"""Load LinkML instance data (YAML) into the tables that linkml-pg-ddl creates.

Each data file holds records of one class, as YAML documents separated by
``---``. The class comes from the file name: ``<anything>-<ClassName>--data.yaml``
(e.g. ``SYNTH1-Person--data.yaml``). Tables and columns are derived from the
LinkML schema the same way ``linkml-pg-ddl`` derives them, in enums-as-varchar
mode, so values of enum-range slots are loaded as plain strings.

How a record maps to rows:

- Scalar slots go into their columns. A multivalued type or enum slot is an
  array column; a single value is wrapped in a one-element array.
- A single-valued class reference goes into ``<slot>_id``. If the value is a
  nested object (a dict), the object is loaded into its own class's table first
  and its id is stored.
- A multivalued class reference becomes rows in the join table
  ``<table>__<slot>``. Nested objects are loaded into their own tables.
- Values are stored as they are: references aren't checked against other
  tables, and enum values aren't checked against the schema.

A record and its nested objects are loaded together in a savepoint: if any of
their rows fails, none of them are stored, the failure is logged, and loading
continues. Rows whose id is already in the table (or already loaded in this
run) are skipped and counted as duplicates. By default the tables that receive
rows are truncated first (``--no-truncate`` to keep their rows). Tables outside
the load that reference them through foreign keys are truncated with them if
they're empty; if any has rows, the run stops instead.

Each data file is loaded in one transaction, committed at the end of the file.
Ctrl-C rolls back the file being loaded; files loaded before it stay.

Exit status: 0 if no record failed, 1 if any failed, 2 if the run couldn't
start (bad file, unknown class, missing tables, no connection, truncate
failed), 130 if interrupted.
"""

import argparse
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, TextIO

import psycopg
import yaml
from linkml_runtime import SchemaView
from psycopg import sql

from py_tools.linkml_pg_ddl import Column, Generator, Table
from py_tools.pg_apply_ddl import add_connection_options, connect, safe_part

PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA = PROJECT_DIR / "schema" / "linkml" / "bdchm.yaml"
DEFAULT_DATA_DIR = PROJECT_DIR / "data" / "harmonized"
DATA_FILE_GLOB = "*--data.yaml"
CLASS_FROM_FILE = re.compile(r"(?:^|-)(?P<cls>[A-Za-z][A-Za-z0-9_]*)--data\.ya?ml$")

try:  # the C loader is much faster on multi-megabyte files
    YamlLoader = yaml.CSafeLoader
except AttributeError:
    YamlLoader = yaml.SafeLoader

EXIT_FAILED = 1
EXIT_SETUP = 2
EXIT_INTERRUPTED = 130


class RecordError(Exception):
    """A record that can't be mapped to rows."""


@dataclass
class Record:
    number: int  # 1-based position in the file
    line: int  # 1-based line where the YAML document starts
    data: dict


@dataclass
class RowOp:
    table: Table
    key: tuple  # the primary key values: (id,) or (owner_id, target_id)
    values: dict[str, Any]  # column name -> value


@dataclass
class Failure:
    record: Record
    error: str


@dataclass
class FileResult:
    loaded: int = 0
    duplicates: int = 0
    failures: list[Failure] = field(default_factory=list)
    rows: Counter = field(default_factory=Counter)  # table -> rows inserted
    skipped_rows: Counter = field(default_factory=Counter)  # table -> duplicate rows skipped
    unknown_keys: Counter = field(default_factory=Counter)

    def counts(self) -> str:
        return f"{self.loaded} loaded, {self.duplicates} duplicate, {len(self.failures)} failed"


def read_records(path: Path) -> list[Record]:
    """The YAML documents of a file, with the line each starts on."""
    records = []
    loader = YamlLoader(path.read_text(encoding="utf-8"))
    try:
        while loader.check_node():
            node = loader.get_node()
            data = loader.construct_document(node)
            if data is None:
                continue
            if not isinstance(data, dict):
                raise ValueError(f"line {node.start_mark.line + 1}: a record must be a mapping")
            records.append(Record(len(records) + 1, node.start_mark.line + 1, data))
    finally:
        loader.dispose()
    return records


class Mapper:
    """Turns records into row inserts, using the tables linkml-pg-ddl derives."""

    def __init__(self, sv: SchemaView):
        self.sv = sv
        self.gen = Generator(sv, enums_as_varchar=True)
        self.gen.build()
        self.class_table: dict[str, Table] = {}
        self.columns: dict[tuple[str, str], Column] = {}  # (table, slot) -> column
        self.joins: dict[tuple[str, str], Table] = {}  # (owner table, slot) -> join table
        for t in self.gen.tables:
            if t.class_name is not None:
                self.class_table[t.class_name] = t
                for c in t.columns:
                    self.columns[(t.name, c.slot.name)] = c
            else:
                owner = t.foreign_keys[0].targets[0]
                self.joins[(owner, t.columns[0].slot.name)] = t
        self.class_of_table = {t.name: c for c, t in self.class_table.items()}

    def is_reference(self, column: Column) -> bool:
        return column.slot.range in self.sv.all_classes() and not (column.slot.any_of or column.slot.exactly_one_of)

    def nested_class(self, range_name: str, value: dict) -> str:
        """The class whose table a nested object goes into."""
        tables = self.gen.tables_for_class(range_name)
        if len(tables) == 1:
            return self.class_of_table[tables[0]]
        if range_name in self.class_table:
            return range_name  # the range itself is concrete; subclasses need a type field
        raise RecordError(f"nested {range_name} could be in any of {len(tables)} tables; can't tell which")

    def plan(self, class_name: str, obj: dict, ops: list[RowOp], unknown: Counter) -> str:
        """Append the row inserts for ``obj`` (children first) and return its id."""
        table = self.class_table[class_name]
        id_slot = self.gen.identifier_slot(class_name).name
        oid = obj.get(id_slot)
        if oid is None:
            raise RecordError(f"{class_name} object has no {id_slot}")
        values: dict[str, Any] = {}
        join_ops: list[RowOp] = []
        for key, value in obj.items():
            join = self.joins.get((table.name, key))
            column = self.columns.get((table.name, key))
            if join is not None:
                items = value if isinstance(value, list) else [value]
                range_name = join.columns[0].slot.range
                for item in items:
                    if item is None:
                        continue
                    target = self.plan(self.nested_class(range_name, item), item, ops, unknown) if isinstance(item, dict) else item
                    owner_col, target_col = join.columns[0].name, join.columns[1].name
                    join_ops.append(RowOp(join, (oid, target), {owner_col: oid, target_col: target}))
            elif column is not None:
                if self.is_reference(column) and isinstance(value, dict):
                    value = self.plan(self.nested_class(column.slot.range, value), value, ops, unknown)
                elif column.is_array:
                    if value is not None and not isinstance(value, list):
                        value = [value]
                elif isinstance(value, (list, dict)):
                    raise RecordError(f"{class_name}.{key} is single-valued but has a {type(value).__name__}")
                values[column.name] = value
            else:
                unknown[f"{class_name}.{key}"] += 1
        ops.append(RowOp(table, (oid,), values))
        ops.extend(join_ops)
        return oid


class Log:
    """The log of one data file's load, written as it goes."""

    def __init__(self, path: Path, args: argparse.Namespace, data_file: Path, class_name: str, started: datetime):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.out: TextIO = path.open("w", encoding="utf-8")
        self.write(
            f"pg-load-data {data_file}",
            f"class:    {class_name}",
            f"target:   {args.user}@{args.host}:{args.port}/{args.dbname}, schema {args.schema}",
            f"truncate: {'yes, before loading' if args.truncate else 'no'}",
            f"started:  {started.isoformat(timespec='seconds')}",
            "",
        )

    def write(self, *lines: str) -> None:
        self.out.write("\n".join(lines) + "\n")
        self.out.flush()

    def finish(self, result: FileResult, ended: str) -> None:
        self.write(f"{ended}: {result.counts()} records", "", "rows inserted / duplicate rows skipped, per table:")
        for table in sorted(set(result.rows) | set(result.skipped_rows)):
            self.write(f"  {table:<45} {result.rows[table]:>8} {result.skipped_rows[table]:>8}")
        if result.unknown_keys:
            self.write("", "keys that aren't slots of the class (ignored):")
            for key, n in sorted(result.unknown_keys.items()):
                self.write(f"  {key}: {n} records")
        if result.failures:
            self.write("", "=" * 30 + " failures " + "=" * 30)
        for f in result.failures:
            self.write(
                "",
                f"record {f.record.number} (line {f.record.line}), id {f.record.data.get('id')!r}:",
                f.error,
                "",
                yaml.safe_dump(f.record.data, sort_keys=False, allow_unicode=True).rstrip(),
                "-" * 70,
            )
        self.out.close()


def log_path_for(args: argparse.Namespace, data_file: Path, started: datetime) -> Path:
    folder = args.log_dir.joinpath(
        safe_part(f"{args.host}-{args.port}"),
        safe_part(args.dbname),
        safe_part(args.schema),
        "load",
        safe_part(data_file.parent.name),
        safe_part(data_file.stem),
    )
    stamp = f"{started:%Y%m%d-%H%M%S}"
    path, n = folder / f"{stamp}.log", 2
    while path.exists():
        path, n = folder / f"{stamp}-{n}.log", n + 1
    return path


def error_text(e: psycopg.Error) -> str:
    d = e.diag
    lines = [f"SQLSTATE {e.sqlstate}: {(d.message_primary or str(e)).strip()}"]
    if d.message_detail:
        lines.append(f"DETAIL: {d.message_detail}")
    if d.message_hint:
        lines.append(f"HINT: {d.message_hint}")
    return "\n".join(lines)


def load_order(mapper: Mapper, classes: list[str]) -> list[str]:
    """Referenced classes first, so loading works with foreign keys in place."""
    deps = {
        c: {
            s.range
            for s in mapper.sv.class_induced_slots(c)
            if s.range in classes and s.range != c and not s.multivalued
        }
        for c in classes
    }
    ordered: list[str] = []
    while deps:
        ready = sorted(c for c, d in deps.items() if not d - set(ordered))
        if not ready:  # a cycle: fall back to name order for the rest
            ready = sorted(deps)
        for c in ready:
            ordered.append(c)
            del deps[c]
    return ordered


def referencing_tables(conn: psycopg.Connection, tables: list[str]) -> list[str]:
    """Other tables that reference ``tables`` through foreign keys, directly or not.

    TRUNCATE refuses to empty a table that another table references unless both
    are truncated together.
    """
    oids = {conn.execute("SELECT to_regclass(%s)::oid", [t]).fetchone()[0] for t in tables}
    found: dict[int, str] = {}
    frontier = set(oids)
    while frontier:
        rows = conn.execute(
            "SELECT DISTINCT c.conrelid::oid, k.relname FROM pg_constraint c JOIN pg_class k ON k.oid = c.conrelid "
            "WHERE c.contype = 'f' AND c.confrelid = ANY(%s)",
            [list(frontier)],
        ).fetchall()
        frontier = {oid for oid, _ in rows if oid not in oids and oid not in found}
        found.update((oid, name) for oid, name in rows if oid in frontier)
    return sorted(found.values())


def existing_keys(conn: psycopg.Connection, table: Table) -> set[tuple]:
    cols = sql.SQL(", ").join(sql.Identifier(c) for c in table.primary_key)
    query = sql.SQL("SELECT {} FROM {}").format(cols, sql.Identifier(table.name))
    return {tuple(row) for row in conn.execute(query)}


def insert(conn: psycopg.Connection, op: RowOp) -> None:
    names = list(op.values)
    query = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
        sql.Identifier(op.table.name),
        sql.SQL(", ").join(sql.Identifier(n) for n in names),
        sql.SQL(", ").join(sql.Placeholder() for _ in names),
    )
    conn.execute(query, [op.values[n] for n in names])


def load_file(
    conn: psycopg.Connection,
    plans: list[tuple[Record, list[RowOp] | str]],
    seen: dict[str, set[tuple]],
    result: FileResult,
    log: Log,
) -> None:
    """Insert one file's planned rows: one savepoint per record, one transaction per file."""
    with conn.transaction():
        for record, ops in plans:
            if isinstance(ops, str):  # couldn't be mapped
                result.failures.append(Failure(record, ops))
                print(f"  FAILED record {record.number} (line {record.line}): {ops}", file=sys.stderr)
                continue
            # The record's own row: the last class-table row (nested objects come
            # before it, its join rows after it).
            top = next(o for o in reversed(ops) if o.table.class_name)
            if top.key in seen[top.table.name]:
                result.duplicates += 1
                continue
            added: list[tuple[str, tuple]] = []
            inserted: Counter = Counter()
            skipped: Counter = Counter()
            try:
                with conn.transaction():  # savepoint
                    for op in ops:
                        if op.key in seen[op.table.name]:
                            skipped[op.table.name] += 1
                            continue
                        insert(conn, op)
                        seen[op.table.name].add(op.key)
                        added.append((op.table.name, op.key))
                        inserted[op.table.name] += 1
            except psycopg.Error as e:
                if conn.broken:
                    raise
                for table, key in added:  # rolled back, so not loaded after all
                    seen[table].discard(key)
                result.failures.append(Failure(record, error_text(e)))
                print(f"  FAILED record {record.number} (line {record.line}): {error_text(e).splitlines()[0]}", file=sys.stderr)
                continue
            result.loaded += 1
            result.rows.update(inserted)
            result.skipped_rows.update(skipped)


def main(argv: list[str] | None = None) -> None:
    sys.stdout.reconfigure(line_buffering=True)
    parser = argparse.ArgumentParser(
        description=(
            "Load LinkML instance data (YAML, one class per file) into the tables created by "
            "linkml-pg-ddl, treating enum values as strings. Logs one file per data file."
        ),
    )
    parser.add_argument(
        "data",
        type=Path,
        nargs="*",
        help=(
            f"data files <...>-<Class>--data.yaml, or directories to load all {DATA_FILE_GLOB} "
            f"files in (not subdirectories) (default: {DEFAULT_DATA_DIR})"
        ),
    )
    add_connection_options(parser, maintenance_db=False)
    parser.add_argument(
        "--linkml-schema",
        type=Path,
        default=DEFAULT_SCHEMA,
        help=f"LinkML schema the tables were generated from (default: {DEFAULT_SCHEMA})",
    )
    parser.add_argument(
        "--truncate",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="empty the tables that receive rows before loading (default: yes)",
    )
    args = parser.parse_args(argv)
    sys.exit(run(args))


def run(args: argparse.Namespace) -> int:
    files = []
    for arg in args.data or [DEFAULT_DATA_DIR]:
        path = arg.resolve()
        if path.is_dir():
            found = sorted(path.glob(DATA_FILE_GLOB))
            if not found:
                print(f"pg-load-data: no {DATA_FILE_GLOB} files in {path}", file=sys.stderr)
                return EXIT_SETUP
            files += found
        else:
            files.append(path)
    files = list(dict.fromkeys(files))  # the same file given twice (e.g. also via its directory)
    if not args.linkml_schema.is_file():
        print(f"pg-load-data: LinkML schema not found: {args.linkml_schema}", file=sys.stderr)
        return EXIT_SETUP

    print(f"Reading the LinkML schema {args.linkml_schema}")
    mapper = Mapper(SchemaView(str(args.linkml_schema.resolve())))

    # Read and map everything first, so the set of tables to truncate is known.
    by_class: dict[str, tuple[Path, list[tuple[Record, list[RowOp] | str]], Counter]] = {}
    for path in files:
        m = CLASS_FROM_FILE.search(path.name)
        if not path.is_file() or not m:
            print(f"pg-load-data: not a <...>-<Class>--data.yaml file: {path}", file=sys.stderr)
            return EXIT_SETUP
        class_name = m["cls"]
        if class_name not in mapper.class_table:
            print(f"pg-load-data: {path.name}: {class_name} isn't a concrete class of the schema", file=sys.stderr)
            return EXIT_SETUP
        if class_name in by_class:
            print(f"pg-load-data: two files for {class_name}: {by_class[class_name][0]} and {path}", file=sys.stderr)
            return EXIT_SETUP
        print(f"Reading {path.name}")
        try:
            records = read_records(path)
        except (yaml.YAMLError, ValueError) as e:
            print(f"pg-load-data: {path}: {e}", file=sys.stderr)
            return EXIT_SETUP
        unknown: Counter = Counter()
        plans: list[tuple[Record, list[RowOp] | str]] = []
        for record in records:
            ops: list[RowOp] = []
            try:
                mapper.plan(class_name, record.data, ops, unknown)
                plans.append((record, ops))
            except RecordError as e:
                plans.append((record, str(e)))
        by_class[class_name] = (path, plans, unknown)

    tables = {op.table.name: op.table for _, plans, _ in by_class.values() for _, ops in plans if not isinstance(ops, str) for op in ops}
    print(f"Target: {args.user}@{args.host}:{args.port}/{args.dbname}, schema {args.schema}")
    log = result = None
    failed = 0
    try:
        with connect(args, args.dbname) as conn:
            conn.autocommit = False
            conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(args.schema)))
            missing = [t for t in sorted(tables) if conn.execute("SELECT to_regclass(%s)", [t]).fetchone()[0] is None]
            conn.commit()
            if missing:
                print(
                    f"pg-load-data: tables missing in schema {args.schema}: {', '.join(missing)}\n"
                    "Apply 01_tables__enums_as_varchar.sql first.",
                    file=sys.stderr,
                )
                return EXIT_SETUP
            if args.truncate:
                extra = referencing_tables(conn, sorted(tables))
                busy = [t for t in extra if conn.execute(
                    sql.SQL("SELECT EXISTS (SELECT 1 FROM {})").format(sql.Identifier(t))).fetchone()[0]]
                if busy:
                    conn.rollback()
                    print(
                        f"pg-load-data: can't truncate: these tables have rows and reference the tables "
                        f"being loaded, through foreign keys: {', '.join(busy)}\n"
                        "Empty them first, drop those foreign keys, or use --no-truncate.",
                        file=sys.stderr,
                    )
                    return EXIT_SETUP
                print(f"Truncating {len(tables)} tables" + (f", plus {len(extra)} empty ones that reference them" if extra else ""))
                names = sql.SQL(", ").join(sql.Identifier(t) for t in sorted(tables) + extra)
                conn.execute(sql.SQL("TRUNCATE {}").format(names))
                conn.commit()
            seen = defaultdict(set)
            for name, table in tables.items():
                seen[name] = existing_keys(conn, table)
            conn.commit()

            for class_name in load_order(mapper, list(by_class)):
                path, plans, unknown = by_class[class_name]
                started = datetime.now()
                log = Log(log_path_for(args, path, started), args, path, class_name, started)
                result = FileResult(unknown_keys=unknown)
                print(f"Loading {path.name} ({len(plans)} records) as {class_name}")
                load_file(conn, plans, seen, result, log)
                log.finish(result, f"finished {datetime.now().isoformat(timespec='seconds')}")
                print(f"  {result.counts()}\n  Log: {log.path}")
                failed += len(result.failures)
                log = None
    except KeyboardInterrupt:
        if log is not None:
            log.finish(FileResult(), "INTERRUPTED (Ctrl-C); this file was rolled back")
            print(f"\nInterrupted; {log.path.parent.name} rolled back. Log: {log.path}", file=sys.stderr)
        return EXIT_INTERRUPTED
    except psycopg.Error as e:
        if log is not None:
            log.finish(FileResult(), f"STOPPED: {str(e).strip()}; this file was rolled back")
        print(f"pg-load-data: {str(e).strip()}", file=sys.stderr)
        return EXIT_SETUP

    if failed:
        print(f"{failed} record(s) failed; see the log(s) above.", file=sys.stderr)
        return EXIT_FAILED
    return 0


if __name__ == "__main__":
    main()

"""Export one JSON document per participant, with all of its data, from PostgreSQL.

Reads the tables that linkml-pg-ddl creates (enums-as-varchar mode) and writes
``<out-dir>/<participant id>.json`` for each participant. The documents are
meant to be indexed into Elasticsearch, with nested fields that follow the
LinkML model.

Every record has one home in a document:

- The participant is the document itself (the root).
- **Members** are records that reference the participant directly: any slot
  whose range can be a Participant holds its id (``associated_participant``,
  ``Specimen.source_participant``, an ``Entity``-range slot). They go into
  top-level arrays named after their table (``condition``, ``visit``, ...).
- **Indirect records** are records of classes that can't belong to a
  participant (no slot whose range can be a Participant) that reference a
  record with a home here, through a slot whose range is that record's single
  table (``QuestionnaireResponse.associated_visit``). They're nested inside it,
  in an array named after their table, recursively. A record of a
  participant-capable class that doesn't reference the participant is in no
  document; the log counts these. (Anything wider couldn't be given a finite
  Elasticsearch mapping; see es_participant_mapping.)

References: a reference to the participant itself, or to the record a nested
record sits in, is left out. So is an id that isn't in any table the slot's
range can live in. Otherwise each reference slot has one JSON type, fixed by
the schema so that it's the same in every document (Elasticsearch requires
this):

- **id slots**: the range is a single class whose records belong to a
  participant (Visit, Condition, ...: it has a slot whose range can be a
  Participant), or Participant itself. Always the id string, for linking.
- **object slots**: every other reference slot (Quantity, Assay, Person,
  Organization, polymorphic ranges such as Entity, ...). Always an object: the
  record inlined recursively, or a stub ``{"id": ...}`` if the record has a
  home in this document, or a record of the same class is already being
  inlined further up the same path (which ends cycles and bounds the depth).

A polymorphic slot (its range maps to several tables) is looked up in all of
them, and its objects, stubs included, get ``"_class": "<ClassName>"``. If no
class of the range can belong to a participant (QuestionnaireResponseValue),
a slot whose range differs between those classes gets a type suffix in the
key, e.g. ``value_boolean``, ``value_decimal``, ``value_time_point``.

Keys are LinkML slot names. Null values and empty arrays are left out. Arrays
of records are sorted by id, so repeated exports give identical files.

Each document goes through the post-processors (``participant_postprocess``)
before it's written.

Exit status: 0 if every participant was written, 1 if any failed (a
post-processor error), 2 if the run couldn't start, 130 if interrupted.
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Any

import psycopg
from linkml_runtime import SchemaView
from linkml_runtime.linkml_model.meta import SlotDefinition
from psycopg import sql
from psycopg.rows import dict_row

from py_tools import participant_postprocess as post
from py_tools.pg_apply_ddl import add_connection_options, connect, safe_part
from py_tools.linkml_pg_ddl import snake
from py_tools.pg_load_data import DEFAULT_SCHEMA, Mapper

PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIR = PROJECT_DIR / "data" / "participants"
ROOT_CLASS = "Participant"
CLASS_KEY = "_class"

EXIT_FAILED = 1
EXIT_SETUP = 2
EXIT_INTERRUPTED = 130

OMIT = object()  # a reference that's left out of the document

Key = tuple[str, Any]  # (table name, id)


def json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, list):
        return [json_value(v) for v in value if v is not None]
    return value


class Exporter:
    def __init__(self, mapper: Mapper, rows: dict[str, dict[Any, dict]], joins: dict[tuple[str, str], dict[Any, list]]):
        self.m = mapper
        self.sv = mapper.sv
        self.gen = mapper.gen
        self.rows = rows
        self.joins = joins
        self.root_table = mapper.class_table[ROOT_CLASS].name
        self.slots: dict[str, list[SlotDefinition]] = {}
        for class_name in mapper.class_table:
            id_name = self.gen.identifier_slot(class_name).name
            slots = self.sv.class_induced_slots(class_name)
            slots.sort(key=lambda s: s.name != id_name)  # identifier first, as in the tables
            self.slots[class_name] = slots
        self.range_tables: dict[str, list[str]] = {}
        # target id -> [(referencing table, referencing id, slot name)]
        self.referrers: dict[Any, list[tuple[str, Any, str]]] = defaultdict(list)
        for class_name, table in mapper.class_table.items():
            for slot in self.slots[class_name]:
                if not self.is_class_range(slot):
                    continue
                join = mapper.joins.get((table.name, slot.name))
                if join is not None:
                    for owner, targets in joins.get((table.name, slot.name), {}).items():
                        for target in targets:
                            self.referrers[target].append((table.name, owner, slot.name))
                else:
                    column = mapper.columns[(table.name, slot.name)].name
                    for rid, row in rows[table.name].items():
                        if row.get(column) is not None:
                            self.referrers[row[column]].append((table.name, rid, slot.name))
        self.unresolved: Counter = Counter()
        # Classes whose records can belong to a participant: they have a slot whose
        # range can be a Participant. Decides the JSON type of slots (see id_slot()).
        self.member_classes = {
            class_name
            for class_name in mapper.class_table
            if class_name != ROOT_CLASS
            and any(
                self.root_table in self.tables_for(slot.range)
                for slot in self.slots[class_name]
                if self.is_class_range(slot)
            )
        }

        # Per polymorphic range mapped in full: slot name -> {class -> key}, for slots
        # whose range differs between the classes (see union_key()).
        self.union_renames: dict[str, dict[str, dict[str, str]]] = {}
        self.homed_all: set[Key] = set()  # over every document built, for the log

    def is_polymorphic(self, slot: SlotDefinition) -> bool:
        return len(self.tables_for(slot.range)) > 1

    def union_slot(self, slot: SlotDefinition) -> bool:
        """A polymorphic slot whose objects are inlined and mapped in full.

        That's when no class of the range can belong to a participant (e.g.
        QuestionnaireResponseValue). Other polymorphic slots (Entity, File,
        Observation) are mapped with ``_class`` and the id only.
        """
        return self.is_polymorphic(slot) and not any(
            self.m.class_of_table[t] in self.member_classes or t == self.root_table
            for t in self.tables_for(slot.range)
        )

    def union_key(self, range_name: str, class_name: str, slot_name: str) -> str:
        """Key of a slot inside an object of a full-union polymorphic slot.

        If the slot's range differs between the range's classes (bdchm's
        QuestionnaireResponseValue*.value is boolean, decimal, integer, string
        or a TimePoint), one field can't hold them all in Elasticsearch, so the
        key gets the range as a suffix: ``value_boolean``, ``value_time_point``.
        """
        if range_name not in self.union_renames:
            ranges: dict[str, dict[str, str]] = defaultdict(dict)
            for t in self.tables_for(range_name):
                c = self.m.class_of_table[t]
                for slot in self.slots[c]:
                    ranges[slot.name][c] = slot.range
            self.union_renames[range_name] = {
                name: {c: f"{name}_{snake(r)}" for c, r in by_class.items()}
                for name, by_class in ranges.items()
                if len(set(by_class.values())) > 1
            }
        return self.union_renames[range_name].get(slot_name, {}).get(class_name, slot_name)

    def nestable(self, referrer_table: str, slot_name: str, container_table: str) -> bool:
        """Whether a referrer is nested under a record it references (an indirect record).

        Only records of classes that can't belong to a participant, through a
        slot whose range is that one table: QuestionnaireResponse.associated_visit
        in bdchm. Anything wider can't be given a finite Elasticsearch mapping.
        """
        class_name = self.m.class_of_table[referrer_table]
        if class_name == ROOT_CLASS or class_name in self.member_classes:
            return False
        return self.tables_for(self.slot_range(referrer_table, slot_name)) == [container_table]

    def is_class_range(self, slot: SlotDefinition) -> bool:
        return slot.range in self.sv.all_classes() and not (slot.any_of or slot.exactly_one_of)

    def tables_for(self, range_name: str) -> list[str]:
        if range_name not in self.range_tables:
            self.range_tables[range_name] = self.gen.tables_for_class(range_name)
        return self.range_tables[range_name]

    def id_slot(self, slot: SlotDefinition) -> bool:
        """Whether a reference slot is written as an id string rather than an object.

        Fixed per slot, so a field has the same JSON type in every document (as
        Elasticsearch requires): a slot whose range is one table, of a class
        whose records belong to a participant (or the participant class), is
        always the id. Every other reference slot is always an object.
        """
        tables = self.tables_for(slot.range)
        if len(tables) != 1:
            return False
        class_name = self.m.class_of_table[tables[0]]
        return class_name == ROOT_CLASS or class_name in self.member_classes

    def slot_range(self, table: str, slot_name: str) -> str:
        class_name = self.m.class_of_table[table]
        return next(s.range for s in self.slots[class_name] if s.name == slot_name)

    def locate(self, range_name: str, rid: Any) -> str | None:
        """The table, among those the range can live in, that has the id."""
        return next((t for t in self.tables_for(range_name) if rid in self.rows[t]), None)

    # -- one participant -------------------------------------------------

    def homes(self, pid: Any) -> tuple[dict[str, list[Any]], dict[Key, list[Key]], set[Key]]:
        """Members (table -> ids), indirect records (container -> records), and all homed keys."""
        members: dict[str, list[Any]] = defaultdict(list)
        nested: dict[Key, list[Key]] = defaultdict(list)
        homed: set[Key] = {(self.root_table, pid)}
        queue: list[Key] = []
        for table, rid, slot in self.referrers.get(pid, []):
            key = (table, rid)
            if table == self.root_table or key in homed:
                continue
            if self.root_table in self.tables_for(self.slot_range(table, slot)):
                homed.add(key)
                members[table].append(rid)
                queue.append(key)
        while queue:
            container = queue.pop(0)
            for table, rid, slot in self.referrers.get(container[1], []):
                key = (table, rid)
                if key in homed or not self.nestable(table, slot, container[0]):
                    continue
                homed.add(key)
                nested[container].append(key)
                queue.append(key)
        return members, nested, homed

    def document(self, pid: Any) -> dict:
        members, nested, homed = self.homes(pid)
        self.homed_all |= homed
        root = (self.root_table, pid)
        ctx = _Build(root=root, homed=homed, nested=nested)
        doc = self.build(root, ctx, path=frozenset({root}), container=None, with_nested=True)
        for table in sorted(members, key=self.table_order):
            records = [
                self.build((table, rid), ctx, path=frozenset({(table, rid)}), container=None, with_nested=True)
                for rid in sorted(members[table], key=str)
            ]
            doc[self.array_key(ROOT_CLASS, table)] = records
        return doc

    def table_order(self, table: str) -> int:
        return [t.name for t in self.gen.tables].index(table)

    def array_key(self, class_name: str, table: str) -> str:
        """Name of an array of records in an object; avoids clashing with a slot name."""
        slot_names = {s.name for s in self.slots[class_name]}
        return f"{table}__referrers" if table in slot_names else table

    def build(self, key: Key, ctx: "_Build", path: frozenset, container: Key | None, with_nested: bool) -> dict:
        table, rid = key
        class_name = self.m.class_of_table[table]
        row = self.rows[table][rid]
        obj: dict[str, Any] = {}
        for slot in self.slots[class_name]:
            join = self.m.joins.get((table, slot.name))
            if join is not None:
                items = []
                for target in self.joins.get((table, slot.name), {}).get(rid, []):
                    value = self.reference(class_name, slot, target, ctx, path, key)
                    if value is not OMIT:
                        items.append(value)
                if items:
                    obj[slot.name] = items
                continue
            column = self.m.columns.get((table, slot.name))
            if column is None:
                continue
            value = row.get(column.name)
            if value is None:
                continue
            if self.is_class_range(slot):
                value = self.reference(class_name, slot, value, ctx, path, key)
                if value is OMIT:
                    continue
            else:
                value = json_value(value)
                if value == []:
                    continue
            obj[slot.name] = value
        if with_nested:
            groups: dict[str, list[Any]] = defaultdict(list)
            for child_table, child_id in ctx.nested.get(key, []):
                groups[child_table].append(child_id)
            for child_table in sorted(groups, key=self.table_order):
                obj[self.array_key(class_name, child_table)] = [
                    self.build((child_table, cid), ctx, path=frozenset({(child_table, cid)}), container=key, with_nested=True)
                    for cid in sorted(groups[child_table], key=str)
                ]
        return obj

    def reference(self, class_name: str, slot: SlotDefinition, target: Any, ctx: "_Build", path: frozenset, owner: Key) -> Any:
        found = self.locate(slot.range, target)
        if found is None:
            self.unresolved[f"{class_name}.{slot.name}"] += 1
            return OMIT
        key = (found, target)
        if key == ctx.root or key == ctx.container_of(owner):
            return OMIT
        if self.id_slot(slot):
            return target
        found_class = self.m.class_of_table[found]
        if key in ctx.homed or found == self.root_table or any(t == found for t, _ in path):
            # Has a home in this document, or a record of the same class is being
            # inlined further up this path (which bounds the depth, so that the
            # Elasticsearch mapping is finite): a stub.
            obj = {self.gen.identifier_slot(found_class).name: target}
        else:
            obj = self.build(key, ctx, path=path | {key}, container=None, with_nested=False)
            if self.union_slot(slot):
                obj = {self.union_key(slot.range, found_class, k): v for k, v in obj.items()}
        if self.is_polymorphic(slot):
            obj = {CLASS_KEY: found_class, **obj}
        return obj


class _Build:
    """Per-document state."""

    def __init__(self, root: Key, homed: set[Key], nested: dict[Key, list[Key]]):
        self.root = root
        self.homed = homed
        self.nested = nested
        self.container = {child: parent for parent, children in nested.items() for child in children}

    def container_of(self, key: Key) -> Key | None:
        return self.container.get(key)


def read_tables(conn: psycopg.Connection, mapper: Mapper) -> tuple[dict, dict]:
    rows: dict[str, dict[Any, dict]] = {}
    joins: dict[tuple[str, str], dict[Any, list]] = {}
    for table in mapper.gen.tables:
        query = sql.SQL("SELECT * FROM {}").format(sql.Identifier(table.name))
        if table.class_name is not None:
            pk = table.primary_key[0]
            rows[table.name] = {r[pk]: r for r in conn.execute(query) if r[pk] is not None}
        else:
            owner_table = table.foreign_keys[0].targets[0]
            owner_col, target_col = table.columns[0].name, table.columns[1].name
            by_owner: dict[Any, list] = defaultdict(list)
            for r in conn.execute(query):
                if r[owner_col] is not None and r[target_col] is not None:
                    by_owner[r[owner_col]].append(r[target_col])
            joins[(owner_table, table.columns[0].slot.name)] = {k: sorted(set(v), key=str) for k, v in by_owner.items()}
    return rows, joins


def main(argv: list[str] | None = None) -> None:
    sys.stdout.reconfigure(line_buffering=True)
    parser = argparse.ArgumentParser(
        description=(
            "Write one JSON document per participant, with all of its data, read from the "
            "tables created by linkml-pg-ddl (enums-as-varchar). Meant for Elasticsearch."
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
        "--out-dir",
        type=Path,
        default=DEFAULT_OUT_DIR,
        help=f"folder for the <participant id>.json files (default: {DEFAULT_OUT_DIR})",
    )
    parser.add_argument(
        "--participant",
        action="append",
        default=[],
        metavar="ID",
        help="export only this participant (repeatable); other files in --out-dir are kept",
    )
    parser.add_argument(
        "--post-processor",
        action="append",
        default=[],
        metavar="MODULE:NAME",
        help="extra post-processor to run after the built-in ones (repeatable)",
    )
    sys.exit(run(parser.parse_args(argv)))


def run(args: argparse.Namespace) -> int:
    started = datetime.now()
    try:
        processors = [post.instantiate(p) for p in post.POST_PROCESSORS] + [post.load(s) for s in args.post_processor]
    except (ImportError, AttributeError, ValueError) as e:
        print(f"pg-export-participants: {e}", file=sys.stderr)
        return EXIT_SETUP
    if not args.linkml_schema.is_file():
        print(f"pg-export-participants: LinkML schema not found: {args.linkml_schema}", file=sys.stderr)
        return EXIT_SETUP

    print(f"Reading the LinkML schema {args.linkml_schema}")
    mapper = Mapper(SchemaView(str(args.linkml_schema.resolve())))
    print(f"Source: {args.user}@{args.host}:{args.port}/{args.dbname}, schema {args.schema}")
    try:
        with connect(args, args.dbname) as conn:
            conn.row_factory = dict_row
            conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(args.schema)))
            missing = [t.name for t in mapper.gen.tables if conn.execute("SELECT to_regclass(%s) AS r", [t.name]).fetchone()["r"] is None]
            if missing:
                print(
                    f"pg-export-participants: {len(missing)} tables missing in schema {args.schema}, "
                    f"e.g. {', '.join(missing[:3])}. Apply 01_tables__enums_as_varchar.sql first.",
                    file=sys.stderr,
                )
                return EXIT_SETUP
            rows, joins = read_tables(conn, mapper)
    except psycopg.Error as e:
        print(f"pg-export-participants: {str(e).strip()}", file=sys.stderr)
        return EXIT_SETUP

    exporter = Exporter(mapper, rows, joins)
    all_ids = sorted(rows[exporter.root_table], key=str)
    if args.participant:
        unknown = [p for p in args.participant if p not in rows[exporter.root_table]]
        if unknown:
            print(f"pg-export-participants: no such participant: {', '.join(unknown)}", file=sys.stderr)
            return EXIT_SETUP
        ids = list(dict.fromkeys(args.participant))
    else:
        ids = all_ids

    out_dir = args.out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"Writing {len(ids)} participant(s) to {out_dir}")
    written: set[Path] = set()
    failures: list[tuple[Any, str]] = []
    arrays: Counter = Counter()
    try:
        for pid in ids:
            try:
                doc = exporter.document(pid)
                ctx = post.Context(participant_id=pid, schema_view=mapper.sv, rows=rows)
                doc = post.run(doc, ctx, processors)
                text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
            except Exception as e:  # a post-processor can raise anything
                failures.append((pid, f"{type(e).__name__}: {e}"))
                print(f"  FAILED {pid}: {type(e).__name__}: {e}", file=sys.stderr)
                continue
            path = out_dir / f"{safe_part(str(pid))}.json"
            path.write_text(text, encoding="utf-8")
            written.add(path)
            arrays.update({k: len(v) for k, v in doc.items() if isinstance(v, list) and v and isinstance(v[0], dict)})
    except KeyboardInterrupt:
        print(f"\nInterrupted after {len(written)} file(s).", file=sys.stderr)
        return EXIT_INTERRUPTED

    # Records of participant-capable classes that no exported document contains:
    # they reference no participant (and aren't nested anywhere).
    orphans: Counter = Counter()
    if not args.participant:
        for class_name in exporter.member_classes:
            table = mapper.class_table[class_name].name
            orphans[table] = sum(1 for rid in rows[table] if (table, rid) not in exporter.homed_all)
        orphans = +orphans

    removed = []
    if not args.participant:
        # A full export: files of participants no longer in the database go. A
        # participant that failed keeps its previous file, if any.
        current = {f"{safe_part(str(pid))}.json" for pid in all_ids}
        for stale in sorted(out_dir.glob("*.json")):
            if stale.name not in current:
                stale.unlink()
                removed.append(stale.name)
    post.finish(processors, {"written": len(written), "failed": len(failures)})

    log_path = write_log(args, started, out_dir, ids, written, removed, failures, arrays, exporter.unresolved, processors, orphans)
    print(f"  {len(written)} written, {len(failures)} failed, {len(removed)} stale file(s) removed\n  Log: {log_path}")
    if exporter.unresolved:
        print(f"  {sum(exporter.unresolved.values())} reference(s) didn't resolve and were left out; see the log.")
    if orphans:
        print(f"  {sum(orphans.values())} record(s) aren't linked to any participant and weren't exported; see the log.")
    return EXIT_FAILED if failures else 0


def write_log(args, started, out_dir, ids, written, removed, failures, arrays, unresolved, processors, orphans) -> Path:
    folder = args.log_dir.joinpath(
        safe_part(f"{args.host}-{args.port}"),
        safe_part(args.dbname),
        safe_part(args.schema),
        "export",
        safe_part(out_dir.name),
    )
    folder.mkdir(parents=True, exist_ok=True)
    stamp = f"{started:%Y%m%d-%H%M%S}"
    path, n = folder / f"{stamp}.log", 2
    while path.exists():
        path, n = folder / f"{stamp}-{n}.log", n + 1
    lines = [
        f"pg-export-participants to {out_dir}",
        f"source:   {args.user}@{args.host}:{args.port}/{args.dbname}, schema {args.schema}",
        f"started:  {started.isoformat(timespec='seconds')}",
        f"finished: {datetime.now().isoformat(timespec='seconds')}",
        f"post-processors: {', '.join(post.name_of(p) for p in processors) or 'none'}",
        "",
        f"participants: {len(ids)} selected, {len(written)} written, {len(failures)} failed",
        f"stale files removed: {len(removed)}",
        "",
        "top-level arrays of records (records over all documents):",
        *[f"  {k:<45} {v:>8}" for k, v in sorted(arrays.items())],
        "",
        "references left out because the id isn't in any table of the slot's range",
        "(class.slot: count over all documents):",
        *([f"  {k:<45} {v:>8}" for k, v in sorted(unresolved.items())] or ["  none"]),
        "",
        "records of participant-capable classes in no document (they reference no",
        "participant; full exports only), per table:",
        *([f"  {k:<45} {v:>8}" for k, v in sorted(orphans.items())] or ["  none"]),
    ]
    if failures:
        lines += ["", "=" * 30 + " failures " + "=" * 30, *[f"{pid}: {err}" for pid, err in failures]]
    if removed:
        lines += ["", "removed:", *[f"  {name}" for name in removed]]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


if __name__ == "__main__":
    main()

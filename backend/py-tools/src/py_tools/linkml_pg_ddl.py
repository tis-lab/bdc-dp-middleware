"""Generate PostgreSQL DDL from a LinkML schema.

Writes one file per kind of DDL into ``schema/pg-ddl/<schema-file-stem>/``:

    00_enum_types.sql    CREATE TYPE ... AS ENUM (native enum mode only)
    01_tables.sql        CREATE TABLE and COMMENT ON
    02_primary_keys.sql  PRIMARY KEY constraints
    03_foreign_keys.sql  FOREIGN KEY constraints
    04_constraints.sql   CHECK and UNIQUE constraints
    05_not_null.sql      NOT NULL for required slots (optional to apply)

Mapping:

- Each concrete (non-abstract, non-mixin) class becomes a table holding all of
  its induced (own and inherited) slots. Abstract classes and mixins get no
  table.
- A single-valued slot whose range is a class becomes a ``<slot>_id`` column
  holding the referenced object's identifier.
- A multivalued slot whose range is a class becomes a join table
  ``<table>__<slot>``. Other multivalued slots become array columns.
- A reference gets a foreign key only when its range class maps to exactly one
  table. A class with concrete subclasses maps to several tables, so those
  references get a comment naming the possible tables instead.
- Enums with listed values become native enum types, or with
  ``--enums-as-varchar`` varchar columns plus CHECK constraints. Dynamic enums
  (values drawn from an ontology, e.g. ``reachable_from``) are always plain
  varchar.

Names are not schema-qualified, so the DDL is applied to whatever PostgreSQL
schema is first on the session's ``search_path``.
"""

import argparse
import hashlib
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

from linkml_runtime import SchemaView
from linkml_runtime.linkml_model.meta import EnumDefinition, SlotDefinition

PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_OUT_ROOT = PROJECT_DIR / "schema" / "pg-ddl"

PG_MAX_IDENTIFIER = 63

# Above this many possible target tables, a no-FK comment names the range class instead.
MAX_LISTED_TARGETS = 8

FILE_ENUM_TYPES = "00_enum_types.sql"
FILE_TABLES = "01_tables.sql"
FILE_PRIMARY_KEYS = "02_primary_keys.sql"
FILE_FOREIGN_KEYS = "03_foreign_keys.sql"
FILE_CONSTRAINTS = "04_constraints.sql"
FILE_NOT_NULL = "05_not_null.sql"
ALL_FILES = [
    FILE_ENUM_TYPES,
    FILE_TABLES,
    FILE_PRIMARY_KEYS,
    FILE_FOREIGN_KEYS,
    FILE_CONSTRAINTS,
    FILE_NOT_NULL,
]

# LinkML built-in types (from linkml:types) to PostgreSQL types.
PG_TYPES = {
    "string": "text",
    "integer": "integer",
    "boolean": "boolean",
    "float": "double precision",
    "double": "double precision",
    "decimal": "numeric",
    "time": "time",
    "date": "date",
    "datetime": "timestamp",
    "date_or_datetime": "text",
    "uriorcurie": "text",
    "curie": "text",
    "uri": "text",
    "ncname": "text",
    "objectidentifier": "text",
    "nodeidentifier": "text",
    "jsonpointer": "text",
    "jsonpath": "text",
    "sparqlpath": "text",
}


def snake(name: str) -> str:
    """``MeasurementObservation`` -> ``measurement_observation``."""
    name = re.sub(r"[^0-9A-Za-z]+", "_", name)
    name = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    name = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", name)
    return name.strip("_").lower()


def pg_name(name: str) -> str:
    """Fit a name into PostgreSQL's 63-byte limit, keeping it unique with a hash."""
    if len(name) <= PG_MAX_IDENTIFIER:
        return name
    digest = hashlib.sha1(name.encode()).hexdigest()[:8]
    return f"{name[: PG_MAX_IDENTIFIER - 9]}_{digest}"


# PostgreSQL keywords that cannot be used as unquoted table, column, or type
# names (pg_get_keywords() catcode R and T, PostgreSQL 18).
PG_RESERVED = set(
    """
    all analyse analyze and any array as asc asymmetric authorization binary both case cast
    check collate collation column concurrently constraint create cross current_catalog
    current_date current_role current_schema current_time current_timestamp current_user
    default deferrable desc distinct do else end except false fetch for foreign freeze from
    full grant group having ilike in initially inner intersect into is isnull join lateral
    leading left like limit localtime localtimestamp natural not notnull null offset on
    only or order outer overlaps placing primary references returning right select
    session_user similar some symmetric system_user table tablesample then to trailing true
    union unique user using variadic verbose when where window with
    """.split()
)


def q(name: str) -> str:
    """Quote a name only if it is a reserved word, so most names stay unquoted."""
    return f'"{name}"' if name in PG_RESERVED else name


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def one_line(text: str | None) -> str:
    return " ".join((text or "").split())


@dataclass
class EnumInfo:
    name: str
    type_name: str
    values: list[str] | None  # None for a dynamic enum


@dataclass
class Column:
    name: str
    pg_type: str
    slot: SlotDefinition
    comment: str
    required: bool
    enum: EnumInfo | None = None
    is_array: bool = False


@dataclass
class ForeignKey:
    column: str
    slot: str
    range_name: str
    targets: list[str]  # tables the referenced class can live in


@dataclass
class Table:
    name: str
    class_name: str | None  # None for a join table
    comment: str
    columns: list[Column] = field(default_factory=list)
    primary_key: list[str] = field(default_factory=list)
    foreign_keys: list[ForeignKey] = field(default_factory=list)
    checks: list[tuple[str, str, str]] = field(default_factory=list)  # (name, expr, comment)
    uniques: list[tuple[str, list[str]]] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


class Generator:
    def __init__(self, sv: SchemaView, enums_as_varchar: bool):
        self.sv = sv
        self.enums_as_varchar = enums_as_varchar
        self.schema_name = sv.schema.name
        self.enums: dict[str, EnumInfo] = {}
        self.tables: list[Table] = []
        self.concrete = [
            c.name
            for c in sv.all_classes().values()
            if not c.abstract and not c.mixin
        ]
        self.table_of = {c: snake(c) for c in self.concrete}

    # -- schema analysis --------------------------------------------------

    def identifier_slot(self, class_name: str) -> SlotDefinition | None:
        return self.sv.get_identifier_slot(class_name, use_key=True)

    def tables_for_class(self, class_name: str) -> list[str]:
        """Tables that can hold an instance of the class (itself and subclasses)."""
        names = self.sv.class_descendants(class_name, reflexive=True)
        return [self.table_of[n] for n in names if n in self.table_of]

    def enum_values(self, enum_name: str, seen: frozenset = frozenset()) -> list[str] | None:
        """Permissible values of an enum, or None if any part of it is dynamic."""
        if enum_name in seen:
            return []
        e: EnumDefinition = self.sv.get_enum(enum_name)
        if e.reachable_from or e.matches or e.concepts:
            return None
        values = list(e.permissible_values)
        for parent in e.inherits or []:
            parent_values = self.enum_values(parent, seen | {enum_name})
            if parent_values is None:
                return None
            values += parent_values
        for expr in e.include or []:
            if expr.reachable_from or expr.matches or expr.concepts or expr.inherits:
                return None
            values += list(expr.permissible_values)
        excluded = set()
        for expr in e.minus or []:
            excluded |= set(expr.permissible_values)
        values = [v for v in dict.fromkeys(values) if v not in excluded]
        # An enum with nothing listed and nothing to draw from is a placeholder;
        # treat it like a dynamic enum rather than an enum with no values.
        return values or None

    def enum_info(self, enum_name: str) -> EnumInfo:
        if enum_name not in self.enums:
            self.enums[enum_name] = EnumInfo(
                name=enum_name,
                type_name=pg_name(snake(enum_name)),
                values=self.enum_values(enum_name),
            )
        return self.enums[enum_name]

    def scalar_type(self, range_name: str) -> str:
        """PostgreSQL type for a LinkML type, following typeof to a built-in."""
        for ancestor in self.sv.type_ancestors(range_name):
            if ancestor in PG_TYPES:
                return PG_TYPES[ancestor]
        return "text"

    def class_label(self, class_name: str) -> str:
        return f"{self.schema_name}:{class_name}"

    # -- building tables ---------------------------------------------------

    def build(self) -> None:
        for class_name in self.concrete:
            if self.identifier_slot(class_name) is None:
                sys.exit(
                    f"error: class {class_name} has no identifier (or key) slot; "
                    "classes without one are not supported yet"
                )
        for class_name in self.concrete:
            self.build_class(class_name)

    def build_class(self, class_name: str) -> None:
        cls = self.sv.get_class(class_name)
        table_name = self.table_of[class_name]
        table = Table(
            name=table_name,
            class_name=class_name,
            comment=f"LinkML class {self.class_label(class_name)}. {one_line(cls.description)}".strip(),
        )
        self.tables.append(table)

        id_slot = self.identifier_slot(class_name)
        slots = self.sv.class_induced_slots(class_name)
        # Identifier first, then the class's slots in schema order.
        slots.sort(key=lambda s: s.name != id_slot.name)

        for slot in slots:
            self.build_slot(table, class_name, slot, is_id=slot.name == id_slot.name)

        table.primary_key = [snake(id_slot.name)]
        for key_name, unique_key in (cls.unique_keys or {}).items():
            columns = [c.name for c in table.columns if c.slot.name in unique_key.unique_key_slots]
            table.uniques.append((key_name, columns))

    def build_slot(self, table: Table, class_name: str, slot: SlotDefinition, is_id: bool) -> None:
        range_name = slot.range or self.sv.schema.default_range or "string"
        origin = f"LinkML slot {self.class_label(class_name)}.{slot.name}"
        comment = f"{origin}. {one_line(slot.description)}".strip()
        required = bool(slot.required) or is_id
        multivalued = bool(slot.multivalued)

        if slot.any_of or slot.exactly_one_of:
            table.notes.append(f"{slot.name}: union range (any_of/exactly_one_of) stored as text")
            range_name = "string"

        if range_name in self.sv.all_classes():
            target_tables = self.tables_for_class(range_name)
            target_id = self.identifier_slot(range_name)
            id_type = self.scalar_type(target_id.range or "string") if target_id else "text"
            if multivalued:
                self.build_join_table(table, class_name, slot, range_name, target_tables, id_type, comment)
                return
            column = pg_name(f"{snake(slot.name)}_id")
            table.columns.append(Column(column, id_type, slot, f"{comment} References {range_name}.", required))
            table.foreign_keys.append(ForeignKey(column, slot.name, range_name, target_tables))
            return

        enum = None
        if range_name in self.sv.all_enums():
            enum = self.enum_info(range_name)
            if enum.values is not None and not self.enums_as_varchar:
                pg_type = q(enum.type_name)
            else:
                pg_type = "varchar"
        else:
            pg_type = self.scalar_type(range_name)

        column = Column(
            name=snake(slot.name),
            pg_type=pg_type + ("[]" if multivalued else ""),
            slot=slot,
            comment=comment,
            required=required,
            enum=enum,
            is_array=multivalued,
        )
        table.columns.append(column)
        self.add_checks(table, column, slot, range_name)

    def add_checks(self, table: Table, column: Column, slot: SlotDefinition, range_name: str) -> None:
        c = column.name
        qc = q(c)
        enum = column.enum
        if enum is not None and enum.values is not None and self.enums_as_varchar:
            values = ", ".join(sql_string(v) for v in enum.values)
            if column.is_array:
                expr = f"{qc} <@ ARRAY[{values}]::varchar[]"
            else:
                expr = f"{qc} IN ({values})"
            name = pg_name(f"{table.name}__{c}__ck_{enum.type_name}")
            table.checks.append((name, expr, f"{c} must be a value of LinkML enum {enum.name}"))

        if column.is_array:
            return  # range checks below apply to single values only
        if slot.minimum_value is not None:
            name = pg_name(f"{table.name}__{c}__ck_minimum")
            table.checks.append((name, f"{qc} >= {slot.minimum_value}", f"minimum_value of {slot.name}"))
        if slot.maximum_value is not None:
            name = pg_name(f"{table.name}__{c}__ck_maximum")
            table.checks.append((name, f"{qc} <= {slot.maximum_value}", f"maximum_value of {slot.name}"))
        if slot.pattern:
            name = pg_name(f"{table.name}__{c}__ck_pattern")
            table.checks.append((name, f"{qc} ~ {sql_string(slot.pattern)}", f"pattern of {slot.name}"))

    def build_join_table(
        self,
        owner: Table,
        class_name: str,
        slot: SlotDefinition,
        range_name: str,
        target_tables: list[str],
        id_type: str,
        comment: str,
    ) -> None:
        owner_id_slot = self.identifier_slot(class_name)
        owner_id_type = self.scalar_type(owner_id_slot.range or "string")
        owner_column = pg_name(f"{owner.name}_id")
        target_column = pg_name(f"{snake(slot.name)}_id")
        if target_column == owner_column:
            target_column = pg_name(f"{snake(slot.name)}_target_id")

        join = Table(
            name=pg_name(f"{owner.name}__{snake(slot.name)}"),
            class_name=None,
            comment=f"Join table for multivalued {comment} Each row links one {class_name} to one {range_name}.",
        )
        join.columns.append(
            Column(owner_column, owner_id_type, slot, f"The {class_name} that owns the value.", True)
        )
        join.columns.append(
            Column(target_column, id_type, slot, f"The referenced {range_name}.", True)
        )
        join.primary_key = [owner_column, target_column]
        join.foreign_keys.append(ForeignKey(owner_column, slot.name, class_name, [owner.name]))
        join.foreign_keys.append(ForeignKey(target_column, slot.name, range_name, target_tables))
        if slot.required:
            owner.notes.append(
                f"{slot.name} is required but stored in join table {join.name}; "
                "NOT NULL cannot enforce that at least one row exists"
            )
        self.tables.append(join)

    # -- rendering ---------------------------------------------------------

    def header(self, title: str, schema_path: Path) -> list[str]:
        mode = "varchar + CHECK" if self.enums_as_varchar else "native enum types"
        return [
            f"-- {title}",
            f"-- Generated by linkml-pg-ddl from LinkML schema '{self.schema_name}' ({schema_path.name}).",
            f"-- Enum mode: {mode}. Do not edit by hand; regenerate instead.",
            "",
        ]

    def render_enum_types(self) -> list[str]:
        out = []
        for enum in self.enums.values():
            if enum.values is None:
                continue
            out.append(f"-- LinkML enum {self.schema_name}:{enum.name}")
            values = ",\n  ".join(sql_string(v) for v in enum.values)
            out.append(f"CREATE TYPE {q(enum.type_name)} AS ENUM (\n  {values}\n);")
            desc = one_line(self.sv.get_enum(enum.name).description)
            out.append(
                f"COMMENT ON TYPE {q(enum.type_name)} IS "
                f"{sql_string(f'LinkML enum {self.schema_name}:{enum.name}. {desc}'.strip())};"
            )
            out.append("")
        return out

    def render_tables(self) -> list[str]:
        out = []
        for t in self.tables:
            origin = f"LinkML class {t.class_name}" if t.class_name else "join table"
            out.append(f"-- {t.name} ({origin})")
            for note in t.notes:
                out.append(f"-- note: {note}")
            width = max(len(q(c.name)) for c in t.columns)
            cols = ",\n".join(f"  {q(c.name).ljust(width)} {c.pg_type}" for c in t.columns)
            out.append(f"CREATE TABLE {q(t.name)} (\n{cols}\n);")
            out.append(f"COMMENT ON TABLE {q(t.name)} IS {sql_string(t.comment)};")
            for c in t.columns:
                out.append(f"COMMENT ON COLUMN {q(t.name)}.{q(c.name)} IS {sql_string(c.comment)};")
            out.append("")
        return out

    def render_primary_keys(self) -> list[str]:
        out = []
        for t in self.tables:
            name = pg_name(f"{t.name}__pk")
            out.append(
                f"ALTER TABLE {q(t.name)}\n  ADD CONSTRAINT {name} PRIMARY KEY ({', '.join(map(q, t.primary_key))});"
            )
        out.append("")
        return out

    def render_foreign_keys(self) -> list[str]:
        out = []
        for t in self.tables:
            for fk in t.foreign_keys:
                if len(fk.targets) == 1:
                    target = fk.targets[0]
                    name = pg_name(f"{t.name}__{fk.column}__fk_{target}")
                    out.append(
                        f"ALTER TABLE {q(t.name)}\n  ADD CONSTRAINT {name}\n"
                        f"  FOREIGN KEY ({q(fk.column)}) REFERENCES {q(target)} ({q(self.target_pk(target))});"
                    )
                elif len(fk.targets) <= MAX_LISTED_TARGETS:
                    out.append(
                        f"-- {t.name}.{fk.column} (slot {fk.slot}): no foreign key, the value can be "
                        f"in any of: {', '.join(fk.targets)}"
                    )
                elif fk.targets:
                    out.append(
                        f"-- {t.name}.{fk.column} (slot {fk.slot}): no foreign key, the value can be "
                        f"in any of {len(fk.targets)} tables ({fk.range_name} and its subclasses)"
                    )
                else:
                    out.append(
                        f"-- {t.name}.{fk.column} (slot {fk.slot}): no foreign key, "
                        "the range class has no concrete table"
                    )
        out.append("")
        return out

    def target_pk(self, table_name: str) -> str:
        for t in self.tables:
            if t.name == table_name:
                return t.primary_key[0]
        raise KeyError(table_name)

    def render_constraints(self) -> list[str]:
        out = []
        for t in self.tables:
            for name, expr, comment in t.checks:
                out.append(f"-- {comment}")
                out.append(f"ALTER TABLE {q(t.name)}\n  ADD CONSTRAINT {name}\n  CHECK ({expr});")
            for key_name, columns in t.uniques:
                name = pg_name(f"{t.name}__uq_{snake(key_name)}")
                out.append(f"ALTER TABLE {q(t.name)}\n  ADD CONSTRAINT {name} UNIQUE ({', '.join(map(q, columns))});")
        out.append("")
        return out

    def render_not_null(self) -> list[str]:
        out = []
        for t in self.tables:
            if t.class_name is None:
                continue  # join table columns are covered by its primary key
            for c in t.columns:
                if c.required and c.name not in t.primary_key:
                    out.append(f"ALTER TABLE {q(t.name)} ALTER COLUMN {q(c.name)} SET NOT NULL;")
        out.append("")
        return out

    def write(self, out_dir: Path, schema_path: Path) -> list[Path]:
        out_dir.mkdir(parents=True, exist_ok=True)
        for name in ALL_FILES:
            (out_dir / name).unlink(missing_ok=True)

        files = {
            FILE_TABLES: ("Tables", self.render_tables()),
            FILE_PRIMARY_KEYS: ("Primary keys", self.render_primary_keys()),
            FILE_FOREIGN_KEYS: ("Foreign keys", self.render_foreign_keys()),
            FILE_CONSTRAINTS: ("Check and unique constraints", self.render_constraints()),
            FILE_NOT_NULL: (
                "NOT NULL for required slots (optional; apply after the other files)",
                self.render_not_null(),
            ),
        }
        if not self.enums_as_varchar:
            files[FILE_ENUM_TYPES] = ("Enum types", self.render_enum_types())

        written = []
        for name in ALL_FILES:
            if name not in files:
                continue
            title, body = files[name]
            path = out_dir / name
            path.write_text("\n".join(self.header(title, schema_path) + body))
            written.append(path)
        return written


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Generate PostgreSQL DDL files from a LinkML schema.",
    )
    parser.add_argument(
        "schema",
        type=Path,
        help="path to the LinkML schema YAML, relative to the current directory or absolute",
    )
    parser.add_argument(
        "--enums-as-varchar",
        action="store_true",
        help="store enums as varchar columns with CHECK constraints instead of native enum types",
    )
    parser.add_argument(
        "--out-root",
        type=Path,
        default=DEFAULT_OUT_ROOT,
        help=f"directory to create the <schema-file-stem>/ output folder in (default: {DEFAULT_OUT_ROOT})",
    )
    args = parser.parse_args(argv)

    schema_path = args.schema.resolve()
    if not schema_path.is_file():
        parser.error(f"schema file not found: {args.schema}")

    generator = Generator(SchemaView(str(schema_path)), args.enums_as_varchar)
    generator.build()
    out_dir = args.out_root.resolve() / schema_path.stem
    for path in generator.write(out_dir, schema_path):
        print(path)


if __name__ == "__main__":
    main()

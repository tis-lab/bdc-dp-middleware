"""Compare each database's DDL with its LinkML model and report every mismatch.

Checks, per table or view named by a class's `table` or `view` annotation:
  columns         same names on both sides
  partitioning    PARTITION BY exactly when the class has a `partition` annotation
  primary key     the class's primary_key unique key (or identifier) equals the PRIMARY KEY
  unique keys     every other unique key has a UNIQUE constraint
  nullability     required / identifier / key slots are NOT NULL (primary key columns count);
                  an optional multivalued slot may be NOT NULL with a DEFAULT, empty meaning none
  types           range maps to the column type; multivalued slots are arrays
  enums           an enum-ranged column has a CHECK listing exactly the enum's values, unless the
                  slot is annotated enforced_by (checked by a release check instead)
  patterns        a slot with a pattern has a CHECK using ~
Views are checked for columns only.

Usage: python tests/check_schema_sql.py            (catalog and counting)
       python tests/check_schema_sql.py MODEL DDL
"""
import re
import sys
from pathlib import Path

import pglast
from pglast.stream import RawStream
from linkml_runtime.utils.schemaview import SchemaView
from pglast import ast
from pglast.enums import ConstrType

ROOT = Path(__file__).resolve().parent.parent
PAIRS = [("schema/db/catalog.yaml", "schema/db/catalog.sql"),
         ("schema/db/counting.yaml", "schema/db/counting.sql")]
TYPES = {"string": {"text", "jsonb"}, "uriorcurie": {"text"}, "integer": {"int4", "int8", "integer", "bigint"},
         "boolean": {"bool", "boolean"}, "date": {"date"}, "datetime": {"timestamptz"},
         "decimal": {"numeric"}, "uuid": {"uuid"}}


def ann(element, key):
    annotations = element.annotations
    if not annotations:
        return None
    a = annotations.get(key) if hasattr(annotations, "get") else getattr(annotations, key, None)
    if a is None:
        return None
    return a.value if hasattr(a, "value") else a.get("value") if isinstance(a, dict) else getattr(a, "value", a)


def model(path):
    sv = SchemaView(str(path))
    out = {}
    for name in sv.all_classes(imports=False):
        cls = sv.get_class(name)
        rel = ann(cls, "table") or ann(cls, "view")
        if not rel:
            continue
        slots = {s.name: s for s in sv.class_induced_slots(name)}
        keys = {k: list(u.unique_key_slots) for k, u in (cls.unique_keys or {}).items()}
        pk = keys.pop("primary_key", None) or [n for n, s in slots.items() if s.identifier]
        cols = {}
        for n, s in slots.items():
            rng = s.range or sv.schema.default_range or "string"
            enum = sv.get_enum(rng)
            if rng in sv.all_classes():  # a reference stores the referenced class's identifier
                ident = sv.get_identifier_slot(rng)
                rng = (ident.range if ident and ident.range else "string")
            cols[n] = {
                "required": bool(s.required or s.identifier),
                "multivalued": bool(s.multivalued),
                "enforced_by": ann(s, "enforced_by"),
                "type": "string" if enum else rng,
                "enum": sorted(sv.get_enum(rng).permissible_values) if enum else None,
                "pattern": s.pattern,
            }
        out[rel] = {"cls": name, "view": ann(cls, "view") is not None, "partition": ann(cls, "partition") is not None,
                    "pk": pk, "unique": list(keys.values()), "cols": cols}
    return out


def deparse(node):
    return RawStream()(node)


def ddl(path):
    out = {}
    for raw in pglast.parse_sql(Path(path).read_text()):
        s = raw.stmt
        if isinstance(s, ast.ViewStmt):
            cols = set()
            for t in s.query.targetList or ():
                if t.name:
                    cols.add(t.name)
                elif isinstance(t.val, ast.ColumnRef):
                    last = t.val.fields[-1]
                    if isinstance(last, ast.A_Star):
                        raise ValueError(f"{s.view.relname}: view select list uses *; name the columns")
                    cols.add(last.sval)
            out[f"{s.view.schemaname}.{s.view.relname}"] = {"view": True, "cols": {c: {} for c in cols}}
            continue
        if not isinstance(s, ast.CreateStmt) or s.partbound:
            continue
        cols, pk, unique, checks = {}, [], [], []
        for e in s.tableElts or ():
            if isinstance(e, ast.ColumnDef):
                info = {"type": e.typeName.names[-1].sval, "array": bool(e.typeName.arrayBounds),
                        "notnull": False, "default": False}
                for c in e.constraints or ():
                    if c.contype == ConstrType.CONSTR_NOTNULL:
                        info["notnull"] = True
                    elif c.contype == ConstrType.CONSTR_DEFAULT:
                        info["default"] = True
                    elif c.contype == ConstrType.CONSTR_PRIMARY:
                        pk = [e.colname]
                    elif c.contype == ConstrType.CONSTR_CHECK:
                        checks.append(deparse(c.raw_expr))
                cols[e.colname] = info
            elif isinstance(e, ast.Constraint):
                keys = [k.sval for k in e.keys or ()]
                if e.contype == ConstrType.CONSTR_PRIMARY:
                    pk = keys
                elif e.contype == ConstrType.CONSTR_UNIQUE:
                    unique.append(keys)
                elif e.contype == ConstrType.CONSTR_CHECK:
                    checks.append(deparse(e.raw_expr))
        for c in pk:
            if c in cols:
                cols[c]["notnull"] = True
        out[f"{s.relation.schemaname}.{s.relation.relname}"] = {
            "view": False, "partition": s.partspec is not None, "pk": pk, "unique": unique,
            "checks": checks, "cols": cols}
    return out


def compare(model_path, ddl_path):
    m, d = model(model_path), ddl(ddl_path)
    found = []
    for rel in sorted(set(m) - set(d)):
        found.append(f"{rel}: in the model ({m[rel]['cls']}) but not the DDL")
    for rel in sorted(set(d) - set(m)):
        found.append(f"{rel}: in the DDL but not the model")
    for rel in sorted(set(m) & set(d)):
        mm, dd = m[rel], d[rel]
        for c in sorted(set(mm["cols"]) - set(dd["cols"])):
            found.append(f"{rel}.{c}: in the model but not the DDL")
        for c in sorted(set(dd["cols"]) - set(mm["cols"])):
            found.append(f"{rel}.{c}: in the DDL but not the model")
        if mm["view"] or dd["view"]:
            continue
        if mm["partition"] != dd["partition"]:
            found.append(f"{rel}: model partition={mm['partition']}, DDL partitioned={dd['partition']}")
        if sorted(mm["pk"]) != sorted(dd["pk"]):
            found.append(f"{rel}: primary key model={mm['pk']} DDL={dd['pk']}")
        for u in mm["unique"]:
            if sorted(u) not in [sorted(x) for x in dd["unique"]]:
                found.append(f"{rel}: unique key {u} has no UNIQUE constraint")
        for c in sorted(set(mm["cols"]) & set(dd["cols"])):
            mc, dc = mm["cols"][c], dd["cols"][c]
            where = f"{rel}.{c}"
            if mc["required"] and not dc["notnull"]:
                found.append(f"{where}: required in the model, nullable in the DDL")
            if not mc["required"] and dc["notnull"] and not (mc["multivalued"] and dc["default"]):
                found.append(f"{where}: NOT NULL in the DDL, optional in the model")
            if mc["multivalued"] != dc["array"]:
                found.append(f"{where}: multivalued={mc['multivalued']} but DDL array={dc['array']}")
            expected = TYPES.get(mc["type"])
            if expected and dc["type"] not in expected:
                found.append(f"{where}: range {mc['type']} but DDL type {dc['type']}")
            mentions = [ch for ch in dd["checks"] if re.search(rf"\b{c}\b", ch)]
            if mc["enum"] and not mc["enforced_by"]:
                values = sorted({v for ch in mentions for v in re.findall(r"'([^']*)'", ch)})
                if not mentions:
                    found.append(f"{where}: enum-ranged but no CHECK")
                elif values != mc["enum"]:
                    found.append(f"{where}: CHECK values {values} differ from enum {mc['enum']}")
            if mc["pattern"] and not any("~" in ch for ch in mentions):
                found.append(f"{where}: pattern {mc['pattern']} but no CHECK using ~")
    return found


def main(argv):
    pairs = [tuple(argv[:2])] if argv else [(ROOT / a, ROOT / b) for a, b in PAIRS]
    found = [f"{Path(b).name}: {p}" for a, b in pairs for p in compare(a, b)]
    print("\n".join(found + [f"{len(found)} problem(s)"]))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
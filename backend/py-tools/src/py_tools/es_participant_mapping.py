"""Generate the Elasticsearch index definition for the participant documents.

Builds the mapping from the LinkML schema with the same rules that
``pg-export-participants`` uses to build the documents (it reuses the
exporter's ``Exporter`` for them), so the two can't disagree. Writes
``schema/es/<YYYYMMDD-HHMMSS>__participant.json``, a body for
``PUT /<index>`` with ``settings`` (only the mapping limits the index needs),
``mappings`` and ``mappings._meta``. File names sort by generation time.

Mapping rules:

- ``dynamic: strict`` at the top, so a document with a field the mapping
  doesn't have is rejected. Post-processors declare the fields they add in an
  ``es_properties`` attribute (see participant_postprocess).
- Every array of objects is ``nested``: the member arrays (``condition``,
  ``visit``, ...), nested indirect records (``visit.questionnaire_response``)
  and multivalued inlined objects (``associated_person.cause_of_death``). A
  single inlined object is an ``object``.
- Enum slots, ids, id references (``associated_visit``), uri/curie types,
  ``time`` and ``date_or_datetime`` are ``keyword``. LinkML ``string`` is
  ``keyword`` with an analyzed ``.text`` subfield. ``integer`` is
  ``integer``; ``float``, ``double`` and ``decimal`` are ``double``;
  ``boolean`` is ``boolean``; ``date`` and ``datetime`` are ``date``.
- An object slot inlines its range class's fields, or only its id where the
  exporter writes a stub because the same class is already on the path.
- Polymorphic slots: if a class of the range can belong to a participant
  (``Entity``, ``File``, ``Observation``), only ``_class`` and the id are
  mapped, with ``dynamic: false``: the exporter's other fields stay in
  ``_source`` but aren't indexed. Otherwise (``QuestionnaireResponseValue``)
  the union of the classes' fields is mapped, with type-suffixed keys for
  slots whose range differs (``value_boolean``, ``value_time_point``, ...).
"""

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from linkml_runtime import SchemaView
from linkml_runtime.linkml_model.meta import SlotDefinition

from py_tools import participant_postprocess as post
from py_tools.pg_export_participants import CLASS_KEY, ROOT_CLASS, Exporter
from py_tools.pg_load_data import DEFAULT_SCHEMA, Mapper

PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIR = PROJECT_DIR / "schema" / "es"
INDEX_NAME = "participant"

KEYWORD = {"type": "keyword"}
STRING = {"type": "keyword", "fields": {"text": {"type": "text"}}}

# LinkML built-in types to Elasticsearch field types (following typeof).
ES_TYPES: dict[str, dict] = {
    "string": STRING,
    "integer": {"type": "integer"},
    "boolean": {"type": "boolean"},
    "float": {"type": "double"},
    "double": {"type": "double"},
    "decimal": {"type": "double"},
    "date": {"type": "date"},
    "datetime": {"type": "date"},
    "time": KEYWORD,
    "date_or_datetime": KEYWORD,  # stored as text; not always a valid ES date
    "uriorcurie": KEYWORD,
    "curie": KEYWORD,
    "uri": KEYWORD,
    "ncname": KEYWORD,
    "objectidentifier": KEYWORD,
    "nodeidentifier": KEYWORD,
    "jsonpointer": KEYWORD,
    "jsonpath": KEYWORD,
    "sparqlpath": KEYWORD,
}

# Elasticsearch defaults for the limits the mapping may exceed.
DEFAULT_TOTAL_FIELDS = 1000
DEFAULT_NESTED_FIELDS = 50
DEFAULT_DEPTH = 20


class MappingError(Exception):
    pass


class MappingBuilder:
    def __init__(self, exporter: Exporter):
        self.ex = exporter
        self.m = exporter.m
        self.sv = exporter.sv

    def scalar(self, slot: SlotDefinition) -> dict:
        if slot.any_of or slot.exactly_one_of:
            return dict(KEYWORD)  # the exporter stores union ranges as text
        range_name = slot.range or self.sv.schema.default_range or "string"
        if range_name in self.sv.all_enums():
            return dict(KEYWORD)
        for ancestor in self.sv.type_ancestors(range_name):
            if ancestor in ES_TYPES:
                return json.loads(json.dumps(ES_TYPES[ancestor]))  # a copy
        return dict(KEYWORD)

    def id_name(self, class_name: str) -> str:
        return self.ex.gen.identifier_slot(class_name).name

    def class_properties(self, class_name: str, path: frozenset[str], nested: bool, chain: frozenset[str]) -> dict:
        """Properties of an object of a class, as the exporter builds it.

        ``path``: tables inlined on the way here (a class on it becomes a stub).
        ``nested``: whether indirect records are nested in it (records with a
        home: members and nested records, not inlined objects).
        ``chain``: tables of the indirect-record nesting so far (ends cycles).
        """
        props: dict[str, Any] = {}
        for slot in self.ex.slots[class_name]:
            props[slot.name] = self.slot_mapping(slot, path)
        if nested:
            table = self.m.class_table[class_name].name
            for referrer, child_table in self.indirect_tables(table):
                if child_table in chain:
                    continue
                key = self.ex.array_key(class_name, child_table)
                props[key] = {
                    "type": "nested",
                    "properties": self.class_properties(referrer, frozenset({child_table}), True, chain | {child_table}),
                }
        return props

    def indirect_tables(self, table: str) -> list[tuple[str, str]]:
        """(class, table) of records the exporter can nest under a record of ``table``."""
        found = []
        for class_name, t in self.m.class_table.items():
            if any(self.ex.is_class_range(s) and self.ex.nestable(t.name, s.name, table) for s in self.ex.slots[class_name]):
                found.append((class_name, t.name))
        return sorted(found, key=lambda ct: self.ex.table_order(ct[1]))

    def slot_mapping(self, slot: SlotDefinition, path: frozenset[str]) -> dict:
        if not self.ex.is_class_range(slot):
            return self.scalar(slot)
        if self.ex.id_slot(slot):
            return dict(KEYWORD)
        container = "nested" if slot.multivalued else "object"
        tables = self.ex.tables_for(slot.range)
        if self.ex.union_slot(slot):
            props: dict[str, Any] = {CLASS_KEY: dict(KEYWORD)}
            for table in tables:
                class_name = self.m.class_of_table[table]
                sub = {self.id_name(class_name): dict(KEYWORD)} if table in path else self.class_properties(
                    class_name, path | {table}, False, frozenset()
                )
                for key, mapping in sub.items():
                    key = self.ex.union_key(slot.range, class_name, key)
                    if key in props and props[key] != mapping:
                        raise MappingError(
                            f"{slot.name} ({slot.range}): field {key} is mapped differently in {class_name}"
                        )
                    props[key] = mapping
            return {"type": container, "properties": props}
        if self.ex.is_polymorphic(slot):
            ids = {self.id_name(self.m.class_of_table[t]) for t in tables}
            return {
                "type": container,
                # The exporter may inline whole objects here; only these are indexed.
                "dynamic": False,
                "properties": {CLASS_KEY: dict(KEYWORD), **{name: dict(KEYWORD) for name in sorted(ids)}},
            }
        table = tables[0]
        class_name = self.m.class_of_table[table]
        if table in path:
            props = {self.id_name(class_name): dict(KEYWORD)}
        else:
            props = self.class_properties(class_name, path | {table}, False, frozenset())
        return {"type": container, "properties": props}

    def mappings(self, processors: list) -> dict:
        root_table = self.ex.root_table
        props = self.class_properties(ROOT_CLASS, frozenset({root_table}), True, frozenset({root_table}))
        for class_name in sorted(self.ex.member_classes, key=lambda c: self.ex.table_order(self.m.class_table[c].name)):
            table = self.m.class_table[class_name].name
            props[self.ex.array_key(ROOT_CLASS, table)] = {
                "type": "nested",
                "properties": self.class_properties(class_name, frozenset({table}), True, frozenset({table})),
            }
        for processor in processors:
            for key, mapping in (getattr(processor, "es_properties", None) or {}).items():
                if key in props:
                    raise MappingError(f"post-processor {post.name_of(processor)} adds {key}, which is already mapped")
                props[key] = mapping
        return {"dynamic": "strict", "properties": props}


def measure(props: dict, depth: int = 1) -> tuple[int, int, int]:
    """(fields as ES counts them, nested fields, depth) of a properties dict."""
    fields = nested = 0
    max_depth = depth
    for mapping in props.values():
        fields += 1 + len(mapping.get("fields", {}))
        if mapping.get("type") == "nested":
            nested += 1
        if "properties" in mapping:
            f, n, d = measure(mapping["properties"], depth + 1)
            fields, nested, max_depth = fields + f, nested + n, max(max_depth, d)
    return fields, nested, max_depth


def with_headroom(needed: int, default: int) -> int:
    """A limit for ``needed``, with room for post-processor fields and schema growth."""
    return max(default, -(-int(needed * 1.25) // 50) * 50)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Generate the Elasticsearch index definition (settings + mappings) for the "
            "documents pg-export-participants writes, from the LinkML schema."
        ),
    )
    parser.add_argument(
        "--linkml-schema",
        type=Path,
        default=DEFAULT_SCHEMA,
        help=f"LinkML schema (default: {DEFAULT_SCHEMA})",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_OUT_DIR,
        help=f"folder for <YYYYMMDD-HHMMSS>__{INDEX_NAME}.json (default: {DEFAULT_OUT_DIR})",
    )
    parser.add_argument(
        "--post-processor",
        action="append",
        default=[],
        metavar="MODULE:NAME",
        help="extra post-processor whose es_properties to include; give the same ones as to the export",
    )
    args = parser.parse_args(argv)

    if not args.linkml_schema.is_file():
        sys.exit(f"es-participant-mapping: LinkML schema not found: {args.linkml_schema}")
    try:
        processors = [post.instantiate(p) for p in post.POST_PROCESSORS] + [post.load(s) for s in args.post_processor]
    except (ImportError, AttributeError, ValueError) as e:
        sys.exit(f"es-participant-mapping: {e}")

    sv = SchemaView(str(args.linkml_schema.resolve()))
    mapper = Mapper(sv)
    exporter = Exporter(mapper, {t.name: {} for t in mapper.gen.tables if t.class_name}, {})
    try:
        mappings = MappingBuilder(exporter).mappings(processors)
    except MappingError as e:
        sys.exit(f"es-participant-mapping: {e}")

    fields, nested, depth = measure(mappings["properties"])
    generated = datetime.now()
    mappings = {
        "_meta": {
            "generated_by": "es-participant-mapping (py-tools)",
            "generated": generated.isoformat(timespec="seconds"),
            "linkml_schema": {
                "name": sv.schema.name,
                "version": sv.schema.version,
                "file": args.linkml_schema.name,
            },
            "post_processors": [post.name_of(p) for p in processors],
            "fields": fields,
            "nested_fields": nested,
            "depth": depth,
        },
        **mappings,
    }
    settings = {
        "index.mapping.total_fields.limit": with_headroom(fields, DEFAULT_TOTAL_FIELDS),
        "index.mapping.nested_fields.limit": with_headroom(nested, DEFAULT_NESTED_FIELDS),
    }
    if depth > DEFAULT_DEPTH:
        settings["index.mapping.depth.limit"] = with_headroom(depth, DEFAULT_DEPTH)

    out_dir = args.out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{generated:%Y%m%d-%H%M%S}__{INDEX_NAME}.json"
    if path.exists():  # a suffix would break the time order of the names
        sys.exit(f"es-participant-mapping: {path} already exists; run again in a second")
    path.write_text(json.dumps({"settings": settings, "mappings": mappings}, indent=2) + "\n", encoding="utf-8")
    print(path)
    print(f"  {fields} fields, {nested} nested fields, depth {depth}")


if __name__ == "__main__":
    main()

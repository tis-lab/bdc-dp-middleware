"""Validate full summary mapper outputs against the pinned LinkML response contract."""
import json
from pathlib import Path
import sys
import yaml
from jsonschema import validators

ROOT = Path(__file__).resolve().parents[1]

def validator(schema_path, target):
    from linkml.generators.jsonschemagen import JsonSchemaGenerator
    from linkml_runtime import SchemaView
    view = SchemaView(str(schema_path))
    if target not in view.all_classes():
        raise ValueError(f"Unknown schema target class: {target}")
    generated = json.loads(JsonSchemaGenerator(str(schema_path), top_class=target, not_closed=False).serialize())
    # LinkML 1.11.1 omits enum restrictions for inherited/include enums.
    # Resolve only finite declarations from the pinned model; ontology-backed enums stay open.
    def finite_values(expression, seen=frozenset()):
        if any(getattr(expression, key, None) for key in
               ("reachable_from", "matches", "concepts", "minus", "pv_formula")):
            return None
        values = set(expression.permissible_values or {})
        for parent in getattr(expression, "inherits", []) or []:
            if parent in seen:
                raise ValueError("Cyclic enum inheritance: " + parent)
            inherited = finite_values(view.get_enum(parent), seen | {parent})
            if inherited is None: return None
            values.update(inherited)
        for included in getattr(expression, "include", []) or []:
            extra = finite_values(included, seen)
            if extra is None: return None
            values.update(extra)
        return values or None
    for name, definition in view.all_enums().items():
        if definition.inherits or definition.include:
            values = finite_values(definition, frozenset({name}))
            if values is not None and name in generated.get("$defs", {}):
                generated["$defs"][name]["enum"] = sorted(values)
    selected = validators.validator_for(generated)
    selected.check_schema(generated)
    return selected(generated)

def validate_file(check, path):
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream) if path.suffix == ".json" else yaml.safe_load(stream)
    errors = sorted(check.iter_errors(value), key=lambda e: str(list(e.absolute_path)))
    if errors:
        for e in errors[:20]:
            print(f"{path}: {list(e.absolute_path)}: {e.message}", file=sys.stderr)
        raise ValueError(f"{path}: schema validation failed")
    print(f"PASS {path}")
    return value

if __name__ == "__main__":
    # Preserve the established command while using the flat-file evidence set.
    from validate_summary_examples import main
    main()

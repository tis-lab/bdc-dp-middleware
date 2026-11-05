"""Validate full summary mapper outputs against the pinned LinkML response contract."""
import argparse
import json
from pathlib import Path
import sys
import yaml
import hashlib
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

REQUIRED_OUTPUTS = {"page-load.json", "filtered.json", "suppressed.json", "empty.json"}

def require_outputs(directory):
    outputs = sorted(directory.glob("*.json"))
    missing = REQUIRED_OUTPUTS - {p.name for p in outputs}
    if missing:
        raise ValueError("Missing actual mapper outputs; run clean Maven tests first: " + ", ".join(sorted(missing)))
    return outputs

def summary(require_generated=False):
    pins = json.loads((ROOT / "schema/summary-validation-pins.json").read_text())
    for name, digest in pins.items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise ValueError("Pinned schema changed; review and update provenance and digest: " + name)
    original = (ROOT / "schema/upstream/summary.yaml").read_text()
    before, rest = original.split("\n  seed:\n", 1)
    seed, after = rest.split("\n  filter:\n", 1)
    expected = before + "\n  seed:\n" + seed.replace("required: true", "required: false") + "\n  filter:\n" + after
    if (ROOT / "schema/summary.yaml").read_text() != expected:
        raise ValueError("Active summary.yaml may differ from upstream only in seed.required")
    check = validator(ROOT / "schema/summary.yaml", "SummaryResponse")
    schema_output = ROOT / "target/summary-schema"
    schema_output.mkdir(parents=True, exist_ok=True)
    (schema_output / "summary.schema.json").write_text(json.dumps(check.schema, indent=2))
    fixtures = sorted((ROOT / "src/test/resources/summary/contracts").glob("*.json"))
    if not fixtures:
        raise ValueError("Missing summary contract fixtures")
    for fixture in fixtures:
        valid = validate_file(check, fixture)
        for bad in [dict(valid, unexpected_field=1), dict(valid, min_count=0), {k:v for k,v in valid.items() if k != "release"}]:
            if check.is_valid(bad):
                raise ValueError("Validator accepted an intentionally invalid summary")
    generated = sorted((ROOT / "target/summary-contract").glob("*.json"))
    if require_generated:
        generated = require_outputs(ROOT / "target/summary-contract")
    for output in generated:
        validate_file(check, output)
    print("PASS summary response contract")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-generated", action="store_true")
    args = parser.parse_args()
    try:
        summary(args.require_generated)
    except (ValueError, OSError, KeyError) as error:
        sys.exit(str(error))

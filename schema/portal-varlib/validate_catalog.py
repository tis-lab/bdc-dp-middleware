"""Validate the upstream-inherited portal catalog; never invent variable identities."""
import argparse
import json
import re
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator
from linkml.generators.jsonschemagen import JsonSchemaGenerator

HERE = Path(__file__).resolve().parent
IDENTIFIER = re.compile(r'^[A-Za-z][A-Za-z0-9._-]*:[^\s]+$')


def reachable_schema(schema):
    """
    Retain every generated definition reachable from the root, including inherited slots.

    Unrelated upstream clinical records contain malformed regexes. They are outside this
    metadata contract and are not validated; imported LinkML definitions remain unchanged.
    """
    definitions = schema.get('$defs', {})
    def references(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key == '$ref':
                    if not child.startswith('#/$defs/'):
                        raise ValueError(f'Unexpected generated reference: {child}')
                    yield child.removeprefix('#/$defs/').replace('~1', '/').replace('~0', '~')
                else:
                    yield from references(child)
        elif isinstance(value, list):
            for child in value:
                yield from references(child)
    result = {key: value for key, value in schema.items() if key != '$defs'}
    pending = list(references(result))
    retained = {}
    while pending:
        name = pending.pop()
        if name not in retained:
            retained[name] = definitions[name]  # Fail closed on dangling definitions.
            pending.extend(references(definitions[name]))
    result['$defs'] = retained
    return result


@lru_cache(maxsize=1)
def validator():
    """Compile the actual upstream-inherited LinkML model to closed JSON Schema."""
    schema = json.loads(JsonSchemaGenerator(
        str(HERE / 'portal-varlib.yaml'), top_class='PortalVariableCatalog',
        not_closed=False,
    ).serialize())
    schema = reachable_schema(schema)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def validate_catalog(data, allow_synthetic=False):
    """Return shape and reference errors; fixture opt-in is not a production approval."""
    errors = [f'{list(e.absolute_path)}: {e.message}' for e in validator().iter_errors(data)]
    if errors:
        return errors
    seen = set()
    indexes = {}
    for group, key in [('integrated_variables', 'id'), ('compound_variables', 'id'),
                       ('studies', 'id'), ('sources', 'source_id'), ('concepts', 'curie'),
                       ('single_continuous_variables', 'id'), ('single_categorical_variables', 'id')]:
        indexes[group] = {}
        for row in data.get(group, []):
            identifier = row[key]
            if not IDENTIFIER.fullmatch(identifier):
                errors.append(f'{group}: expected URI/CURIE identifier: {identifier}')
            if identifier in seen:
                errors.append(f'Duplicate identity across catalog: {identifier}')
            seen.add(identifier)
            indexes[group][identifier] = row
            if not allow_synthetic and identifier.startswith('example:'):
                errors.append(f'Synthetic identifier not publishable: {identifier}')
    for row in data['sources']:
        if not allow_synthetic and (row['revision'] == '0' * 40 or row['repository'].startswith('example/')):
            errors.append('Synthetic provenance is not publishable')
    if not allow_synthetic and data['release'].startswith('SYNTHETIC'):
        errors.append('Synthetic release is not publishable')
    for group in ['integrated_variables', 'compound_variables']:
        for row in data.get(group, []):
            if row['concept_type'] not in indexes['concepts']:
                errors.append(f'{row["id"]}: concept_type does not resolve')
            study = row.get('associated_study')
            if study is not None and study not in indexes['studies']:
                errors.append(f'{row["id"]}: associated_study does not resolve')
            references = row['metadata_sources']
            if not references or len(references) != len(set(references)):
                errors.append(f'{row["id"]}: metadata_sources must be nonempty and unique')
            if set(references) - indexes['sources'].keys():
                errors.append(f'{row["id"]}: metadata_sources do not resolve')
    source_variables = {**indexes['single_continuous_variables'], **indexes['single_categorical_variables']}
    for row in source_variables.values():
        if row['associated_study'] not in indexes['studies']:
            errors.append(f'{row["id"]}: source study does not resolve')
        if not row['metadata_sources'] or set(row['metadata_sources']) - indexes['sources'].keys():
            errors.append(f'{row["id"]}: source provenance does not resolve')
    for compound in data['compound_variables']:
        for metadata in compound.get('row_metadata', []):
            source = source_variables.get(metadata['id'])
            if source is None:
                errors.append(f'{compound["id"]}: row_metadata must resolve to a source variable')
            elif source['associated_study'] != compound['associated_study']:
                errors.append(f'{compound["id"]}: source-variable study differs')
    for row in data['integrated_variables']:
        references = row['integrates']
        if not references or len(references) != len(set(references)):
            errors.append(f'{row["id"]}: integrates must be nonempty and unique')
        if set(references) - indexes['compound_variables'].keys():
            errors.append(f'{row["id"]}: integrates must resolve to compound variables')
    return errors


def main():
    """Validate catalog structure, references and publication eligibility."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--allow-synthetic', action='store_true', help='Only for explicit test/review fixtures')
    args = parser.parse_args()
    data = json.loads(args.input.read_text(encoding='utf-8'))
    errors = validate_catalog(data, allow_synthetic=args.allow_synthetic)
    if errors:
        for error in errors:
            print(error)
        return 1
    print(f'PASS: {len(data["integrated_variables"])} integrated variables; schema and references validated')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

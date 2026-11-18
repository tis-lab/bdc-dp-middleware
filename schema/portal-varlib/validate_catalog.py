"""Validate a portal catalog with LinkML and check its cross-list references."""
import argparse
import json
import re
from functools import lru_cache
from pathlib import Path

from linkml.validator import validate
from linkml_runtime import SchemaView

HERE = Path(__file__).resolve().parent
IDENTIFIER = re.compile(r'^[A-Za-z][A-Za-z0-9._-]*:[^\s]+$')


@lru_cache(maxsize=1)
def catalog_schema():
    """Resolve local imports once for the standard LinkML validator."""
    view = SchemaView(str(HERE / 'portal-varlib.yaml'))
    view.merge_imports()
    return view.schema


def validate_catalog(data, allow_synthetic=False):
    """Return shape and reference errors; fixture opt-in is not a production approval."""
    report = validate(data, catalog_schema(), 'PortalVariableCatalog')
    errors = [result.message for result in report.results]
    if errors:
        return errors
    seen = set()
    indexes = {}
    for group, key in [('integrated_variables', 'id'), ('compound_variables', 'id'),
                       ('studies', 'id'), ('sources', 'source_id'), ('concepts', 'curie'),
                       ('single_continuous_variables', 'id'), ('single_categorical_variables', 'id')]:
        indexes[group] = {}
        for row in data.get(group, []):
            identifier = row.get(key)
            if not isinstance(identifier, str) or not identifier:
                errors.append(f'{group}: missing {key}')
                continue
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
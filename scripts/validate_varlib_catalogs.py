"""Validate the exact producer catalogs packaged in the middleware; no generation or network."""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / 'schema/portal-varlib'
DEFAULT = ROOT / 'src/main/resources/harmonized/catalogs/manifest.json'


def read_json(path):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            if k in result:
                raise ValueError(f'{path}: duplicate JSON key {k}')
            result[k] = v
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)


def validate_collection(manifest_path=DEFAULT):
    spec = importlib.util.spec_from_file_location('portal_validator', CONTRACT / 'validate_catalog.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    manifest = read_json(manifest_path)
    if manifest.get('schema_version') != 3 or not manifest.get('release') or not manifest.get('catalogs'):
        raise ValueError('Expected nonempty version-3 catalog manifest')
    seen, files, prefixes, concepts, variables = {}, set(), set(), set(), set()
    for entry in manifest['catalogs']:
        name = entry['file']
        if Path(name).name != name or not name.endswith('.json') or name in files:
            raise ValueError(f'Invalid/duplicate catalog filename: {name}')
        files.add(name)
        path = manifest_path.parent / name
        data = read_json(path)
        errors = module.validate_catalog(data)
        if errors:
            raise ValueError(f'{name}: ' + '\n'.join(errors))
        if data['release'] != manifest['release'] or not data['integrated_variables']:
            raise ValueError(f'{name}: empty catalog or mixed release')
        prefix = entry['prefix']
        if prefix in prefixes or {x['curie'].split(':')[0] for x in data['concepts']} != {prefix}:
            raise ValueError(f'{name}: incorrect/duplicate prefix partition')
        prefixes.add(prefix)
        compounds = {r['id']: r for r in data['compound_variables']}
        for iv in data['integrated_variables']:
            if iv['bdchm_type'] not in {'condition', 'observation', 'procedure', 'drugexposure'}:
                raise ValueError(f'Catalog class needs an explicit middleware mapping: {iv["bdchm_type"]}')
            classes = {'condition': ['Condition'], 'observation': ['Observation', 'MeasurementObservation', 'SdohObservation'],
                       'procedure': ['Procedure'], 'drugexposure': ['DrugExposure']}
            if iv['id'] not in {f'bdchm:{cls}/{iv["concept_type"]}' for cls in classes[iv['bdchm_type']]}:
                raise ValueError(f'Unsupported producer ID/class convention: {iv["id"]}')
            if iv['id'] in variables:
                raise ValueError(f'Duplicate integrated variable: {iv["id"]}')
            variables.add(iv['id'])
            for ref in iv['integrates']:
                cv = compounds[ref]
                if (iv['concept_type'], iv['bdchm_type']) != (cv['concept_type'], cv['bdchm_type']):
                    raise ValueError(f'{iv["id"]}: integrated/compound concept or class differs')
        for group, key in [('studies', 'id'), ('sources', 'source_id'), ('concepts', 'curie'),
                           ('single_continuous_variables', 'id'), ('single_categorical_variables', 'id')]:
            for row in data.get(group, []):
                # Producer usage descriptions and provenance are local to each catalog. Do not
                # merge those fields or treat the first occurrence as the universal definition.
                canonical = {k: v for k, v in row.items()
                             if not (group.startswith('single_') and k in ('metadata_sources', 'variable_description'))}
                identity = row[key]
                record = (group, canonical)
                if identity in seen and seen[identity] != record:
                    raise ValueError(f'Conflicting shared definition: {identity}')
                seen[identity] = record
        concepts.update(x['curie'] for x in data['concepts'])
    actual = {p.name for p in manifest_path.parent.glob('varlib_catalog_*.json')}
    if actual != files:
        raise ValueError(f'Manifest/catalog file mismatch: {actual ^ files}')
    return {'release': manifest['release'], 'catalogs': len(files),
            'concepts': len(concepts), 'integrated_variables': len(variables)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=DEFAULT)
    args = parser.parse_args()
    try:
        print('PASS:', json.dumps(validate_collection(args.manifest)))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f'FAIL: {error}\n')

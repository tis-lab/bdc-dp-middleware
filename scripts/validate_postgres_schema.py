"""Validate actual PostgreSQL responses against the existing pinned summary contract."""
from pathlib import Path
from validate_summary_schema import ROOT, validator, validate_file

required = {'page-load', 'single-term', 'and', 'or', 'mixed', 'focused', 'empty', 'suppressed'}
directory = ROOT / 'target/postgres-contract'
missing = required - {p.stem for p in directory.glob('*.json')}
if missing:
    raise SystemExit('Missing PostgreSQL integration outputs: ' + ', '.join(sorted(missing)))
check = validator(ROOT / 'schema/summary.yaml', 'SummaryResponse')
for name in sorted(required):
    validate_file(check, directory / (name + '.json'))

# Only the schema response model may be shared with the Elasticsearch summary package.
source = ROOT / 'src/main/java/gov/nih/nhlbi/bdc/dataportal/cohortbuilder/postgres'
for path in source.glob('*.java'):
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('import ') and 'cohortbuilder.' in line:
            if '.search.' in line or ('.summary.' in line and '.SummaryModels.' not in line):
                raise SystemExit(f'Unexpected backend coupling: {path.name}: {line}')
print('PASS PostgreSQL response contract and backend isolation')

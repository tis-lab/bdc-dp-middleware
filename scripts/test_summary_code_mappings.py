"""Ensure Java normalization meanings agree exactly with the pinned LinkML model."""
import sys
from pathlib import Path
import yaml
root = Path(__file__).resolve().parents[1]
model = yaml.safe_load((root / 'synthetic/bdchm.yaml').read_text())
fields = {'condition.relationship_to_participant': 'FamilyRelationshipEnum', 'demography.sex': 'SexEnum', 'demography.race': 'RaceEnum', 'demography.ethnicity': 'EthnicityEnum'}
expected = {(field, label, value['meaning']) for field, name in fields.items()
            for label, value in model['enums'][name]['permissible_values'].items() if value and value.get('meaning')}
actual = {tuple(line.split('\t')) for line in Path(sys.argv[1]).read_text().splitlines()}
assert actual == expected, {'missing': expected-actual, 'unexpected': actual-expected}
print('PASS: all Java code aliases match the pinned schema meanings')

import unittest
from validate_summary_schema import ROOT, validator, require_outputs
import tempfile
from pathlib import Path
import json
import copy

class SchemaValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.summary = validator(ROOT / 'schema/summary.yaml', 'SummaryResponse')
    def test_summary_required_values_and_unknown_fields(self):
        valid = {'release': 'test-release', 'min_count': 1, 'studies': []}
        self.assertTrue(self.summary.is_valid(valid))
        self.assertFalse(self.summary.is_valid(dict(valid, totalHits=42)))
        self.assertFalse(self.summary.is_valid(dict(valid, min_count=0)))
        self.assertFalse(self.summary.is_valid({'min_count': 1}))
    def test_filter_extensions_are_not_in_the_active_contract(self):
        value = {'release': 'test-release', 'min_count': 1, 'filter': []}
        self.assertTrue(self.summary.is_valid(value))
        self.assertFalse(self.summary.is_valid(dict(value, filter_expression={'operator': 'OR'})))
        for removed in ['SummaryCriterion', 'SummaryFilter', 'SelectionStatusEnum', 'SummaryOperatorEnum']:
            self.assertNotIn(removed, self.summary.schema.get('$defs', {}))
    def test_unknown_target_class_fails(self):
        with self.assertRaises(ValueError):
            validator(ROOT / 'schema/summary.yaml', 'MadeUpRoot')

    def test_nested_invalid_fields_and_enums(self):
        valid = json.loads((ROOT / 'src/test/resources/summary/contracts/page-load.json').read_text())
        self.assertTrue(self.summary.is_valid(valid))
        for location, key, value in [
            (('studies', 0), 'unexpected_field', True),
            (('studies', 0), 'participant_count', 'not-a-number'),
            (('studies', 0, 'conditions', 0, 'values', 0), 'condition_status', 'INVALID_STATUS'),
        ]:
            bad = copy.deepcopy(valid)
            node = bad
            for part in location: node = node[part]
            node[key] = value
            self.assertFalse(self.summary.is_valid(bad), (location, key))

    def test_inherited_status_values_are_accepted(self):
        valid = json.loads((ROOT / 'src/test/resources/summary/contracts/page-load.json').read_text())
        for status in ['PRESENT', 'ABSENT', 'UNKNOWN', 'HISTORICAL']:
            valid['studies'][0]['conditions'][0]['values'][0]['condition_status'] = status
            self.assertTrue(self.summary.is_valid(valid), status)

    def test_generated_outputs_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError): require_outputs(root)
            (root / 'page-load.json').write_text('{}')
            with self.assertRaises(ValueError): require_outputs(root)

if __name__ == '__main__':
    unittest.main()

"""Independent SDL/operation check; does not replace the Spring GraphQL startup test."""
from pathlib import Path
import unittest
from graphql import build_schema, parse, validate, validate_schema

ROOT = Path(__file__).resolve().parents[1]

class GraphQLContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory = ROOT / 'src/main/resources/graphql/core'
        cls.schema = build_schema('\n'.join(p.read_text() for p in sorted(directory.glob('*.graphqls'))))
    def test_schema_is_valid(self):
        self.assertEqual(validate_schema(self.schema), [])
    def test_optional_focus_and_legacy_seed(self):
        query = 'query($focus: [ID!], $filter: SummaryFilterInput) { summary(focus: $focus, filter: $filter) { focus seed studies { participant_count } } }'
        self.assertEqual(validate(self.schema, parse(query)), [])
        self.assertIsNotNone(self.schema.get_type('SummaryResponse').fields['seed'].deprecation_reason)
        self.assertTrue(validate(self.schema, parse('{ summary(focus: [null]) { focus } }')))
    def test_page_load_without_arguments(self):
        query = '{ summary { release seed filter min_count studies { research_study participant_count } } }'
        self.assertEqual(validate(self.schema, parse(query)), [])
        self.assertIn('filter', self.schema.query_type.fields['summary'].args)
    def test_boolean_request_is_valid_without_response_extension(self):
        self.assertIsNotNone(self.schema.get_type('SummaryFilterInput'))
        self.assertNotIn('filter_expression', self.schema.get_type('SummaryResponse').fields)
        query = 'query($a: ID!, $b: ID!) { summary(filter: {and: [{summaryId: $a}, {or: [{summaryId: $b}]}]}) { filter studies { participant_count } } }'
        self.assertEqual(validate(self.schema, parse(query)), [])
        self.assertEqual(validate(self.schema, parse('{ summary(filter: null) { release } }')), [])
        self.assertTrue(validate(self.schema, parse('{ summary(filter: {field: "arbitrary"}) { release } }')))

    def test_json_operation_removed_but_diagnostics_remain(self):
        self.assertNotIn('summaryJson', self.schema.query_type.fields)
        self.assertIsNone(self.schema.get_type('JSON'))
        self.assertTrue(validate(self.schema, parse('{ summaryJson }')))
        for name in ['summary', 'searchProbe', 'searchConditions']:
            self.assertIn(name, self.schema.query_type.fields)

    def test_future_operators_are_not_advertised(self):
        for field in ['measurement', 'temporal', 'not', 'stages']:
            query = '{ summary(filter: {and: [{'+field+': {}}]}) { release } }'
            self.assertTrue(validate(self.schema, parse(query)))

    def test_concept_selection_and_status_inputs(self):
        for condition in ['concept: "HP:0000822", selection: INCLUDE',
                          'concept: "MONDO:0005015", status: ABSENT',
                          'concept: "HP:0000822", selection: EXCLUDE, relationship: NATURAL_MOTHER']:
            self.assertEqual(validate(self.schema, parse('{ summary(filter: {condition: {'+condition+'}}) { release } }')), [])
        self.assertTrue(validate(self.schema, parse('{ summary(filter: {condition: {concept: "HP:0000822", selection: OTHER}}) { release } }')))
        self.assertEqual(self.schema.get_type('SummaryConditionInput').fields['relationship'].default_value, 'ONESELF')

if __name__ == '__main__':
    unittest.main()

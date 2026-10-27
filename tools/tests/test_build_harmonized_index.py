"""Semantic regression fixtures independent of the evolving RTI corpus."""
import importlib.util
import tempfile
import unittest
from pathlib import Path
import yaml

spec = importlib.util.spec_from_file_location('builder', Path(__file__).parents[1] / 'build_harmonized_index.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

class BuilderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)

    def write(self, study, filename, cls, slot, code):
        p = self.repo / 'priority_variables_transform' / (study + '-ingest') / (filename + '.yaml')
        p.parent.mkdir(parents=True, exist_ok=True)
        existing = yaml.safe_load(p.read_text()) if p.exists() else []
        existing.append({'class_derivations': {cls: {'slot_derivations': {slot: {'value': code}}}}})
        p.write_text(yaml.safe_dump(existing))

    def test_labels_stay_on_aliases_and_multiple_concepts_survive(self):
        for code in ['MONDO:1', 'MONDO:2']:
            self.write('A', 'shared_name', 'Condition', 'condition_concept', code)
        qa = self.repo / 'labels' / 'variables.tsv'
        qa.parent.mkdir(parents=True)
        qa.write_text('var_name\tvar_label\nshared_name\tGeneric variable label\n')
        rows = builder.build(self.repo, variable_labels=qa)['concepts']
        self.assertEqual(len(rows), 2)
        for c in rows:
            self.assertIsNone(c['conceptLabel'])
            self.assertEqual(c['variables'][0]['variableLabel'], 'Generic variable label')

    def test_preserves_classes_and_associations_without_majority_vote(self):
        self.write('A', 'one', 'MeasurementObservation', 'observation_type', 'OBA:1')
        self.write('B', 'two', 'Observation', 'observation_type', 'OBA:1')
        c = builder.build(self.repo)['concepts'][0]
        self.assertEqual(c['bdchmClasses'], ['MeasurementObservation', 'Observation'])
        self.assertEqual([(m['study'], m['bdchmClass']) for m in c['sourceMappings']],
                         [('A', 'MeasurementObservation'), ('B', 'Observation')])
        self.assertEqual(c['bdchmType'], 'observation')

    def test_conflicting_coarse_categories_are_not_guessed(self):
        self.write('A', 'one', 'Condition', 'condition_concept', 'EXAMPLE:1')
        self.write('B', 'two', 'Observation', 'observation_type', 'EXAMPLE:1')
        self.assertIsNone(builder.build(self.repo)['concepts'][0]['bdchmType'])

    def test_study_attribution_is_concept_specific(self):
        self.write('A', 'same', 'Condition', 'condition_concept', 'MONDO:1')
        self.write('B', 'same', 'Condition', 'condition_concept', 'MONDO:2')
        rows = builder.build(self.repo)['concepts']
        self.assertEqual([r['studies'] for r in rows], [['A'], ['B']])
        self.assertEqual([r['studyCount'] for r in rows], [1, 1])

    def test_prefix_normalization_preserves_local_identifier_case(self):
        for code in ['oba:AbC', 'OBA:AbC', 'OBA:abc']:
            self.write('A', 'same', 'MeasurementObservation', 'observation_type', code)
        rows = builder.build(self.repo)['concepts']
        self.assertEqual(len(rows), 2)
        self.assertEqual({r['conceptType'].split(':')[1] for r in rows}, {'AbC', 'abc'})

    def test_unknown_class_is_preserved_and_warned(self):
        self.write('A', 'one', 'NewClass', 'observation_type', 'EXAMPLE:1')
        index = builder.build(self.repo)
        self.assertEqual(index['concepts'][0]['bdchmClasses'], ['NewClass'])
        self.assertIsNone(index['concepts'][0]['bdchmType'])
        self.assertTrue(any('NewClass' in w for w in index['warnings']))

    def test_does_not_extract_status_as_a_concept(self):
        self.write('A', 'one', 'Condition', 'condition_status', 'MONDO:1')
        self.assertEqual(builder.build(self.repo)['concepts'], [])

    def test_untracked_legacy_label_table_is_not_implicitly_loaded(self):
        self.write('A', 'asthma', 'Condition', 'condition_concept', 'HP:1')
        legacy = self.repo / 'transform_assessment/harmonized_qaqc/harmonized_vars.tsv'
        legacy.parent.mkdir(parents=True)
        legacy.write_text('var_name\tvar_label\nasthma\tAsthma\n')
        index = builder.build(self.repo)
        self.assertIsNone(index['source']['variableLabels'])
        self.assertIsNone(index['concepts'][0]['variables'][0]['variableLabel'])
        self.assertIsNone(index['concepts'][0]['conceptLabel'])

    def test_explicit_missing_label_input_fails(self):
        self.write('A', 'one', 'Condition', 'condition_concept', 'MONDO:1')
        with self.assertRaisesRegex(ValueError, 'does not exist'):
            builder.build(self.repo, self.repo / 'missing.tsv')

    def test_fixed_generation_date_is_reproducible(self):
        self.write('A', 'one', 'Condition', 'condition_concept', 'MONDO:1')
        first = builder.build(self.repo, generated='2026-10-26')
        second = builder.build(self.repo, generated='2026-10-26')
        self.assertEqual(first, second)
        self.assertEqual(first['generated'], '2026-10-26')

if __name__ == '__main__':
    unittest.main()

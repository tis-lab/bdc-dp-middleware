"""Negative checks for the packaged collection, beyond individual LinkML files."""
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from validate_varlib_catalogs import DEFAULT, validate_collection


class CatalogValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(DEFAULT.parent, self.root, dirs_exist_ok=True)
        self.manifest_path = self.root / 'manifest.json'
        self.manifest = json.loads(self.manifest_path.read_text())

    def save(self):
        self.manifest_path.write_text(json.dumps(self.manifest))

    def edit_catalog(self, index, edit):
        entry = self.manifest['catalogs'][index]
        path = self.root / entry['file']
        data = json.loads(path.read_text())
        edit(data)
        path.write_text(json.dumps(data))
        self.save()

    def test_empty_manifest_fails(self):
        self.manifest['catalogs'] = []
        self.save()
        with self.assertRaisesRegex(ValueError, 'nonempty'):
            validate_collection(self.manifest_path)

    def test_invalid_catalog_fails(self):
        entry = self.manifest['catalogs'][0]
        (self.root / entry['file']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'required property'):
            validate_collection(self.manifest_path)

    def test_unresolved_reference_fails(self):
        self.edit_catalog(0, lambda d: d['integrated_variables'][0]['integrates'].append('hv:missing'))
        with self.assertRaisesRegex(ValueError, 'resolve'):
            validate_collection(self.manifest_path)

    def test_mixed_release_fails(self):
        self.edit_catalog(0, lambda d: d.update(release='other-release'))
        with self.assertRaisesRegex(ValueError, 'mixed release'):
            validate_collection(self.manifest_path)

    def test_undeclared_catalog_fails(self):
        self.manifest['catalogs'].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, 'file mismatch'):
            validate_collection(self.manifest_path)

    def test_conflicting_shared_study_fails(self):
        self.edit_catalog(1, lambda d: d['studies'][0].update(study_name='Conflicting name'))
        with self.assertRaisesRegex(ValueError, 'Conflicting shared'):
            validate_collection(self.manifest_path)

    def test_crlf_and_reformatted_catalogs_pass(self):
        for entry in self.manifest['catalogs']:
            path = self.root / entry['file']
            data = json.loads(path.read_text())
            path.write_bytes(json.dumps(data, indent=4).replace('\n', '\r\n').encode('utf-8'))
        self.assertEqual(validate_collection(self.manifest_path)['concepts'], 303)

    def test_producer_collection_passes(self):
        self.assertEqual(validate_collection(self.manifest_path)['integrated_variables'], 305)


if __name__ == '__main__':
    unittest.main()

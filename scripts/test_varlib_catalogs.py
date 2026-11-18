"""Test the packaged VarLib catalogs and their validation rules."""

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

        self.manifest_path = self.root / "manifest.json"
        self.manifest = self.read_json(self.manifest_path)

    @staticmethod
    def read_json(path):
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def write_json(path, data):
        path.write_text(
            json.dumps(data, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
            )

    def save_manifest(self):
        self.write_json(self.manifest_path, self.manifest)

    def catalog_path(self, index=0):
        return self.root / self.manifest["catalogs"][index]["file"]

    def edit_catalog(self, index, edit):
        path = self.catalog_path(index)
        data = self.read_json(path)
        edit(data)
        self.write_json(path, data)

    def validate(self):
        return validate_collection(self.manifest_path)

    def test_producer_collection_passes(self):
        result = self.validate()

        self.assertEqual(result["release"], self.manifest["release"])
        self.assertEqual(result["catalogs"], 9)
        self.assertEqual(result["concepts"], 303)
        self.assertEqual(result["integrated_variables"], 305)

    def test_empty_manifest_fails(self):
        self.manifest["catalogs"] = []
        self.save_manifest()

        with self.assertRaisesRegex(ValueError, "nonempty"):
            self.validate()

    def test_invalid_catalog_fails(self):
        self.write_json(self.catalog_path(), {})

        # LinkML owns the wording of structural validation errors.
        with self.assertRaises(ValueError):
            self.validate()

    def test_unresolved_reference_fails(self):
        self.edit_catalog(
            0,
            lambda data: data["integrated_variables"][0]["integrates"].append(
                "hv:missing"
            ),
        )

        with self.assertRaisesRegex(ValueError, "resolve"):
            self.validate()

    def test_mixed_release_fails(self):
        self.edit_catalog(0, lambda data: data.update(release="other-release"))

        with self.assertRaisesRegex(ValueError, "mixed release"):
            self.validate()

    def test_undeclared_catalog_fails(self):
        self.manifest["catalogs"].pop()
        self.save_manifest()

        with self.assertRaisesRegex(ValueError, "file mismatch"):
            self.validate()

    def test_incorrect_prefix_fails(self):
        self.manifest["catalogs"][0]["prefix"] = "WRONG"
        self.save_manifest()

        with self.assertRaisesRegex(ValueError, "prefix partition"):
            self.validate()

    def test_conflicting_shared_study_fails(self):
        first_catalog = self.read_json(self.catalog_path(0))
        first_studies = {
            study["id"]: study for study in first_catalog["studies"]
        }

        # Find an actual shared study rather than assuming matching row order.
        for index in range(1, len(self.manifest["catalogs"])):
            path = self.catalog_path(index)
            data = self.read_json(path)

            for study in data["studies"]:
                if study["id"] in first_studies:
                    original = first_studies[study["id"]]["study_name"]
                    study["study_name"] = original + " - conflicting definition"
                    self.write_json(path, data)

                    with self.assertRaisesRegex(
                            ValueError, "Conflicting shared"
                    ):
                        self.validate()
                    return

        self.fail("The fixture collection must contain a shared study")

    def test_crlf_and_reformatted_catalogs_pass(self):
        for entry in self.manifest["catalogs"]:
            path = self.root / entry["file"]
            data = self.read_json(path)
            text = json.dumps(data, indent=4, ensure_ascii=False) + "\n"
            path.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))

        self.assertEqual(self.validate()["concepts"], 303)

    def test_optional_term_metadata_passes(self):
        prefix = self.manifest["catalogs"][0]["prefix"]

        self.edit_catalog(
            0,
            lambda data: data["concepts"][0].update(
                vocabulary=prefix,
                parents=[f"{prefix}:parent-not-in-this-catalog"],
            ),
        )

        # Parents may resolve through a separate term graph.
        self.assertEqual(self.validate()["catalogs"], 9)

    def test_term_without_optional_metadata_passes(self):
        def remove_optional_fields(data):
            concept = data["concepts"][0]
            for field in ("label", "vocabulary", "parents"):
                concept.pop(field, None)

        self.edit_catalog(0, remove_optional_fields)

        self.assertEqual(self.validate()["catalogs"], 9)

    def test_missing_concept_identifier_fails(self):
        self.edit_catalog(
            0,
            lambda data: data["concepts"][0].pop("curie"),
        )

        with self.assertRaises(ValueError):
            self.validate()

    def test_invalid_parents_type_fails(self):
        self.edit_catalog(
            0,
            lambda data: data["concepts"][0].update(parents=42),
        )

        with self.assertRaises(ValueError):
            self.validate()

    def test_unknown_concept_field_fails(self):
        self.edit_catalog(
            0,
            lambda data: data["concepts"][0].update(
                participant_count=100
            ),
        )

        with self.assertRaises(ValueError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
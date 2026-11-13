"""Shared manifest vectors must pass identically in Python and Android."""

import json
import unittest
from pathlib import Path

from transferkit.contracts import validate_document


VECTORS = json.loads(
    (Path(__file__).parents[1] / "shared" / "manifest-contract-vectors.json").read_text("utf-8")
)


class SharedManifestContractVectorTests(unittest.TestCase):
    def test_valid_vectors_pass_schema_and_semantic_validation(self) -> None:
        for vector in VECTORS["valid"]:
            with self.subTest(name=vector["name"]):
                model = validate_document(vector["document"], "manifest.schema.json")
                self.assertIsNotNone(model)

    def test_invalid_vectors_are_rejected(self) -> None:
        for vector in VECTORS["invalid"]:
            with self.subTest(name=vector["name"]):
                with self.assertRaises(ValueError):
                    validate_document(vector["document"], "manifest.schema.json")


if __name__ == "__main__":
    unittest.main()

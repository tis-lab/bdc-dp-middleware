#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn --batch-mode clean verify
python3 -m venv .venv-validation
.venv-validation/bin/python -m pip install -r scripts/requirements-summary-validation.txt -r scripts/requirements-varlib-validation.txt
.venv-validation/bin/python scripts/validate_summary_examples.py
.venv-validation/bin/python scripts/validate_varlib_catalogs.py
.venv-validation/bin/python -m unittest discover -s scripts -p test_varlib_catalogs.py

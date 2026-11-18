$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    mvn --batch-mode clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    python -m venv .venv-validation
    if ($LASTEXITCODE -ne 0) { throw 'Python environment creation failed.' }
    $python = Join-Path $PWD '.venv-validation/Scripts/python.exe'
    & $python -m pip install -r scripts/requirements-summary-validation.txt -r scripts/requirements-varlib-validation.txt
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    foreach ($script in @('validate_summary_examples.py','validate_varlib_catalogs.py')) {
        & $python "scripts/$script"
        if ($LASTEXITCODE -ne 0) { throw "$script failed." }
    }
    & $python -m unittest discover -s scripts -p test_varlib_catalogs.py
    if ($LASTEXITCODE -ne 0) { throw 'Catalog tests failed.' }
} finally { Pop-Location }

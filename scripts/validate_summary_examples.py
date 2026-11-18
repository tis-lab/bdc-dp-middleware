"""Validate the packaged synthetic YAML responses and actual Java output."""
import json
from validate_summary_schema import ROOT, validator, validate_file


def main():
    """Validate fixtures, metadata and Maven-generated response evidence."""
    check = validator(ROOT / 'schema/summary.yaml', 'SummaryResponse')
    directory = ROOT / 'src/main/resources/summary'
    scenarios = json.loads((directory / 'scenarios.json').read_text(encoding='utf-8'))
    for scenario in scenarios:
        response = validate_file(check, directory / scenario['file'])
        assert response.get('focus', []) == scenario['focus'], 'Scenario focus mismatch'
        expected = [] if scenario['filter'] is None else [scenario['filter']['summaryId']]
        assert response.get('filter', []) == expected, 'Scenario filter mismatch'
    for name in ['page-load', 't2d']:
        path = ROOT / 'target/summary-contract' / (name + '.json')
        if not path.exists():
            raise SystemExit('Run Maven tests first: missing ' + str(path))
        validate_file(check, path)
    print('PASS packaged YAML and actual Java summary responses')


if __name__ == "__main__":
    main()

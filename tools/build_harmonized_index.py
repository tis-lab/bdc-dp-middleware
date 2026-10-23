#!/usr/bin/env python3
"""
Generated using Claude.

Build one JSON index of BDC harmonized variables from the RTI repository.

    python build_harmonized_index.py --repo ./NHLBI-BDC-DMC-HV --out harmonized-index.json

Reads `priority_variables_transform/<STUDY>-ingest/<variable>.yaml` — LinkML-Map
transformation specs — and extracts the handful of facts a portal needs, discarding the
transformation machinery. Optionally enriches with human labels from
`transform_assessment/harmonized_qaqc/harmonized_vars.tsv`.

The output carries the two views:

  variables[]  one record per variable name, folded across study directories
  concepts[]   one record per ontology CURIE, inverted from the above

THE COUNTING RULE, which is the whole reason this is not a two-line script:
a concept counts only the studies whose own file assigns it. Several studies can share a
variable file name while assigning different codes, so crediting a code with every study
that has a file of that name overstates coverage badly — it was wrong on 41% of rows when
first written that way.

Requires PyYAML:  pip install pyyaml
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import yaml

# Slots whose literal `value:` is an ontology concept. Everything else in a spec is
# machinery — identifier expressions, value mappings, provenance labels — and those carry
# literals like ONESELF and PRESENT that must never be mistaken for identifiers.
CONCEPT_SLOT = re.compile(r".*_concept$|^observation_type$")

# A CURIE. Full match only, so prose values cannot slip through.
CURIE = re.compile(r"[A-Za-z][A-Za-z0-9._-]*:[A-Za-z0-9._-]+")

INGEST_GLOB = "priority_variables_transform/*-ingest/*.yaml"
LABEL_TABLE = "transform_assessment/harmonized_qaqc/harmonized_vars.tsv"


def concept_key(curie: str) -> str:
    """
    Prefix upper-cased, local identifier untouched.

    Prefix casing is not stable across sources — these files use MeSH and RxCUI, while
    knowledge graphs commonly emit MESH — so an exact match silently loses mappings.
    Upper-casing the whole CURIE would be wrong the other way: some vocabularies do
    distinguish case in the local part.
    """
    prefix, sep, local = curie.partition(":")
    return f"{prefix.upper()}{sep}{local}" if sep else curie


def concepts_in(block, found: set[str]) -> None:
    """Collect every ontology CURIE assigned by a concept slot in one derivation block."""
    for body in (block.get("class_derivations") or {}).values():
        if not isinstance(body, dict):
            continue
        for slot, rule in (body.get("slot_derivations") or {}).items():
            if not isinstance(rule, dict) or not CONCEPT_SLOT.match(slot):
                continue
            value = rule.get("value")
            if (
                isinstance(value, str)
                and CURIE.fullmatch(value)
                and not value.lower().startswith("http")
            ):
                found.add(value)


def read_specs(repo: Path) -> tuple[dict, dict, list[str]]:
    """
    Walk every ingest spec.

    Returns (studies_by_variable, concepts_by_variable_and_study, warnings) where the
    second keeps the study-and-concept pairing per file — the input to the counting rule.
    """
    studies: dict[str, set[str]] = defaultdict(set)
    pairs: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    warnings: list[str] = []

    files = sorted(repo.glob(INGEST_GLOB))
    if not files:
        sys.exit(f"No ingest specs found under {repo / INGEST_GLOB}. Wrong --repo path?")

    for path in files:
        variable = path.stem
        study = path.parent.name.removesuffix("-ingest")
        studies[variable].add(study)

        try:
            blocks = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as error:
            sys.exit(f"Could not parse {path}: {error}")

        if not isinstance(blocks, list):
            # One file upstream is a stub with no derivations. Skipping is correct;
            # failing would make this script hostage to an unrelated file.
            warnings.append(f"{path.relative_to(repo)}: not a list of derivations, skipped")
            continue

        found: set[str] = set()
        for block in blocks:
            if isinstance(block, dict):
                concepts_in(block, found)
        for curie in found:
            pairs[variable][study].add(curie)

    return studies, pairs, warnings


def read_labels(repo: Path) -> tuple[dict[str, dict[str, str]], list[str]]:
    """Human label and domain per variable. Optional: absent means every label is null."""
    path = repo / LABEL_TABLE
    if not path.exists():
        return {}, [f"{LABEL_TABLE} not found; labels and domains will be null"]

    labels: dict[str, dict[str, str]] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            name = (row.get("var_name") or "").strip()
            if not name:
                continue
            labels[name] = {
                "label": (row.get("var_label") or "").strip() or None,
                "domain": (row.get("DOMAIN") or "").strip() or None,
            }
    return labels, []


def build(repo: Path) -> dict:
    studies, pairs, warnings = read_specs(repo)
    labels, label_warnings = read_labels(repo)
    warnings += label_warnings

    variables = []
    for name in sorted(studies):
        extra = labels.get(name, {})
        every_concept = sorted({c for per in pairs[name].values() for c in per})
        variables.append(
            {
                "name": name,
                # Never null, so a frontend never has to choose between two name fields.
                "label": extra.get("label") or name,
                "domain": extra.get("domain"),
                "studyCount": len(studies[name]),
                "studies": sorted(studies[name]),
                "concepts": every_concept,
            }
        )

    # The inversion. Keyed on the normalised CURIE, but the original spelling is kept for
    # display, since that is what the specs actually wrote.
    by_concept: dict[str, dict] = {}
    for variable, per_study in pairs.items():
        for study, curies in per_study.items():
            for curie in curies:
                entry = by_concept.setdefault(
                    concept_key(curie),
                    {"id": curie, "studies": set(), "variableNames": set()},
                )
                entry["studies"].add(study)
                entry["variableNames"].add(variable)

    concepts = [
        {
            "id": entry["id"],
            "studyCount": len(entry["studies"]),
            "studies": sorted(entry["studies"]),
            "variableNames": sorted(entry["variableNames"]),
        }
        for _, entry in sorted(by_concept.items())
    ]

    return {
        "generated": date.today().isoformat(),
        "source": {
            "repository": "RTIInternational/NHLBI-BDC-DMC-HV",
            "ingest": INGEST_GLOB,
            "labelTable": LABEL_TABLE,
        },
        "counts": {
            "specFiles": sum(len(s) for s in studies.values()),
            "studies": len({s for group in studies.values() for s in group}),
            "variables": len(variables),
            "concepts": len(concepts),
            "variablesWithALabel": sum(1 for v in variables if v["label"] != v["name"]),
            "variablesWithNoConcept": sum(1 for v in variables if not v["concepts"]),
        },
        "warnings": warnings,
        "variables": variables,
        "concepts": concepts,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--repo", type=Path, required=True, help="checkout of NHLBI-BDC-DMC-HV")
    parser.add_argument("--out", type=Path, help="write JSON here (default: stdout)")
    parser.add_argument("--quiet", action="store_true", help="suppress the summary on stderr")
    args = parser.parse_args()

    index = build(args.repo)
    text = json.dumps(index, indent=2, ensure_ascii=False)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)

    if not args.quiet:
        counts = index["counts"]
        print(
            f"{counts['specFiles']} spec files in {counts['studies']} studies -> "
            f"{counts['variables']} variables, {counts['concepts']} concepts "
            f"({counts['variablesWithALabel']} with a label, "
            f"{counts['variablesWithNoConcept']} with no concept)",
            file=sys.stderr,
        )
        for warning in index["warnings"]:
            print(f"  warning: {warning}", file=sys.stderr)


if __name__ == "__main__":
    main()

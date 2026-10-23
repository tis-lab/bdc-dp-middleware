#!/usr/bin/env python3
"""
Generated using Claude.

Build a concept-keyed index of BDC harmonized variables from the RTI repository.

    python build_harmonized_index.py --repo ./NHLBI-BDC-DMC-HV --out harmonized-index.json

Reads `priority_variables_transform/<STUDY>-ingest/<variable>.yaml` — LinkML-Map
transformation specs — and inverts them so the ontology concept is the key and the
variables that carry it hang off it.

WHY CONCEPT-FIRST. An earlier version keyed on the spec file name (`hyperten.yaml` ->
`hyperten`). File names carry no meaning: they are an artefact of how the specs are
organised, two studies can name the same thing differently, and 49 of 179 have no
human label at all, so a portal ends up showing a raw stem like `basophil_ct` to a
researcher. The meaning lives in the ontology identifier the spec assigns, which is
also the only thing that joins to a knowledge graph. This mirrors `IntegratedVariable`
in linkml/bdc-variable-library, where `concept_type` is the key and `integrates` lists
the per-study variables.

THE COUNTING RULE. A concept counts only the studies whose own spec assigns it. Several
studies can share a variable file name while assigning different codes, so crediting a
code with every study that has a file of that name overstates coverage badly — it was
wrong on 41% of rows when first written that way.

Requires PyYAML:  pip install pyyaml
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import yaml

# Slots whose literal `value:` is an ontology concept. Everything else in a spec is
# machinery — identifier expressions, value mappings, provenance labels — and those carry
# literals like ONESELF and PRESENT that must never be mistaken for identifiers.
CONCEPT_SLOT = re.compile(r".*_concept$|^observation_type$")

# A CURIE. Full match only, so prose values cannot slip through.
CURIE = re.compile(r"[A-Za-z][A-Za-z0-9._-]*:[A-Za-z0-9._-]+")

# BDCHM class in the spec -> the `bdchm_type` enum in linkml/bdc-variable-library.
# SdohObservation is a social-determinants observation; the library has no narrower term.
BDCHM_TYPE = {
    "Condition": "condition",
    "MeasurementObservation": "observation",
    "Observation": "observation",
    "SdohObservation": "observation",
    "DrugExposure": "drugexposure",
    "Procedure": "procedure",
    "Exposure": "exposure",
}

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


def read_specs(repo: Path):
    """
    Walk every ingest spec once.

    Returns three things, all keyed so the counting rule can be applied afterwards:
      assignments  (variable, study) -> {curie}
      classes      curie -> Counter of the BDCHM classes that assigned it
      studies      variable -> {study}, i.e. every study with a file of that name
    """
    assignments: dict[tuple[str, str], set[str]] = defaultdict(set)
    classes: dict[str, Counter] = defaultdict(Counter)
    studies: dict[str, set[str]] = defaultdict(set)
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

        for block in blocks:
            if not isinstance(block, dict):
                continue
            for bdchm_class, body in (block.get("class_derivations") or {}).items():
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
                        assignments[(variable, study)].add(value)
                        classes[concept_key(value)][bdchm_class] += 1

    return assignments, classes, studies, warnings


def read_labels(repo: Path):
    """Human label per variable, from the QA spreadsheet. Optional enrichment."""
    path = repo / LABEL_TABLE
    if not path.exists():
        return {}, [f"{LABEL_TABLE} not found; labels will be null"]

    labels: dict[str, str] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            name = (row.get("var_name") or "").strip()
            label = (row.get("var_label") or "").strip()
            if name and label:
                labels[name] = label
    return labels, []


def build(repo: Path) -> dict:
    assignments, classes, studies, warnings = read_specs(repo)
    labels, label_warnings = read_labels(repo)
    warnings += label_warnings

    # Invert: concept -> which variables assign it, and in which studies.
    carriers: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    original: dict[str, str] = {}
    for (variable, study), curies in assignments.items():
        for curie in curies:
            key = concept_key(curie)
            original.setdefault(key, curie)
            carriers[key][variable].add(study)

    concepts = []
    for key in sorted(carriers):
        by_variable = carriers[key]

        # The counting rule: only the studies that actually assign this concept.
        covering = sorted({s for group in by_variable.values() for s in group})

        # A concept has no label of its own in the specs, so take the first its carrying
        # variables supply. 213 of 240 get one this way; the rest fall back to the CURIE,
        # which is still meaningful where a file name is not.
        #
        # Called conceptLabel, not label: GraphQL forbids two members of a union exposing one
        # field name with different nullability, and Term.label is non-null. Keeping the JSON
        # key, the Java component and the GraphQL field identical avoids a silent mismatch.
        #
        # Named conceptLabel, not label: Term.label is non-null in the GraphQL schema, and a
        # union cannot expose one field name with two nullabilities.
        label = next(
            (labels[v] for v in sorted(by_variable) if v in labels),
            None,
        )

        # The BDCHM class the specs most often use for this concept, mapped to the
        # bdc-variable-library enum.
        common = classes[key].most_common(1)
        bdchm_type = BDCHM_TYPE.get(common[0][0]) if common else None

        concepts.append(
            {
                "conceptType": original[key],
                "conceptLabel": label,
                "bdchmType": bdchm_type,
                "studyCount": len(covering),
                "studies": covering,
                "variables": [
                    {
                        "variableName": variable,
                        "variableLabel": labels.get(variable),
                        # Only the studies where THIS variable assigns THIS concept, not
                        # every study with a file of that name.
                        "studies": sorted(by_variable[variable]),
                    }
                    for variable in sorted(by_variable)
                ],
            }
        )

    unmapped = sorted(
        {c for key in classes for c in classes[key] if c not in BDCHM_TYPE}
    )
    if unmapped:
        warnings.append(
            "BDCHM classes with no bdchm_type mapping, left null: " + ", ".join(unmapped)
        )

    return {
        "generated": date.today().isoformat(),
        "source": {
            "repository": "RTIInternational/NHLBI-BDC-DMC-HV",
            "ingest": INGEST_GLOB,
            "labelTable": LABEL_TABLE,
            "alignedWith": "linkml/bdc-variable-library IntegratedVariable",
        },
        "counts": {
            "specFiles": sum(len(s) for s in studies.values()),
            "studies": len({s for group in studies.values() for s in group}),
            "distinctVariableNames": len(studies),
            "concepts": len(concepts),
            "conceptsWithALabel": sum(1 for c in concepts if c["conceptLabel"]),
            "conceptsWithABdchmType": sum(1 for c in concepts if c["bdchmType"]),
        },
        "warnings": warnings,
        "concepts": concepts,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a concept-keyed harmonized index.")
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
            f"{counts['concepts']} concepts "
            f"({counts['conceptsWithALabel']} with a label, "
            f"{counts['conceptsWithABdchmType']} with a bdchmType) "
            f"across {counts['distinctVariableNames']} variable names",
            file=sys.stderr,
        )
        for warning in index["warnings"]:
            print(f"  warning: {warning}", file=sys.stderr)


if __name__ == "__main__":
    main()

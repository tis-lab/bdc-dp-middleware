#!/usr/bin/env python3
"""
Generated using Claude.

Build a concept-keyed index of BDC harmonized variables from the RTI repository.

    python build_harmonized_index.py --repo ./NHLBI-BDC-DMC-HV --out harmonized-index.json

Reads `priority_variables_transform/<STUDY>-ingest/<variable>.yaml` — LinkML-Map
transformation specs — and inverts them so the ontology concept is the key and the
variables that carry it hang off it.

Concept CURIEs are identities; filenames remain searchable provenance aliases only.
Exact BDCHM classes and study/class/slot/file assignments are preserved. QA labels
are variable labels, never concept labels. This index is a prototype projection,
not a serialization of the DMC variable-library model. The future source is undecided.

Study counts include only specifications assigning the exact concept; they are
mapping coverage, not participant counts or authorization to access a dataset.

Requires PyYAML:  pip install pyyaml
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import subprocess
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

    Returns mappings and diagnostics, all keyed so the counting rule can be applied afterwards:
      assignments  (variable, study) -> {curie}
      classes      curie -> exact BDCHM classes that assigned it
      mappings     curie -> (study, class, slot, source file) tuples
      studies      variable -> {study}, i.e. every study with a file of that name
    """
    assignments: dict[tuple[str, str], set[str]] = defaultdict(set)
    classes: dict[str, set[str]] = defaultdict(set)
    mappings: dict[str, set[tuple[str, str, str, str]]] = defaultdict(set)
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
                        classes[concept_key(value)].add(bdchm_class)
                        mappings[concept_key(value)].add((study, bdchm_class, slot, path.relative_to(repo).as_posix()))

    return assignments, classes, mappings, studies, warnings


def read_labels(path: Path | None):
    """Explicit optional variable-label TSV; never a source of ontology labels."""
    if path is None:
        return {}, []
    if not path.is_file():
        raise ValueError(f"Variable-label file does not exist: {path}")

    labels: dict[str, str] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            name = (row.get("var_name") or "").strip()
            label = (row.get("var_label") or "").strip()
            if name and label:
                labels[name] = label
    return labels, []


def build(repo: Path, variable_labels: Path | None = None, generated: str | None = None) -> dict:
    assignments, classes, mappings, studies, warnings = read_specs(repo)
    labels, label_warnings = read_labels(variable_labels)
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

        # QA labels describe specification aliases, not ontology concepts.
        # Preserve them on carriers for search; never promote them to concept labels.
        label = None
        exact_classes = sorted(classes[key])
        coarse = {BDCHM_TYPE.get(c) for c in exact_classes}
        # Retain the old convenience field only when all classes agree.
        bdchm_type = next(iter(coarse)) if len(coarse) == 1 else None

        concepts.append(
            {
                "conceptType": original[key],
                "conceptLabel": label,
                "bdchmType": bdchm_type,
                "bdchmClasses": exact_classes,
                "sourceMappings": [
                    {"study": study, "bdchmClass": cls, "conceptSlot": slot, "sourceFile": file}
                    for study, cls, slot, file in sorted(mappings[key])
                ],
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

    try:
        revision = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"], check=True,
            capture_output=True, text=True).stdout.strip()
        dirty = bool(subprocess.run(
            ["git", "-C", str(repo), "status", "--porcelain"], check=True,
            capture_output=True, text=True).stdout.strip())
    except (OSError, subprocess.CalledProcessError):
        revision, dirty = None, None

    return {
        "schemaVersion": 2,
        "generated": generated or date.today().isoformat(),
        "source": {
            "repository": "RTIInternational/NHLBI-BDC-DMC-HV",
            "ingest": INGEST_GLOB,
            "variableLabels": None if variable_labels is None else {
                "file": variable_labels.name,
                "sha256": hashlib.sha256(variable_labels.read_bytes()).hexdigest(),
            },
            "revision": revision,
            "dirty": dirty,
            "representation": "interim concept index; not DMC model instances",
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
    parser.add_argument("--variable-labels", type=Path,
                        help="Optional explicit TSV (var_name, var_label); variable labels only")
    parser.add_argument("--generated-date", type=lambda value: date.fromisoformat(value).isoformat(),
                        help="ISO date for reproducible generation; defaults to today")
    parser.add_argument("--quiet", action="store_true", help="suppress the summary on stderr")
    args = parser.parse_args()

    index = build(args.repo, args.variable_labels, args.generated_date)
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

"""Give each study folder of harmonized data a ResearchStudy, and point its participants at it.

For every study folder (under ``data/harmonized`` by default), this:

- writes ``<prefix>-ResearchStudy--data.yaml`` with the folder's ResearchStudy,
  where ``<prefix>`` comes from the folder's ``<prefix>-Participant--data.yaml``;
- sets ``member_of_research_study`` of every participant in that file to the
  study's id, a reference that conforms to the bdchm schema, instead of the
  study's name.

Every folder needs an entry in ``STUDIES``, except those starting with a dot,
which are ignored. An entry with no folder only gets a warning, so that part of
the data can be present.

It can be run again at any time, for example after the data is regenerated:

- The study ids are derived from ``STUDIES``, so they don't change between runs.
- ``STUDIES`` is the source of the study records, because ``data/harmonized`` is
  not committed. To change a study, edit it there: a study file that differs is
  replaced.
- A participant may already have the study's id, or still have its name. Any
  other value stops the run, so that a participant isn't moved to another study
  by accident.

Everything is checked before anything is written. Only the
``member_of_research_study`` lines of a participant file change. Each file is
replaced in one step, so a failure can't leave half a file behind.

Exit status: 0 if the data is as it should be now, 2 if it couldn't be done. If
a check failed, nothing was written. If a write failed, the message lists the
files already written; fix the cause and run it again.
"""

import argparse
import os
import re
import shutil
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path

import yaml

from py_tools.pg_load_data import DEFAULT_DATA_DIR, read_records

SLOT = "member_of_research_study"
SLOT_LINE = re.compile(rf"^{SLOT}:[^\r\n]*", re.MULTILINE)
PARTICIPANT_FILE = re.compile(r"^(?P<prefix>.+)-Participant--data\.yaml$")

# Example data, made up for the synthetic harmonized data sets. These are the
# ResearchStudy slots of schema/linkml/bdchm.yaml; name, research_project_type,
# program and principal_investigator are required. The key is the folder name.
STUDIES: dict[str, dict] = {
    "study_one": {
        "accession_authority": "Example Accession Authority",
        "accession_number": "EXAMPLE-0001",
        "description": (
            "A made-up observational cohort study, for the synthetic SYNTH1 data set. "
            "It follows adults with chronic conditions through repeated clinic visits."
        ),
        "description_shortened": "Made-up cohort study for the SYNTH1 data.",
        "identity": ["SYNTH1"],
        "name": "Example Study One",
        "name_shortened": "Study One",
        "principal_investigator": ["Example Investigator A"],
        "program": "Example Research Program",
        "research_project_type": "STUDY",
        "sponsor": "Example Research Sponsor",
        "url": "https://example.org/studies/synth1",
        "version": "1.0",
    },
    "study_two": {
        "accession_authority": "Example Accession Authority",
        "accession_number": "EXAMPLE-0002",
        "description": (
            "A made-up longitudinal cohort study, for the synthetic SYNTH2 data set. "
            "It has more visits per participant than Example Study One, and is ongoing."
        ),
        "description_shortened": "Made-up longitudinal study for the SYNTH2 data.",
        "identity": ["SYNTH2"],
        "name": "Example Study Two",
        "name_shortened": "Study Two",
        "principal_investigator": ["Example Investigator B", "Example Investigator C"],
        "program": "Example Research Program",
        "research_project_type": "STUDY",
        "sponsor": "Example Research Sponsor",
        "url": "https://example.org/studies/synth2",
        "version": "1.0",
    },
}


class PlanError(Exception):
    """The data can't be brought to the wanted state."""


def stable_id(*parts: str) -> str:
    """A UUID that depends only on ``parts``, of the same kind as the data's ids (version 5)."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "https://example.org/bdchm/" + "/".join(parts)))


def study_record(folder: str) -> dict:
    """The folder's ResearchStudy, with its id."""
    return {"id": stable_id("ResearchStudy", folder), **STUDIES[folder]}


def study_text(record: dict) -> str:
    """The record in the format of the other data files: sorted keys, each document followed by ``---``."""
    return yaml.safe_dump(record, sort_keys=True, allow_unicode=True) + "---\n"


@dataclass
class FilePlan:
    folder: str
    study_path: Path
    study_text: str
    study_status: str  # "create", "replace" or "unchanged"
    participant_path: Path
    participant_text: str
    changed: int  # participants to change
    total: int  # participants in the file


def plan_participants(path: Path, record: dict) -> tuple[str, int, int]:
    """The file's new text, how many participants change, and how many there are."""
    study_id, name = record["id"], record["name"]
    records = read_records(path)
    changed = 0
    for r in records:
        value = r.data.get(SLOT)
        if value == name:
            changed += 1
        elif value != study_id:
            raise PlanError(
                f"{path}: participant on line {r.line} has {SLOT} {value!r}, expected the study's "
                f"name {name!r} or id {study_id!r}"
            )
    text = path.read_bytes().decode("utf-8")
    new_text, lines = SLOT_LINE.subn(f"{SLOT}: {study_id}", text)
    if lines != len(records):  # e.g. the value is written over several lines
        raise PlanError(f"{path}: found {lines} '{SLOT}:' lines at the top level for {len(records)} participants")
    return new_text, changed, len(records)


def plan_folder(folder_path: Path) -> FilePlan:
    folder = folder_path.name
    candidates = sorted(p for p in folder_path.iterdir() if PARTICIPANT_FILE.match(p.name))
    if len(candidates) != 1:
        names = ", ".join(p.name for p in candidates) or "none"
        raise PlanError(f"{folder_path}: expected one <prefix>-Participant--data.yaml file, found: {names}")
    participant_path = candidates[0]
    prefix = PARTICIPANT_FILE.match(participant_path.name)["prefix"]
    study_path = folder_path / f"{prefix}-ResearchStudy--data.yaml"

    record = study_record(folder)
    text = study_text(record)
    if not study_path.exists():
        status = "create"
    elif study_path.read_text(encoding="utf-8") == text:
        status = "unchanged"
    else:
        status = "replace"
    new_text, changed, total = plan_participants(participant_path, record)
    return FilePlan(folder, study_path, text, status, participant_path, new_text, changed, total)


def write_file(path: Path, text: str) -> None:
    """Replace the file in one step, keeping its permissions."""
    temp = path.with_name(path.name + ".tmp")
    try:
        temp.write_bytes(text.encode("utf-8"))
        if path.exists():
            shutil.copymode(path, temp)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Write a ResearchStudy file into each study folder of harmonized data, and set "
            f"{SLOT} of the folder's participants to that study's id. Safe to run again."
        ),
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=DEFAULT_DATA_DIR,
        help=f"the folder holding the study folders (default: {DEFAULT_DATA_DIR})",
    )
    parser.add_argument("--dry-run", action="store_true", help="report what would change, without writing")
    args = parser.parse_args(argv)
    sys.exit(run(args))


def run(args: argparse.Namespace) -> int:
    data_dir = args.data_dir.resolve()
    if not data_dir.is_dir():
        print(f"harmonized-add-studies: not a folder: {data_dir}", file=sys.stderr)
        return 2
    folders = sorted(p for p in data_dir.iterdir() if p.is_dir() and not p.name.startswith("."))
    unknown = [p.name for p in folders if p.name not in STUDIES]
    if unknown:
        print(
            f"harmonized-add-studies: no study defined for: {', '.join(unknown)}. Add them to STUDIES in "
            f"{Path(__file__).name}. Nothing was written.",
            file=sys.stderr,
        )
        return 2
    if not folders:
        print(f"harmonized-add-studies: no study folders in {data_dir}", file=sys.stderr)
        return 2
    missing = [name for name in STUDIES if not (data_dir / name).is_dir()]
    if missing:
        print(f"harmonized-add-studies: WARNING: no folder for: {', '.join(missing)}; skipping", file=sys.stderr)

    try:
        plans = [plan_folder(p) for p in folders]
    except (PlanError, yaml.YAMLError, ValueError, OSError) as e:
        print(f"harmonized-add-studies: {e}\nNothing was written.", file=sys.stderr)
        return 2

    prefix = "would " if args.dry_run else ""
    written: list[str] = []
    for plan in plans:
        if not args.dry_run:
            try:
                # The study first: participants should never reference a study that isn't there.
                if plan.study_status != "unchanged":
                    write_file(plan.study_path, plan.study_text)
                    written.append(str(plan.study_path))
                if plan.changed:
                    write_file(plan.participant_path, plan.participant_text)
                    written.append(str(plan.participant_path))
            except OSError as e:
                print(
                    f"harmonized-add-studies: {e}\nAlready written: {', '.join(written) or 'nothing'}\n"
                    "Fix the cause and run it again.",
                    file=sys.stderr,
                )
                return 2
        study_action = "unchanged" if plan.study_status == "unchanged" else f"{prefix}{plan.study_status}"
        print(f"{plan.folder}:")
        print(f"  {plan.study_path.name}: {study_action}")
        print(f"  {plan.participant_path.name}: {prefix}change {plan.changed} of {plan.total} participants")
    return 0


if __name__ == "__main__":
    main()

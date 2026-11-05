"""Give every participant of the harmonized data a random ``age_at_index``.

For every ``<prefix>-Participant--data.yaml`` under ``data/harmonized`` (by
default), this sets ``age_at_index``, which the bdchm schema defines in days, to
a value between 18 and 99 years. The ages are roughly uniform over that range.

They're made-up values, independent of the other ages in the data: an
``age_at_index`` can be later than the participant's visits.

It can be run again at any time, for example after the data is regenerated:

- An age is derived from a hash of the participant's id, so a participant gets
  the same age on every run.
- A participant may have no age yet, or a null one, or already have the derived
  age. Any other value stops the run, so that a value from elsewhere isn't
  replaced by accident.

Everything is checked before anything is written. Only the ``age_at_index`` lines
of a file change: the line is added first in each record, in the sorted position
the data files use.

Exit status: 0 if the data is as it should be now, 2 if it couldn't be done. If
a check failed, nothing was written. If a write failed, the message lists the
files already written; fix the cause and run it again.
"""

import argparse
import hashlib
import math
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import yaml

from py_tools.harmonized_add_studies import PARTICIPANT_FILE, write_file
from py_tools.pg_load_data import DEFAULT_DATA_DIR, read_records

SLOT = "age_at_index"
DAYS_PER_YEAR = 365.25
MIN_DAYS = math.ceil(18 * DAYS_PER_YEAR)  # the first day of age 18
MAX_DAYS = math.ceil(100 * DAYS_PER_YEAR) - 1  # the last day of age 99


class PlanError(Exception):
    """The data can't be brought to the wanted state."""


def age_in_days(participant_id: str) -> int:
    """A value from MIN_DAYS to MAX_DAYS that depends only on the id."""
    digest = hashlib.sha256(f"{SLOT}/{participant_id}".encode()).digest()
    return MIN_DAYS + int.from_bytes(digest[:8]) % (MAX_DAYS - MIN_DAYS + 1)


@dataclass
class FilePlan:
    path: Path
    text: str
    changed: int  # participants to change
    ages: list[int]  # every participant's age, in days


def plan_file(path: Path) -> FilePlan:
    records = read_records(path)
    text = path.read_bytes().decode("utf-8")
    lines = text.splitlines(keepends=True)
    starts = [r.line - 1 for r in records] + [len(lines)]
    changed = 0
    ages = []
    # From the last record back, so inserting a line doesn't move the earlier ones.
    for i in reversed(range(len(records))):
        record = records[i]
        participant_id = record.data.get("id")
        if participant_id is None:
            raise PlanError(f"{path}: participant on line {record.line} has no id")
        age = age_in_days(str(participant_id))
        ages.append(age)
        value = record.data.get(SLOT)
        if value == age:
            continue
        if value is not None:
            raise PlanError(
                f"{path}: participant on line {record.line} has {SLOT} {value!r}, expected none or {age}"
            )
        start = starts[i]
        ending = "\r\n" if lines[start].endswith("\r\n") else "\n"
        new_line = f"{SLOT}: {age}{ending}"
        existing = [n for n in range(start, starts[i + 1]) if lines[n].startswith(f"{SLOT}:")]
        if existing:  # a null value
            lines[existing[0]] = new_line
        else:
            lines.insert(start, new_line)
        changed += 1
    return FilePlan(path, "".join(lines), changed, ages)


def decade_counts(ages: list[int]) -> str:
    """How many ages fall in each decade of years, as a printable table."""
    counts = Counter(int(age // DAYS_PER_YEAR) // 10 * 10 for age in ages)
    return "\n".join(f"  {decade:>2}s: {counts[decade]}" for decade in sorted(counts))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description=(
            f"Set {SLOT} of every participant in the harmonized data to a random age from 18 to 99 "
            "years, in days. A participant gets the same age on every run. Safe to run again."
        ),
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=DEFAULT_DATA_DIR,
        help=f"the folder to look for participant files in, including subfolders (default: {DEFAULT_DATA_DIR})",
    )
    parser.add_argument("--dry-run", action="store_true", help="report what would change, without writing")
    args = parser.parse_args(argv)
    sys.exit(run(args))


def run(args: argparse.Namespace) -> int:
    data_dir = args.data_dir.resolve()
    paths = sorted(p for p in data_dir.rglob("*-Participant--data.yaml") if PARTICIPANT_FILE.match(p.name))
    if not paths:
        print(f"harmonized-add-ages: no participant files under {data_dir}", file=sys.stderr)
        return 2
    try:
        plans = [plan_file(p) for p in paths]
    except (PlanError, yaml.YAMLError, ValueError, OSError) as e:
        print(f"harmonized-add-ages: {e}\nNothing was written.", file=sys.stderr)
        return 2

    prefix = "would " if args.dry_run else ""
    written: list[str] = []
    for plan in plans:
        if not args.dry_run and plan.changed:
            try:
                write_file(plan.path, plan.text)
                written.append(str(plan.path))
            except OSError as e:
                print(
                    f"harmonized-add-ages: {e}\nAlready written: {', '.join(written) or 'nothing'}\n"
                    "Fix the cause and run it again.",
                    file=sys.stderr,
                )
                return 2
        print(f"{plan.path.name}: {prefix}change {plan.changed} of {len(plan.ages)} participants")
    all_ages = [age for plan in plans for age in plan.ages]
    print(f"Participants per decade of age:\n{decade_counts(all_ages)}")
    return 0


if __name__ == "__main__":
    main()

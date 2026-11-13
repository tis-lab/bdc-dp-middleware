# Database schemas

# AI Generated - has not been curated by a human yet!!!


| File | What it holds |
|---|---|
| `common.yaml` | Conventions and slots shared by both databases |
| `catalog.yaml`, `catalog.sql` | Catalog database: releases, terms, studies, consent meaning |
| `counting.yaml`, `counting.sql` | Counting database: participant-level projection, posting lists, cohorts |

Each `.yaml` is the LinkML model and each `.sql` the DDL. They must agree; see
[Validation](#validation).

## What the counts mean

These rules are defined in `counting.yaml` and implemented by the rebuild job
and the middleware.

### A participant is one enrollment in one study

Each `participant_id` is one BDC-HM `Participant` record: one person's
enrollment in one research study, under one consent group. BDC-HM has no
identifier that links the same person across studies, so someone enrolled in
two studies is two participants. A count over several studies counts
enrollments, not distinct people.

### Conflicting demography records

A participant can have several `Demography` records (one per visit).
`counting.participant_attribute` reduces them to one row per participant:

| Value | Rule |
|---|---|
| `sex`, `ethnicity` | Set only when all the participant's non-NULL values agree; otherwise NULL, so the participant is counted under no value and not as recorded |
| `race` | Union of the records' values, so race buckets can add up to more than the participant count |

The `demography_conflict` release check reports, for each release, how many
participants have records that disagree.

### Age summaries

Age summaries bin `age_at_baseline_days`: `Visit.age_at_visit_start` of the
study's curated baseline visit (`counting.study_baseline`). A participant
without a baseline visit is not counted as having an age. `age_at_index_days`
is kept for fidelity to BDC-HM, but harmonization does not populate it and
summaries do not use it.

Harmonized ages are whole years × 365, so binning converts with the same
factor: age in years is `floor(age_at_baseline_days / 365)`, and a band
`[low, high)` in years holds days from `365 * low` inclusive to `365 * high`
exclusive. Converting with 365.25 instead would move everyone whose age is
exactly a band's lower bound into the band below (45 years is stored as 16425,
below `ceil(45 * 365.25)` = 16437).

### Measurement values

`counting.event.value_numeric` holds `value_quantity.value_decimal` when set,
otherwise `value_quantity.value_integer`, so integer- and decimal-valued
observations are compared and binned together. Records with both set to
different values fail the `quantity_value_conflict` release check.

### Condition ages

`age_start_days` for a condition is `Condition.age_at_condition_start`, NULL
when not recorded. The pinned BDC-HM has no other slot for the age at which a
condition was reported.

## Validation

`schema/tests/check_schema.py` compares each model with its DDL (columns,
partitioning, keys, nullability, types, enum and pattern checks) and checks
that every `bdchm:` mapping names a class or slot in the pinned BDC-HM
(`synthetic/bdchm.yaml`). It is not run in CI yet; run it after changing a
model or its DDL:

```sh
pip install -r schema/tests/requirements.txt
python schema/tests/check_schema.py                                         # catalog and counting
python schema/tests/check_schema.py schema/db/counting.yaml schema/db/counting.sql   # one pair
```

It can be run from any directory; with no arguments it finds the files from the
repository root. It prints one line per problem and exits 1 if there are any.

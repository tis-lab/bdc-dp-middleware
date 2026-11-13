-- =====================================================================
-- COUNTING DATABASE
--   counting : participant-level projection, posting lists, cohorts
-- Release-scoped tables are partitioned by release, so retiring a
-- release is a DETACH and DROP of its partitions. The exception is
-- release_check: it is the permanent record of what was verified before
-- each release was served, so it is never partitioned or deleted from.
-- Columns must match counting.yaml (LinkML); check_schema_sql.py
-- fails CI when they differ. Partitioning, indexes and grants live only
-- here, because the LinkML SQL generator cannot express them.
-- =====================================================================

CREATE SCHEMA counting;

CREATE TABLE counting.participant_ordinal (        -- numbered in consent-group order
  release            text,
  ordinal            integer,
  participant_id     text NOT NULL,
  research_study_id  text NOT NULL,
  consent_group      text NOT NULL,
  PRIMARY KEY (release, ordinal),
  UNIQUE (release, participant_id)
) PARTITION BY LIST (release);

CREATE TABLE counting.participant_attribute (
  release               text,
  ordinal               integer,
  sex                   text,                       -- NULL when Demography records disagree
  ethnicity             text,
  race                  text[],                     -- union across Demography records (multivalued in BDC-HM)
  age_at_index_days     integer,                    -- unpopulated by current harmonization
  baseline_visit_id     text,
  age_at_baseline_days  integer,                    -- whole years x 365
  PRIMARY KEY (release, ordinal)
) PARTITION BY LIST (release);
CREATE INDEX participant_attribute_race_idx ON counting.participant_attribute USING gin (race);

CREATE TABLE counting.study_baseline (             -- curated baseline visit per study
  release              text,
  research_study_id    text,
  baseline_visit_name  text NOT NULL,
  PRIMARY KEY (release, research_study_id)
) PARTITION BY LIST (release);

CREATE TABLE counting.group_range (                -- each consent group's contiguous ordinals
  release            text,
  consent_group      text,
  research_study_id  text    NOT NULL,
  lo                 integer NOT NULL,
  hi                 integer NOT NULL,
  PRIMARY KEY (release, consent_group),
  CHECK (lo <= hi)
) PARTITION BY LIST (release);

CREATE TABLE counting.event (                      -- projection of BDC-HM records
  release            text,
  source_record_id   text,                         -- BDC-HM Entity id
  source_class       text    NOT NULL,             -- Condition, Procedure, DrugExposure, MeasurementObservation
  source_file        text    NOT NULL,
  record_provenance  text,                         -- condition_provenance, ...
  ordinal            integer NOT NULL,             -- participant
  visit_id           text,                         -- associated_visit
  age_start_days     integer,
  age_end_days       integer,
  age_start_slot     text    NOT NULL,             -- BDC-HM slot the start age came from
  concept            text    NOT NULL,             -- condition_concept, observation_type, ...
  status             text,                         -- condition_status, procedure_status, exposure_status
  relationship       text,                         -- relationship_to_participant (conditions)
  value_decimal      numeric,
  value_operator     text,                         -- e.g. LESS_THAN
  unit               text,
  PRIMARY KEY (release, source_record_id)
) PARTITION BY LIST (release);
CREATE INDEX event_concept_idx  ON counting.event (release, concept, status, ordinal);
CREATE INDEX event_value_idx    ON counting.event (release, concept, value_decimal);
CREATE INDEX event_timeline_idx ON counting.event (release, ordinal, age_start_days);

CREATE TABLE counting.term_closure (               -- copy of the release's closure, for joins here
  release     text,
  ancestor    text,
  descendant  text,
  PRIMARY KEY (release, ancestor, descendant)
) PARTITION BY LIST (release);
CREATE INDEX counting_closure_descendant_idx ON counting.term_closure (release, descendant);

CREATE TABLE counting.term_member (                -- posting lists: who has each term
  release      text,
  concept      text,                               -- term, subtypes included
  status_rule  text CHECK (status_rule IN ('present', 'present_or_historical', 'absent', 'unknown')),
  ordinal      integer,
  PRIMARY KEY (release, concept, status_rule, ordinal)
) PARTITION BY LIST (release);

CREATE TABLE counting.release_check (              -- checks that must pass before activation; permanent history
  release     text,
  check_name  text CHECK (check_name IN ('participant_count', 'null_status', 'relationship_unmapped',
                                         'demography_conflict', 'baseline_coverage',
                                         'unit_inconsistent', 'unit_missing', 'enum_value_invalid')),
  expected    bigint,
  actual      bigint,
  passed      boolean     NOT NULL,
  checked_at  timestamptz NOT NULL,
  PRIMARY KEY (release, check_name)
);                                                 -- not partitioned: retained after a release is retired

CREATE TABLE counting.cohort (                     -- materialized cohorts (stage 5)
  cohort_id        uuid PRIMARY KEY,
  release          text        NOT NULL,
  ras_subject      text,                           -- NULL only if public cohorts are ever saved
  expression       jsonb       NOT NULL,           -- cohort expression over BDC-HM
  expression_hash  text        NOT NULL,
  created_at       timestamptz NOT NULL,
  expires_at       timestamptz NOT NULL
);

CREATE TABLE counting.cohort_member (
  cohort_id       uuid REFERENCES counting.cohort ON DELETE CASCADE,
  ordinal         integer,
  in_exact_scope  boolean NOT NULL,
  PRIMARY KEY (cohort_id, ordinal)
);

CREATE TABLE counting.cohort_evidence (            -- which source records satisfied each rule
  cohort_id         uuid REFERENCES counting.cohort ON DELETE CASCADE,
  ordinal           integer,
  rule_name         text,
  source_record_id  text,
  PRIMARY KEY (cohort_id, ordinal, rule_name, source_record_id)
);

CREATE VIEW counting.cohort_member_in_scope AS     -- provisioning never sees out-of-scope members
  SELECT cohort_id, ordinal FROM counting.cohort_member WHERE in_exact_scope;

-- Per-release partitions, created by the rebuild job:
CREATE TABLE counting.participant_ordinal_r2026_10   PARTITION OF counting.participant_ordinal   FOR VALUES IN ('2026.10');
CREATE TABLE counting.participant_attribute_r2026_10 PARTITION OF counting.participant_attribute FOR VALUES IN ('2026.10');
CREATE TABLE counting.study_baseline_r2026_10        PARTITION OF counting.study_baseline        FOR VALUES IN ('2026.10');
CREATE TABLE counting.group_range_r2026_10           PARTITION OF counting.group_range           FOR VALUES IN ('2026.10');
CREATE TABLE counting.event_r2026_10                 PARTITION OF counting.event                 FOR VALUES IN ('2026.10');
CREATE TABLE counting.term_closure_r2026_10          PARTITION OF counting.term_closure          FOR VALUES IN ('2026.10');
CREATE TABLE counting.term_member_r2026_10           PARTITION OF counting.term_member           FOR VALUES IN ('2026.10');


-- =====================================================================
-- ROLES AND GRANTS
-- Each role maps to one Entra managed identity (Entra-only authentication).
-- Run the catalog section in the catalog database and the counting
-- section in the counting database.
-- =====================================================================
-- Some of these roles are theoretical and don't actually exist yet, but represent different 
-- access levels that may be required for different processes
GRANT USAGE ON SCHEMA counting TO rebuild_job, api_counting, api_public, provisioning;
GRANT SELECT, INSERT, UPDATE, DELETE ON counting.participant_ordinal, counting.participant_attribute,
      counting.study_baseline, counting.group_range, counting.event,
      counting.term_closure, counting.term_member TO rebuild_job;
-- release_check is history: the rebuild job records and re-runs checks but cannot delete them.
GRANT SELECT, INSERT, UPDATE ON counting.release_check TO rebuild_job;
GRANT SELECT ON counting.participant_ordinal, counting.participant_attribute,
                counting.study_baseline, counting.group_range, counting.event,
                counting.term_closure, counting.term_member, counting.release_check TO api_counting;
GRANT SELECT, INSERT, DELETE ON counting.cohort, counting.cohort_member, counting.cohort_evidence TO api_counting;
-- Unauthenticated and signed-in summaries. Posting lists and attributes only: no events,
-- no participant_id, no cohorts. Exact counts leave this role only through the disclosure layer.
GRANT SELECT ON counting.participant_attribute, counting.group_range,
                counting.term_closure, counting.term_member TO api_public;
ALTER ROLE api_public SET statement_timeout = '2s';
-- Provisioning reads in-scope members only.
GRANT SELECT ON counting.cohort, counting.cohort_member_in_scope, counting.participant_ordinal TO provisioning;
-- Roles query the parent tables, where privileges are checked, so new
-- partitions need no grants of their own. Never grant on partitions directly.
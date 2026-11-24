-- =====================================================================
-- bdc_dp_hm_index: schema hm. Participant-level; restricted.
--   hm : participant-level projection of BDC-HM, rebuilt per release:
--        participants, events, posting lists, release checks
-- Cohorts are not stored: a stage 5 request materializes its cohort in a temporary
-- table for one transaction (see the grants for api_counting). Persisted cohorts are
-- a future addition, in future/cohort_hm_index.sql.
-- Release-scoped tables in hm are partitioned by release, so retiring a
-- release is a DETACH and DROP of its partitions. The exception is
-- release_check: it is the permanent record of what was verified before
-- each release was served, so it is never partitioned or deleted from.
-- Columns must match hm_index.yaml (LinkML); run
-- python schema/tests/check_schema.py after changing either (CI runs it too).
-- Partitioning, indexes, functions and grants live only here, because the LinkML SQL
-- generator cannot express them. What the counts mean: schema/db/README.md.
-- Deployed as deploy_migrations acting as bdc_dp_owner, which owns every object;
-- grants are at the end and are safe to re-run.
--
-- PostgreSQL 10 variant of schema/db/hm_index.sql, which setup-db runs on servers older
-- than 11. PostgreSQL 10 cannot put a PRIMARY KEY, UNIQUE constraint or index on a
-- partitioned table, so here the partitioned parents carry only columns (key columns
-- NOT NULL, as a primary key would make them) and CHECKs, and
-- hm.create_release_partitions adds the keys and indexes to each partition it creates.
-- Every key includes release, the partition key, and each partition holds one release,
-- so a key per partition enforces exactly what the parent's key does. Everything else
-- is unchanged; keep it in step with schema/db/hm_index.sql.
-- =====================================================================

CREATE SCHEMA hm;
COMMENT ON SCHEMA hm IS
  'Participant-level projection of BDC-HM, rebuilt per release: participants, events, posting lists. Restricted.';

-- ---------------------------------------------------------------------
-- hm tables
-- ---------------------------------------------------------------------

CREATE TABLE hm.participant_ordinal (        -- numbered in consent-group order
  release            text    NOT NULL,
  ordinal            integer NOT NULL,
  participant_id     text NOT NULL,
  research_study_id  text NOT NULL,
  consent_group      text NOT NULL
  -- per partition: PRIMARY KEY (release, ordinal), UNIQUE (release, participant_id)
) PARTITION BY LIST (release);

CREATE TABLE hm.participant_attribute (
  release               text    NOT NULL,
  ordinal               integer NOT NULL,
  sex                   text,                       -- NULL when Demography records disagree
  ethnicity             text,
  race                  text[],                     -- union across Demography records (multivalued in BDC-HM)
  age_at_index_days     integer,                    -- unpopulated by current harmonization
  baseline_visit_id     text,
  age_at_baseline_days  integer                     -- whole years x 365
  -- per partition: PRIMARY KEY (release, ordinal); index USING gin (race)
) PARTITION BY LIST (release);

CREATE TABLE hm.study_baseline (             -- curated baseline visit per study
  release              text NOT NULL,
  research_study_id    text NOT NULL,
  baseline_visit_name  text NOT NULL
  -- per partition: PRIMARY KEY (release, research_study_id)
) PARTITION BY LIST (release);

CREATE TABLE hm.group_range (                -- each consent group's contiguous ordinals
  release            text    NOT NULL,
  consent_group      text    NOT NULL,
  research_study_id  text    NOT NULL,
  lo                 integer NOT NULL,
  hi                 integer NOT NULL,
  CHECK (lo <= hi)
  -- per partition: PRIMARY KEY (release, consent_group)
) PARTITION BY LIST (release);

CREATE TABLE hm.event (                      -- projection of BDC-HM records
  release            text    NOT NULL,
  source_record_id   text    NOT NULL,             -- BDC-HM Entity id
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
  value_numeric      numeric,                      -- value_quantity.value_decimal, else value_integer
  value_operator     text,                         -- e.g. LESS_THAN
  unit               text
  -- per partition: PRIMARY KEY (release, source_record_id); indexes on
  -- (release, concept, status, ordinal), (release, concept, value_numeric) and
  -- (release, ordinal, age_start_days)
) PARTITION BY LIST (release);

CREATE TABLE hm.term_closure (               -- copy of the release's closure, for joins here
  release     text NOT NULL,
  ancestor    text NOT NULL,
  descendant  text NOT NULL
  -- per partition: PRIMARY KEY (release, ancestor, descendant); index on (release, descendant)
) PARTITION BY LIST (release);

CREATE TABLE hm.term_member (                -- posting lists: who has each term
  release      text    NOT NULL,
  concept      text    NOT NULL,                   -- term, subtypes included
  status_rule  text    NOT NULL CHECK (status_rule IN ('present', 'present_or_historical', 'absent', 'unknown')),
  ordinal      integer NOT NULL
  -- per partition: PRIMARY KEY (release, concept, status_rule, ordinal)
) PARTITION BY LIST (release);

CREATE TABLE hm.release_check (              -- checks that must pass before activation; permanent history
  release     text,
  check_name  text CHECK (check_name IN ('participant_count', 'null_status', 'relationship_unmapped',
                                         'demography_conflict', 'baseline_coverage',
                                         'unit_inconsistent', 'unit_missing', 'quantity_value_conflict',
                                         'enum_value_invalid')),
  expected    bigint,
  actual      bigint,
  passed      boolean     NOT NULL,
  checked_at  timestamptz NOT NULL,
  PRIMARY KEY (release, check_name)
);                                                 -- not partitioned: retained after a release is retired

-- ---------------------------------------------------------------------
-- Release partition functions. The rebuild job calls
-- hm.create_release_partitions('2026.10') before loading a release and
-- hm.retire_release_partitions('2026.10') after marking it retired in
-- bdc_dp_metadata.catalog.release. Both run with the owner's rights, so
-- the rebuild job owns nothing and cannot change grants or definitions.
-- ---------------------------------------------------------------------

CREATE FUNCTION hm.release_suffix(p_release text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog
AS $$
BEGIN
  IF p_release IS NULL
   OR p_release !~ '^[0-9A-Za-z][0-9A-Za-z._-]{0,30}$' THEN
    RAISE EXCEPTION 'invalid release name: %', p_release;
  END IF;
  RETURN 'r' || regexp_replace(lower(p_release), '[^0-9a-z]', '_', 'g');
END
$$;

CREATE FUNCTION hm.create_release_partitions(p_release text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
  parent    text;
  part      text;
  bound     text;
  ddl       text;
BEGIN
  FOREACH parent IN ARRAY ARRAY['participant_ordinal', 'participant_attribute', 'study_baseline',
                                'group_range', 'event', 'term_closure', 'term_member']
  LOOP
    part := parent || '_' || hm.release_suffix(p_release);

    SELECT pg_get_expr(c.relpartbound, c.oid) INTO bound
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'hm' AND c.relname = part;

    IF bound IS NULL THEN
      EXECUTE format('CREATE TABLE hm.%I PARTITION OF hm.%I FOR VALUES IN (%L)', part, parent, p_release);
      -- The keys and indexes PostgreSQL 11+ would take from the parent. {p} is the
      -- partition's name, which release_suffix limits to [0-9a-z_], at most 63 bytes
      -- with any suffix below.
      FOREACH ddl IN ARRAY CASE parent
        WHEN 'participant_ordinal' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, ordinal)',
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_uq UNIQUE (release, participant_id)']
        WHEN 'participant_attribute' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, ordinal)',
          'CREATE INDEX {p}_race_idx ON hm.{p} USING gin (race)']
        WHEN 'study_baseline' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, research_study_id)']
        WHEN 'group_range' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, consent_group)']
        WHEN 'event' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, source_record_id)',
          'CREATE INDEX {p}_concept_idx ON hm.{p} (release, concept, status, ordinal)',
          'CREATE INDEX {p}_value_idx ON hm.{p} (release, concept, value_numeric)',
          'CREATE INDEX {p}_timeline_idx ON hm.{p} (release, ordinal, age_start_days)']
        WHEN 'term_closure' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, ancestor, descendant)',
          'CREATE INDEX {p}_descendant_idx ON hm.{p} (release, descendant)']
        WHEN 'term_member' THEN ARRAY[
          'ALTER TABLE hm.{p} ADD CONSTRAINT {p}_pkey PRIMARY KEY (release, concept, status_rule, ordinal)']
      END
      LOOP
        EXECUTE replace(ddl, '{p}', part);
      END LOOP;
    ELSIF bound <> format('FOR VALUES IN (%L)', p_release) THEN
      RAISE EXCEPTION 'partition hm.% already exists for a different release (%)', part, bound;
    END IF;
  END LOOP;
END
$$;

-- Retiring a release removes its participant-level data. The caller must first
-- mark the release retired in bdc_dp_metadata.catalog.release; this database
-- cannot check that.
CREATE FUNCTION hm.retire_release_partitions(p_release text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
  parent    text;
  part      text;
  bound     text;
  part_oid  oid;
BEGIN
  FOREACH parent IN ARRAY ARRAY[
    'participant_ordinal', 'participant_attribute', 'study_baseline',
    'group_range', 'event', 'term_closure', 'term_member'
  ]
  LOOP
    part := parent || '_' || hm.release_suffix(p_release);

    SELECT c.oid, pg_get_expr(c.relpartbound, c.oid)
      INTO part_oid, bound
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'hm' AND c.relname = part;

    IF NOT FOUND THEN
      CONTINUE;
    END IF;

    -- Check release identity and parent before deleting the partition.
    IF bound IS DISTINCT FROM format('FOR VALUES IN (%L)', p_release)
       OR NOT EXISTS (
         SELECT 1
         FROM pg_inherits
         WHERE inhrelid = part_oid
           AND inhparent = to_regclass(format('hm.%I', parent))
       ) THEN
      RAISE EXCEPTION
        'partition hm.% does not belong to release % and parent hm.%',
        part, p_release, parent;
    END IF;

    EXECUTE format(
      'ALTER TABLE hm.%I DETACH PARTITION hm.%I', parent, part
    );
    EXECUTE format('DROP TABLE hm.%I', part);
  END LOOP;
END
$$;

-- Functions are executable by PUBLIC by default; remove that.
REVOKE ALL ON FUNCTION hm.release_suffix(text)            FROM PUBLIC;
REVOKE ALL ON FUNCTION hm.create_release_partitions(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION hm.retire_release_partitions(text) FROM PUBLIC;

-- =====================================================================
-- ROLES AND GRANTS
-- Roles are created once per server by an Entra administrator (see README.md); each maps to
-- one Entra managed identity. Re-run this section after every schema change: grants limited
-- to specific objects are not covered by the defaults. Grants for provisioning are a future
-- addition, in future/provisioning_hm_index.sql. Grant on partitioned
-- parents only: queries go through the parent, where privileges are checked, so partitions
-- created later by hm.create_release_partitions need no grants of their own.
-- =====================================================================

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA hm FROM PUBLIC;

-- Schema usage and default privileges for tables added later
GRANT USAGE ON SCHEMA hm     TO rebuild_job, api_counting, api_public;

ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner IN SCHEMA hm
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rebuild_job;
ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner IN SCHEMA hm
  GRANT SELECT ON TABLES TO api_counting;
-- Functions the owner creates later are not executable by PUBLIC either. This must be the
-- global default: a per-schema default can only add privileges, never remove them.
ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- api_public gets no defaults: its access is granted object by object.

-- hm: written only by the rebuild job
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA hm TO rebuild_job;
-- release_check is history: the rebuild job records and re-runs checks but cannot delete them
REVOKE DELETE ON hm.release_check FROM rebuild_job;
GRANT EXECUTE ON FUNCTION hm.create_release_partitions(text), hm.retire_release_partitions(text)
  TO rebuild_job;

-- hm: the counting API reads everything
GRANT SELECT ON ALL TABLES IN SCHEMA hm TO api_counting;

-- hm: public and pre-stage-5 summaries. api_public gets no SELECT on any hm table: every
-- table here is participant-level and keyed by ordinal, so posting lists (term_member),
-- attributes (participant_attribute) and consent-group ranges (group_range) together give
-- every participant's study, consent group, demographics and concepts. It will get EXECUTE
-- only on SECURITY DEFINER functions that return exactly the public tier of the disclosure
-- policy, already shaped, so this role never receives a participant row or an exact count:
--   * root and facet totals, each the exact union over every study in scope (including
--     studies below min_count), then obfuscated once; never a sum of per-study values
--   * studies at or above min_count, named without counts, and suppressed_study_count for
--     the rest (zero and small counts are indistinguishable)
--   * no contributions, no record_count, no records
-- Noise is applied inside the function: it is seeded by release, consent group and the
-- matching participant set, which only the database can see. min_count, noise scale and
-- policy_version come from the policy loaded into the database, never from the caller, and
-- every result carries the policy_version it was shaped under. Those functions arrive with
-- the counting query layer; until then api_public reads nothing here. The REVOKE also
-- removes the table grants from databases deployed with earlier versions of this file.
REVOKE ALL ON ALL TABLES IN SCHEMA hm FROM api_public;

-- Stage 5 (signed-in breakdowns and approved counts) materializes the refined cohort for
-- one request only: api_counting evaluates the cohort expression once into
--   CREATE TEMPORARY TABLE ... ON COMMIT DROP
-- computes every facet and study contribution of the response from it in the same
-- transaction, and commits, which drops it. Nothing about the cohort outlives the request,
-- so no participant list is stored per researcher and nothing needs to expire. Temporary
-- tables are private to the transaction's session and are not written to the WAL. Do not
-- use session-scoped temporary tables: connections are pooled and shared between users.
-- Exact scope is computed per request from the caller's current entitlements and
-- catalog.consent_group_current. The bootstrap revokes every database privilege from
-- PUBLIC, including TEMPORARY, so it is granted here.
GRANT TEMPORARY ON DATABASE bdc_dp_hm_index TO api_counting;

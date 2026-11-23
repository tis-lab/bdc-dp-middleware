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
-- Re-runnable copy of schema/db/hm_index.sql; keep the two in step (check_schema.py
-- reads only the copy in schema/db). The whole script is safe to run again against
-- an existing database:
--   * It runs in one transaction, so a failure leaves the database as it was. It
--     takes an advisory lock so two deployments cannot interleave, and lock_timeout
--     makes it fail rather than wait indefinitely behind live queries.
--   * The schema, tables and indexes are created only when missing. A column missing
--     from an existing table is added (looked up in pg_catalog first, so a table that
--     already has every column is not locked). A NOT NULL column can only be added
--     while the table is empty. Then each table and index is compared with this file
--     through pg_catalog (columns, types, NOT NULL, defaults, identity, partition key,
--     constraints, index definitions), and the script stops with the differences listed; it never drops
--     or changes an existing column, constraint or index.
--     Expected definitions are written as PostgreSQL 16 prints them.
--   * Functions are CREATE OR REPLACE, so their bodies always match this file.
--   * Roles the grants name are created NOLOGIN when missing.
-- Run it with psql -v ON_ERROR_STOP=1 -f. setup-db (hm) runs the plain copy, not this file;
-- see README-SETUP-DB.md.
-- =====================================================================

BEGIN;

SET LOCAL lock_timeout = '30s';
SET LOCAL search_path = pg_catalog;

-- Serialize concurrent deployments of this script; released at COMMIT or ROLLBACK.
SELECT pg_advisory_xact_lock(hashtext('bdc_dp_hm_index:hm_index.sql'));

CREATE SCHEMA IF NOT EXISTS hm;
COMMENT ON SCHEMA hm IS
  'Participant-level projection of BDC-HM, rebuilt per release: participants, events, posting lists. Restricted.';

-- ---------------------------------------------------------------------
-- Deployment checks. Both functions exist only inside this transaction: they are
-- dropped before COMMIT, so no other session ever sees them. The same two functions
-- are in hm_index.sql and metadata.sql; keep them identical.
--   hm.deploy_add_columns_and_check: adds each given column whose name the table
--     lacks, then checks the table has exactly those columns, the partition key (NULL =
--     not partitioned) and the constraint definitions (pg_get_constraintdef, compared
--     with whitespace collapsed; NOT NULL is checked per column). A column is written
--     'name type[ NOT NULL][ DEFAULT expr][ GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY]',
--     as pg_catalog prints it, which is also valid ADD COLUMN text.
--   hm.deploy_check_index: the index exists with the given pg_get_indexdef.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION hm.deploy_add_columns_and_check(
  p_schema text, p_table text, p_partition_key text, p_columns text[], p_constraints text[])
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
  qualified  text := p_schema || '.' || p_table;
  rel        oid;
  kind       "char";
  partkey    text;
  item       text;
  problems   text[] := '{}';
BEGIN
  SELECT c.oid, c.relkind INTO rel, kind
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = p_schema AND c.relname = p_table;

  IF NOT FOUND THEN
    RAISE EXCEPTION '% does not exist after CREATE TABLE IF NOT EXISTS', qualified;
  END IF;

  -- Add columns whose name is absent. A column present under a different type,
  -- nullability, default or identity is left alone and reported below. On a
  -- partitioned table the column is added to every partition too.
  IF kind IN ('r', 'p') THEN
    FOREACH item IN ARRAY p_columns LOOP
      IF NOT EXISTS (
        SELECT 1 FROM pg_attribute a
        WHERE a.attrelid = rel AND a.attname = split_part(item, ' ', 1)
          AND a.attnum > 0 AND NOT a.attisdropped
      ) THEN
        BEGIN
          EXECUTE format('ALTER TABLE %I.%I ADD COLUMN %s', p_schema, p_table, item);
          RAISE NOTICE 'added column to %: %', qualified, item;
        EXCEPTION WHEN not_null_violation THEN
          RAISE EXCEPTION 'cannot add column % to %: the table has rows', item, qualified
            USING HINT = 'Add the column as nullable, backfill it, then set NOT NULL.';
        END;
      END IF;
    END LOOP;
  END IF;

  IF p_partition_key IS NULL THEN
    IF kind <> 'r' THEN
      problems := problems || format('expected an unpartitioned table, found relkind %s', kind);
    END IF;
  ELSIF kind <> 'p' THEN
    problems := problems || format('expected a table PARTITION BY %s, found relkind %s',
                                   p_partition_key, kind);
  ELSE
    partkey := pg_get_partkeydef(rel);
    IF partkey IS DISTINCT FROM p_partition_key THEN
      problems := problems || format('expected PARTITION BY %s, found PARTITION BY %s',
                                     p_partition_key, partkey);
    END IF;
  END IF;

  FOR item IN
    WITH actual AS (
      SELECT a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
             || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END
             || coalesce(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), '')
             || CASE a.attidentity WHEN 'a' THEN ' GENERATED ALWAYS AS IDENTITY'
                                   WHEN 'd' THEN ' GENERATED BY DEFAULT AS IDENTITY'
                                   ELSE '' END AS spec
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
      WHERE a.attrelid = rel AND a.attnum > 0 AND NOT a.attisdropped
    )
    SELECT 'missing or different column: ' || e FROM unnest(p_columns) e
      WHERE e NOT IN (SELECT spec FROM actual)
    UNION ALL
    SELECT 'unexpected column: ' || spec FROM actual
      WHERE spec <> ALL (p_columns)
    ORDER BY 1
  LOOP
    problems := problems || item;
  END LOOP;

  FOR item IN
    WITH actual AS (
      SELECT regexp_replace(pg_get_constraintdef(con.oid), '\s+', ' ', 'g') AS def
      FROM pg_constraint con
      WHERE con.conrelid = rel AND con.contype IN ('p', 'u', 'c', 'f', 'x')
    ), expected AS (
      SELECT regexp_replace(e, '\s+', ' ', 'g') AS def FROM unnest(p_constraints) e
    )
    SELECT 'missing constraint: ' || def FROM expected
      WHERE def NOT IN (SELECT def FROM actual)
    UNION ALL
    SELECT 'unexpected constraint: ' || def FROM actual
      WHERE def NOT IN (SELECT def FROM expected)
    ORDER BY 1
  LOOP
    problems := problems || item;
  END LOOP;

  IF cardinality(problems) > 0 THEN
    RAISE EXCEPTION '% already exists but differs from this script:%', qualified,
      E'\n  ' || array_to_string(problems, E'\n  ')
      USING HINT = 'Migrate the table explicitly; this script only adds missing columns.';
  END IF;
END
$$;

CREATE OR REPLACE FUNCTION hm.deploy_check_index(p_schema text, p_index text, p_definition text)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
  kind  "char";
  def   text;
BEGIN
  SELECT c.relkind, pg_get_indexdef(c.oid) INTO kind, def
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = p_schema AND c.relname = p_index;

  IF NOT FOUND THEN
    RAISE EXCEPTION '%.% does not exist after CREATE INDEX IF NOT EXISTS', p_schema, p_index;
  ELSIF kind NOT IN ('i', 'I') THEN
    RAISE EXCEPTION '%.% exists but is not an index (relkind %)', p_schema, p_index, kind;
  ELSIF def IS DISTINCT FROM p_definition THEN
    RAISE EXCEPTION E'index %.% already exists but differs from this script:\n  expected: %\n  found:    %',
      p_schema, p_index, p_definition, def
      USING HINT = 'Drop or rename the existing index, then re-run.';
  END IF;
END
$$;

-- ---------------------------------------------------------------------
-- hm tables
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS hm.participant_ordinal (        -- numbered in consent-group order
  release            text,
  ordinal            integer,
  participant_id     text NOT NULL,
  research_study_id  text NOT NULL,
  consent_group      text NOT NULL,
  PRIMARY KEY (release, ordinal),
  UNIQUE (release, participant_id)
) PARTITION BY LIST (release);
SELECT hm.deploy_add_columns_and_check('hm', 'participant_ordinal', 'LIST (release)',
  ARRAY['release text NOT NULL', 'ordinal integer NOT NULL', 'participant_id text NOT NULL',
        'research_study_id text NOT NULL', 'consent_group text NOT NULL'],
  ARRAY['PRIMARY KEY (release, ordinal)', 'UNIQUE (release, participant_id)']);

CREATE TABLE IF NOT EXISTS hm.participant_attribute (
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
SELECT hm.deploy_add_columns_and_check('hm', 'participant_attribute', 'LIST (release)',
  ARRAY['release text NOT NULL', 'ordinal integer NOT NULL', 'sex text', 'ethnicity text',
        'race text[]', 'age_at_index_days integer', 'baseline_visit_id text',
        'age_at_baseline_days integer'],
  ARRAY['PRIMARY KEY (release, ordinal)']);
CREATE INDEX IF NOT EXISTS participant_attribute_race_idx ON hm.participant_attribute USING gin (race);
SELECT hm.deploy_check_index('hm', 'participant_attribute_race_idx',
  'CREATE INDEX participant_attribute_race_idx ON ONLY hm.participant_attribute USING gin (race)');

CREATE TABLE IF NOT EXISTS hm.study_baseline (             -- curated baseline visit per study
  release              text,
  research_study_id    text,
  baseline_visit_name  text NOT NULL,
  PRIMARY KEY (release, research_study_id)
) PARTITION BY LIST (release);
SELECT hm.deploy_add_columns_and_check('hm', 'study_baseline', 'LIST (release)',
  ARRAY['release text NOT NULL', 'research_study_id text NOT NULL',
        'baseline_visit_name text NOT NULL'],
  ARRAY['PRIMARY KEY (release, research_study_id)']);

CREATE TABLE IF NOT EXISTS hm.group_range (                -- each consent group's contiguous ordinals
  release            text,
  consent_group      text,
  research_study_id  text    NOT NULL,
  lo                 integer NOT NULL,
  hi                 integer NOT NULL,
  PRIMARY KEY (release, consent_group),
  CHECK (lo <= hi)
) PARTITION BY LIST (release);
SELECT hm.deploy_add_columns_and_check('hm', 'group_range', 'LIST (release)',
  ARRAY['release text NOT NULL', 'consent_group text NOT NULL',
        'research_study_id text NOT NULL', 'lo integer NOT NULL', 'hi integer NOT NULL'],
  ARRAY['PRIMARY KEY (release, consent_group)', 'CHECK ((lo <= hi))']);

CREATE TABLE IF NOT EXISTS hm.event (                      -- projection of BDC-HM records
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
  value_numeric      numeric,                      -- value_quantity.value_decimal, else value_integer
  value_operator     text,                         -- e.g. LESS_THAN
  unit               text,
  PRIMARY KEY (release, source_record_id)
) PARTITION BY LIST (release);
SELECT hm.deploy_add_columns_and_check('hm', 'event', 'LIST (release)',
  ARRAY['release text NOT NULL', 'source_record_id text NOT NULL',
        'source_class text NOT NULL', 'source_file text NOT NULL', 'record_provenance text',
        'ordinal integer NOT NULL', 'visit_id text', 'age_start_days integer',
        'age_end_days integer', 'age_start_slot text NOT NULL', 'concept text NOT NULL',
        'status text', 'relationship text', 'value_numeric numeric', 'value_operator text',
        'unit text'],
  ARRAY['PRIMARY KEY (release, source_record_id)']);
CREATE INDEX IF NOT EXISTS event_concept_idx  ON hm.event (release, concept, status, ordinal);
CREATE INDEX IF NOT EXISTS event_value_idx    ON hm.event (release, concept, value_numeric);
CREATE INDEX IF NOT EXISTS event_timeline_idx ON hm.event (release, ordinal, age_start_days);
SELECT hm.deploy_check_index('hm', 'event_concept_idx',
  'CREATE INDEX event_concept_idx ON ONLY hm.event USING btree (release, concept, status, ordinal)');
SELECT hm.deploy_check_index('hm', 'event_value_idx',
  'CREATE INDEX event_value_idx ON ONLY hm.event USING btree (release, concept, value_numeric)');
SELECT hm.deploy_check_index('hm', 'event_timeline_idx',
  'CREATE INDEX event_timeline_idx ON ONLY hm.event USING btree (release, ordinal, age_start_days)');

CREATE TABLE IF NOT EXISTS hm.term_closure (               -- copy of the release's closure, for joins here
  release     text,
  ancestor    text,
  descendant  text,
  PRIMARY KEY (release, ancestor, descendant)
) PARTITION BY LIST (release);
SELECT hm.deploy_add_columns_and_check('hm', 'term_closure', 'LIST (release)',
  ARRAY['release text NOT NULL', 'ancestor text NOT NULL', 'descendant text NOT NULL'],
  ARRAY['PRIMARY KEY (release, ancestor, descendant)']);
CREATE INDEX IF NOT EXISTS hm_closure_descendant_idx ON hm.term_closure (release, descendant);
SELECT hm.deploy_check_index('hm', 'hm_closure_descendant_idx',
  'CREATE INDEX hm_closure_descendant_idx ON ONLY hm.term_closure USING btree (release, descendant)');

CREATE TABLE IF NOT EXISTS hm.term_member (                -- posting lists: who has each term
  release      text,
  concept      text,                               -- term, subtypes included
  status_rule  text CHECK (status_rule IN ('present', 'present_or_historical', 'absent', 'unknown')),
  ordinal      integer,
  PRIMARY KEY (release, concept, status_rule, ordinal)
) PARTITION BY LIST (release);
SELECT hm.deploy_add_columns_and_check('hm', 'term_member', 'LIST (release)',
  ARRAY['release text NOT NULL', 'concept text NOT NULL', 'status_rule text NOT NULL',
        'ordinal integer NOT NULL'],
  ARRAY['PRIMARY KEY (release, concept, status_rule, ordinal)',
        'CHECK ((status_rule = ANY (ARRAY[''present''::text, ''present_or_historical''::text, '
          || '''absent''::text, ''unknown''::text])))']);

CREATE TABLE IF NOT EXISTS hm.release_check (              -- checks that must pass before activation; permanent history
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
SELECT hm.deploy_add_columns_and_check('hm', 'release_check', NULL,
  ARRAY['release text NOT NULL', 'check_name text NOT NULL', 'expected bigint',
        'actual bigint', 'passed boolean NOT NULL',
        'checked_at timestamp with time zone NOT NULL'],
  ARRAY['PRIMARY KEY (release, check_name)',
        'CHECK ((check_name = ANY (ARRAY[''participant_count''::text, ''null_status''::text, '
          || '''relationship_unmapped''::text, ''demography_conflict''::text, '
          || '''baseline_coverage''::text, ''unit_inconsistent''::text, ''unit_missing''::text, '
          || '''quantity_value_conflict''::text, ''enum_value_invalid''::text])))']);

DROP FUNCTION hm.deploy_add_columns_and_check(text, text, text, text[], text[]);
DROP FUNCTION hm.deploy_check_index(text, text, text);

-- ---------------------------------------------------------------------
-- Release partition functions. The rebuild job calls
-- hm.create_release_partitions('2026.10') before loading a release and
-- hm.retire_release_partitions('2026.10') after marking it retired in
-- bdc_dp_metadata.catalog.release. Both run with the owner's rights, so
-- the rebuild job owns nothing and cannot change grants or definitions.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION hm.release_suffix(p_release text)
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

CREATE OR REPLACE FUNCTION hm.create_release_partitions(p_release text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
  parent    text;
  part      text;
  bound     text;
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
    ELSIF bound <> format('FOR VALUES IN (%L)', p_release) THEN
      RAISE EXCEPTION 'partition hm.% already exists for a different release (%)', part, bound;
    END IF;
  END LOOP;
END
$$;

-- Retiring a release removes its participant-level data. The caller must first
-- mark the release retired in bdc_dp_metadata.catalog.release; this database
-- cannot check that.
CREATE OR REPLACE FUNCTION hm.retire_release_partitions(p_release text)
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

-- A role that does not exist yet is created NOLOGIN as a placeholder so the grants below
-- succeed; an administrator still has to map it to its Entra identity. Needs CREATEROLE,
-- but only when a role is missing.
DO $$
DECLARE
  r  text;
BEGIN
  FOREACH r IN ARRAY ARRAY['bdc_dp_owner', 'rebuild_job', 'api_counting', 'api_public'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = r) THEN
      BEGIN
        EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        RAISE NOTICE 'created missing role % as NOLOGIN; map it to its Entra identity', r;
      EXCEPTION WHEN duplicate_object THEN
        NULL;                                      -- created concurrently by another session
      END;
    END IF;
  END LOOP;
END
$$;

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
-- The database is named by current_database() rather than bdc_dp_hm_index, so the script
-- also runs against a database with another name.
DO $$
BEGIN
  EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO api_counting', current_database());
END
$$;

COMMIT;

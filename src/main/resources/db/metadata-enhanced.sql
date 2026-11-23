-- =====================================================================
-- bdc_dp_metadata: schemas catalog and access. No participant-level data.
--   catalog : releases, terms, studies, curated consent meaning and eligibility
--   access  : accounts, entitlements and the query ledger
-- Columns must match metadata.yaml (LinkML); run python schema/tests/check_schema.py
-- after changing either (CI runs it too). Deployed as deploy_migrations acting as
-- bdc_dp_owner, which owns every object; grants are at the end and are safe to re-run.
--
-- Re-runnable copy of schema/db/metadata.sql; keep the two in step (check_schema.py
-- reads only the copy in schema/db). The whole script is safe to run again against
-- an existing database:
--   * It runs in one transaction, so a failure leaves the database as it was. It
--     takes an advisory lock so two deployments cannot interleave, and lock_timeout
--     makes it fail rather than wait indefinitely behind live queries.
--   * The schemas, tables and indexes are created only when missing. A column missing
--     from an existing table is added (looked up in pg_catalog first, so a table that
--     already has every column is not locked). A NOT NULL column without a default can
--     only be added while the table is empty. Then each table and index is compared
--     with this file through pg_catalog (columns, types, NOT NULL, defaults, identity,
--     constraints, foreign keys, index definitions), and the script stops with the
--     differences listed; it never drops or changes an existing column, constraint or
--     index. Expected definitions are written as PostgreSQL 16 prints them.
--   * The view is CREATE OR REPLACE, so its definition always matches this file.
--   * Roles the grants name are created NOLOGIN when missing.
-- Run it with psql -v ON_ERROR_STOP=1 -f. setup-db (metadata) runs the plain copy, not this file;
-- see README-SETUP-DB.md.
-- =====================================================================

BEGIN;

SET LOCAL lock_timeout = '30s';
SET LOCAL search_path = pg_catalog;

-- Serialize concurrent deployments of this script; released at COMMIT or ROLLBACK.
SELECT pg_advisory_xact_lock(hashtext('bdc_dp_metadata:metadata.sql'));

CREATE SCHEMA IF NOT EXISTS catalog;
COMMENT ON SCHEMA catalog IS
  'Releases, terms, studies, curated consent meaning and eligibility. No participant-level data.';

CREATE SCHEMA IF NOT EXISTS access;
COMMENT ON SCHEMA access IS
  'Accounts, entitlements and the query ledger.';

-- ---------------------------------------------------------------------
-- Deployment checks. Both functions exist only inside this transaction: they are
-- dropped before COMMIT, so no other session ever sees them. The same two functions
-- are in hm_index.sql and metadata.sql; keep them identical.
--   catalog.deploy_add_columns_and_check: adds each given column whose name the table
--     lacks, then checks the table has exactly those columns, the partition key (NULL =
--     not partitioned) and the constraint definitions (pg_get_constraintdef, compared
--     with whitespace collapsed; NOT NULL is checked per column). A column is written
--     'name type[ NOT NULL][ DEFAULT expr][ GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY]',
--     as pg_catalog prints it, which is also valid ADD COLUMN text.
--   catalog.deploy_check_index: the index exists with the given pg_get_indexdef.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION catalog.deploy_add_columns_and_check(
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

CREATE OR REPLACE FUNCTION catalog.deploy_check_index(p_schema text, p_index text, p_definition text)
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
-- catalog: releases, terms, studies, curated consent meaning
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS catalog.release (
  release         text PRIMARY KEY,                -- e.g. '2026.10'
  delta_version   bigint      NOT NULL,            -- pinned version of the governed Delta tables
  schema_version  text        NOT NULL,            -- bdchm_governed schema version
  kg_version      text        NOT NULL,            -- pinned BDC-KG version for terms, closure and mappings
  status          text        NOT NULL CHECK (status IN ('loading', 'active', 'retired')),
  loaded_at       timestamptz NOT NULL
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'release', NULL,
  ARRAY['release text NOT NULL', 'delta_version bigint NOT NULL', 'schema_version text NOT NULL',
        'kg_version text NOT NULL', 'status text NOT NULL',
        'loaded_at timestamp with time zone NOT NULL'],
  ARRAY['PRIMARY KEY (release)',
        'CHECK ((status = ANY (ARRAY[''loading''::text, ''active''::text, ''retired''::text])))']);

CREATE TABLE IF NOT EXISTS catalog.term (
  release           text REFERENCES catalog.release,
  curie             text,                          -- 'MONDO:0005148'
  label             text   NOT NULL,
  synonyms          text[] NOT NULL DEFAULT '{}',
  ontology          text   NOT NULL,               -- MONDO, HP, LOINC, ...
  ontology_version  text   NOT NULL,
  bdchm_class       text   NOT NULL,               -- Condition, Procedure, DrugExposure, MeasurementObservation
  summary_class     text   NOT NULL,               -- ConditionSummary, ...
  replaced_by       text,                          -- set when the term is obsolete in this release
  PRIMARY KEY (release, curie)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'term', NULL,
  ARRAY['release text NOT NULL', 'curie text NOT NULL', 'label text NOT NULL',
        'synonyms text[] NOT NULL DEFAULT ''{}''::text[]', 'ontology text NOT NULL',
        'ontology_version text NOT NULL', 'bdchm_class text NOT NULL',
        'summary_class text NOT NULL', 'replaced_by text'],
  ARRAY['PRIMARY KEY (release, curie)',
        'FOREIGN KEY (release) REFERENCES catalog.release(release)']);

CREATE TABLE IF NOT EXISTS catalog.term_closure (                -- each term with all its descendants (and itself)
  release     text REFERENCES catalog.release,
  ancestor    text,
  descendant  text,
  PRIMARY KEY (release, ancestor, descendant)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'term_closure', NULL,
  ARRAY['release text NOT NULL', 'ancestor text NOT NULL', 'descendant text NOT NULL'],
  ARRAY['PRIMARY KEY (release, ancestor, descendant)',
        'FOREIGN KEY (release) REFERENCES catalog.release(release)']);
CREATE INDEX IF NOT EXISTS term_closure_descendant_idx ON catalog.term_closure (release, descendant);
SELECT catalog.deploy_check_index('catalog', 'term_closure_descendant_idx',
  'CREATE INDEX term_closure_descendant_idx ON catalog.term_closure USING btree (release, descendant)');

CREATE TABLE IF NOT EXISTS catalog.study (
  release            text REFERENCES catalog.release,
  research_study_id  text,
  accession          text NOT NULL,              -- 'phs001234.v3.p1'
  title              text NOT NULL,
  description        text,
  PRIMARY KEY (release, research_study_id)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'study', NULL,
  ARRAY['release text NOT NULL', 'research_study_id text NOT NULL', 'accession text NOT NULL',
        'title text NOT NULL', 'description text'],
  ARRAY['PRIMARY KEY (release, research_study_id)',
        'FOREIGN KEY (release) REFERENCES catalog.release(release)']);

CREATE TABLE IF NOT EXISTS catalog.variable_mapping (             -- from the release's BDC-KG version
  release                   text REFERENCES catalog.release,
  mapping_id                text,                  -- the mapping's identifier in the BDC-KG
  research_study_id         text NOT NULL,         -- ResearchStudy.id
  bdchm_class               text NOT NULL,         -- e.g. Condition, MeasurementObservation
  bdchm_slot                text NOT NULL,         -- e.g. condition_concept, value_quantity
  concept                   text,                  -- term the mapping produces, when it produces one
  dbgap_study_accession     text NOT NULL CHECK (dbgap_study_accession ~ '^phs\d{6}\.v\d+\.p\d+$'),
  dbgap_dataset_accession   text NOT NULL CHECK (dbgap_dataset_accession ~ '^pht\d{6}\.v\d+\.p\d+$'),
  dbgap_variable_accession  text NOT NULL CHECK (dbgap_variable_accession ~ '^phv\d{8}\.v\d+\.p\d+$'),
  mapping_note              text,                  -- how the source values were transformed
  PRIMARY KEY (release, mapping_id)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'variable_mapping', NULL,
  ARRAY['release text NOT NULL', 'mapping_id text NOT NULL', 'research_study_id text NOT NULL',
        'bdchm_class text NOT NULL', 'bdchm_slot text NOT NULL', 'concept text',
        'dbgap_study_accession text NOT NULL', 'dbgap_dataset_accession text NOT NULL',
        'dbgap_variable_accession text NOT NULL', 'mapping_note text'],
  ARRAY['PRIMARY KEY (release, mapping_id)',
        'FOREIGN KEY (release) REFERENCES catalog.release(release)',
        'CHECK ((dbgap_study_accession ~ ''^phs\d{6}\.v\d+\.p\d+$''::text))',
        'CHECK ((dbgap_dataset_accession ~ ''^pht\d{6}\.v\d+\.p\d+$''::text))',
        'CHECK ((dbgap_variable_accession ~ ''^phv\d{8}\.v\d+\.p\d+$''::text))']);
CREATE INDEX IF NOT EXISTS variable_mapping_study_idx ON catalog.variable_mapping (release, research_study_id, concept);
SELECT catalog.deploy_check_index('catalog', 'variable_mapping_study_idx',
  'CREATE INDEX variable_mapping_study_idx ON catalog.variable_mapping USING btree (release, research_study_id, concept)');

CREATE TABLE IF NOT EXISTS catalog.consent_group (               -- curated ConsentGroup records, versioned
  consent_group_id         text    CHECK (consent_group_id ~ '^phs\d{6}\.c\d+$'),   -- 'phs001234.c1'
  version                  integer,
  study_accession          text    NOT NULL CHECK (study_accession ~ '^phs\d{6}$'),   -- 'phs001234'
  dbgap_abbreviation       text    NOT NULL,       -- 'HMB-IRB-NPU'
  primary_data_use         text    NOT NULL CHECK (primary_data_use IN ('NRES', 'GRU', 'HMB', 'DS', 'POA')),
  modifiers                text[]  NOT NULL DEFAULT '{}'
                           CHECK (modifiers <@ ARRAY['IRB', 'NPUNCU', 'PUB', 'COL', 'GSO', 'NMDS', 'GS', 'MOR']),
  disease_restriction      text[]  NOT NULL DEFAULT '{}',   -- MONDO terms, required for DS
  source_consent_text      text    NOT NULL,
  curated_by               text    NOT NULL,
  reviewed_by              text    NOT NULL,
  curated_on               date    NOT NULL,
  PRIMARY KEY (consent_group_id, version),
  CHECK (primary_data_use <> 'DS' OR cardinality(disease_restriction) > 0),
  CHECK (starts_with(consent_group_id, study_accession || '.c'))
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'consent_group', NULL,
  ARRAY['consent_group_id text NOT NULL', 'version integer NOT NULL',
        'study_accession text NOT NULL', 'dbgap_abbreviation text NOT NULL',
        'primary_data_use text NOT NULL', 'modifiers text[] NOT NULL DEFAULT ''{}''::text[]',
        'disease_restriction text[] NOT NULL DEFAULT ''{}''::text[]',
        'source_consent_text text NOT NULL', 'curated_by text NOT NULL',
        'reviewed_by text NOT NULL', 'curated_on date NOT NULL'],
  ARRAY['PRIMARY KEY (consent_group_id, version)',
        'CHECK ((consent_group_id ~ ''^phs\d{6}\.c\d+$''::text))',
        'CHECK ((study_accession ~ ''^phs\d{6}$''::text))',
        'CHECK ((primary_data_use = ANY (ARRAY[''NRES''::text, ''GRU''::text, ''HMB''::text, '
          || '''DS''::text, ''POA''::text])))',
        'CHECK ((modifiers <@ ARRAY[''IRB''::text, ''NPUNCU''::text, ''PUB''::text, ''COL''::text, '
          || '''GSO''::text, ''NMDS''::text, ''GS''::text, ''MOR''::text]))',
        'CHECK (((primary_data_use <> ''DS''::text) OR (cardinality(disease_restriction) > 0)))',
        'CHECK (starts_with(consent_group_id, (study_accession || ''.c''::text)))']);

CREATE TABLE IF NOT EXISTS catalog.consent_group_eligibility (   -- the portal's policy per consent group, versioned
  consent_group_id         text    CHECK (consent_group_id ~ '^phs\d{6}\.c\d+$'),
  version                  integer,
  consent_group_version    integer NOT NULL,       -- the consent_group version this decision was made against
  exact_count_eligible     boolean NOT NULL,
  sharing_eligible         boolean NOT NULL,
  eligibility_approved_by  text    NOT NULL,
  approved_on              date    NOT NULL,
  PRIMARY KEY (consent_group_id, version),
  FOREIGN KEY (consent_group_id, consent_group_version) REFERENCES catalog.consent_group (consent_group_id, version)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'consent_group_eligibility', NULL,
  ARRAY['consent_group_id text NOT NULL', 'version integer NOT NULL',
        'consent_group_version integer NOT NULL', 'exact_count_eligible boolean NOT NULL',
        'sharing_eligible boolean NOT NULL', 'eligibility_approved_by text NOT NULL',
        'approved_on date NOT NULL'],
  ARRAY['PRIMARY KEY (consent_group_id, version)',
        'CHECK ((consent_group_id ~ ''^phs\d{6}\.c\d+$''::text))',
        'FOREIGN KEY (consent_group_id, consent_group_version) '
          || 'REFERENCES catalog.consent_group(consent_group_id, version)']);

-- Current consent meaning with the current eligibility decision (GovernedConsentGroup). A
-- decision made against an older version of the consent meaning does not apply: the group reads
-- as ineligible until eligibility is decided again. Columns are listed rather than m.*, because
-- Postgres expands * once at creation and would silently omit columns added to consent_group later.
CREATE OR REPLACE VIEW catalog.consent_group_current AS
  WITH meaning AS (
    SELECT DISTINCT ON (consent_group_id) *
    FROM catalog.consent_group
    ORDER BY consent_group_id, version DESC
  ), decision AS (
    SELECT DISTINCT ON (consent_group_id) *
    FROM catalog.consent_group_eligibility
    ORDER BY consent_group_id, version DESC
  )
  SELECT m.consent_group_id, m.version, m.study_accession, m.dbgap_abbreviation, m.primary_data_use,
         m.modifiers, m.disease_restriction, m.source_consent_text, m.curated_by, m.reviewed_by,
         m.curated_on,
         d.version                                                        AS eligibility_version,
         coalesce(d.consent_group_version = m.version, false)             AS eligibility_is_current,
         coalesce(d.consent_group_version = m.version AND d.exact_count_eligible, false) AS exact_count_eligible,
         coalesce(d.consent_group_version = m.version AND d.sharing_eligible, false)     AS sharing_eligible,
         d.eligibility_approved_by,
         d.approved_on
  FROM meaning m
  LEFT JOIN decision d USING (consent_group_id);

CREATE TABLE IF NOT EXISTS catalog.stigmatizing_concept (        -- excluded as filters outside approved scope
  list_version  integer,
  root_curie    text,                              -- the whole subtree is excluded
  category      text NOT NULL,                     -- e.g. 'mental health'
  reviewed_by   text NOT NULL,
  PRIMARY KEY (list_version, root_curie)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'stigmatizing_concept', NULL,
  ARRAY['list_version integer NOT NULL', 'root_curie text NOT NULL', 'category text NOT NULL',
        'reviewed_by text NOT NULL'],
  ARRAY['PRIMARY KEY (list_version, root_curie)']);

CREATE TABLE IF NOT EXISTS catalog.term_availability (           -- studies with at least min_count participants per term
  release            text REFERENCES catalog.release,
  curie              text,
  research_study_id  text,
  PRIMARY KEY (release, curie, research_study_id)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'term_availability', NULL,
  ARRAY['release text NOT NULL', 'curie text NOT NULL', 'research_study_id text NOT NULL'],
  ARRAY['PRIMARY KEY (release, curie, research_study_id)',
        'FOREIGN KEY (release) REFERENCES catalog.release(release)']);

CREATE TABLE IF NOT EXISTS catalog.term_resolution_cache (       -- BDC-KG results that passed release validation
  release      text REFERENCES catalog.release,
  query_text   text,                               -- normalized search text
  candidates   jsonb       NOT NULL,
  fetched_at   timestamptz NOT NULL,
  PRIMARY KEY (release, query_text)
);
SELECT catalog.deploy_add_columns_and_check('catalog', 'term_resolution_cache', NULL,
  ARRAY['release text NOT NULL', 'query_text text NOT NULL', 'candidates jsonb NOT NULL',
        'fetched_at timestamp with time zone NOT NULL'],
  ARRAY['PRIMARY KEY (release, query_text)',
        'FOREIGN KEY (release) REFERENCES catalog.release(release)']);

-- ---------------------------------------------------------------------
-- access: accounts, entitlements, the query ledger
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS access.account (                      -- one row per NIH RAS identity
  ras_subject  text PRIMARY KEY,
  first_seen   timestamptz NOT NULL,
  last_seen    timestamptz NOT NULL
);
SELECT catalog.deploy_add_columns_and_check('access', 'account', NULL,
  ARRAY['ras_subject text NOT NULL', 'first_seen timestamp with time zone NOT NULL',
        'last_seen timestamp with time zone NOT NULL'],
  ARRAY['PRIMARY KEY (ras_subject)']);

CREATE TABLE IF NOT EXISTS access.entitlement (                  -- one row per consent group per visa
  ras_subject       text REFERENCES access.account,
  consent_group_id  text CHECK (consent_group_id ~ '^phs\d{6}\.c\d+$'),
  visa_id           text,
  issuer            text        NOT NULL,
  granted_at        timestamptz NOT NULL,
  expires_at        timestamptz NOT NULL,          -- never later than the visa's expiry
  PRIMARY KEY (ras_subject, consent_group_id, visa_id)
);
SELECT catalog.deploy_add_columns_and_check('access', 'entitlement', NULL,
  ARRAY['ras_subject text NOT NULL', 'consent_group_id text NOT NULL', 'visa_id text NOT NULL',
        'issuer text NOT NULL', 'granted_at timestamp with time zone NOT NULL',
        'expires_at timestamp with time zone NOT NULL'],
  ARRAY['PRIMARY KEY (ras_subject, consent_group_id, visa_id)',
        'FOREIGN KEY (ras_subject) REFERENCES access.account(ras_subject)',
        'CHECK ((consent_group_id ~ ''^phs\d{6}\.c\d+$''::text))']);
CREATE INDEX IF NOT EXISTS entitlement_expiry_idx ON access.entitlement (expires_at);
SELECT catalog.deploy_check_index('access', 'entitlement_expiry_idx',
  'CREATE INDEX entitlement_expiry_idx ON access.entitlement USING btree (expires_at)');

-- Public queries have no user: a public row is identified by client_key instead of an account.
-- The CHECK keeps the two apart, so a row naming an account cannot be written as public and a
-- signed-in or approved row cannot omit its account.
CREATE TABLE IF NOT EXISTS access.query_ledger (                 -- budgets, overlap checks and audit
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ras_subject      text REFERENCES access.account, -- NULL for public requests
  client_key       text,                           -- hashed network address, public tier only
  tier             text        NOT NULL CHECK (tier IN ('public', 'signed_in', 'approved')),
  stage            smallint,
  policy_version   integer     NOT NULL,           -- disclosure policy the response was shaped under
  release          text        NOT NULL,
  expression_hash  text        NOT NULL,           -- canonical expression
  result_hash      text,                           -- hash of the matched set (noise seed)
  count_mode       text        NOT NULL CHECK (count_mode IN ('OBFUSCATED', 'EXACT')),
  budget_cost      integer     NOT NULL DEFAULT 0,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (CASE WHEN tier = 'public' THEN ras_subject IS NULL AND client_key IS NOT NULL
              ELSE ras_subject IS NOT NULL AND client_key IS NULL END)
);
SELECT catalog.deploy_add_columns_and_check('access', 'query_ledger', NULL,
  ARRAY['id bigint NOT NULL GENERATED ALWAYS AS IDENTITY', 'ras_subject text', 'client_key text',
        'tier text NOT NULL', 'stage smallint', 'policy_version integer NOT NULL',
        'release text NOT NULL', 'expression_hash text NOT NULL', 'result_hash text',
        'count_mode text NOT NULL', 'budget_cost integer NOT NULL DEFAULT 0',
        'created_at timestamp with time zone NOT NULL DEFAULT now()'],
  ARRAY['PRIMARY KEY (id)',
        'FOREIGN KEY (ras_subject) REFERENCES access.account(ras_subject)',
        'CHECK ((tier = ANY (ARRAY[''public''::text, ''signed_in''::text, ''approved''::text])))',
        'CHECK ((count_mode = ANY (ARRAY[''OBFUSCATED''::text, ''EXACT''::text])))',
        'CHECK ( CASE WHEN (tier = ''public''::text) THEN ((ras_subject IS NULL) AND (client_key IS NOT NULL)) '
          || 'ELSE ((ras_subject IS NOT NULL) AND (client_key IS NULL)) END)']);
CREATE INDEX IF NOT EXISTS query_ledger_subject_idx ON access.query_ledger (ras_subject, created_at);
CREATE INDEX IF NOT EXISTS query_ledger_client_idx  ON access.query_ledger (client_key, created_at);
SELECT catalog.deploy_check_index('access', 'query_ledger_subject_idx',
  'CREATE INDEX query_ledger_subject_idx ON access.query_ledger USING btree (ras_subject, created_at)');
SELECT catalog.deploy_check_index('access', 'query_ledger_client_idx',
  'CREATE INDEX query_ledger_client_idx ON access.query_ledger USING btree (client_key, created_at)');

DROP FUNCTION catalog.deploy_add_columns_and_check(text, text, text, text[], text[]);
DROP FUNCTION catalog.deploy_check_index(text, text, text);

-- =====================================================================
-- ROLES AND GRANTS
-- Roles are created once per server by an Entra administrator (see README.md); each maps to
-- one Entra managed identity. Re-run this section after every schema change: grants limited
-- to specific tables (api_public's) are not covered by the defaults. Grants for provisioning
-- and doc_generator are future additions, in future/.
-- =====================================================================

-- A role that does not exist yet is created NOLOGIN as a placeholder so the grants below
-- succeed; an administrator still has to map it to its Entra identity. Needs CREATEROLE,
-- but only when a role is missing.
DO $$
DECLARE
  r  text;
BEGIN
  FOREACH r IN ARRAY ARRAY['bdc_dp_owner', 'rebuild_job', 'api_search', 'api_counting', 'api_public',
                           'clearinghouse'] LOOP
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
REVOKE ALL ON SCHEMA catalog, access FROM PUBLIC;

-- Schema usage and default privileges for tables added later
GRANT USAGE ON SCHEMA catalog
  TO rebuild_job, api_search, api_counting, api_public;
GRANT USAGE ON SCHEMA access
  TO api_search, api_counting, api_public, clearinghouse;

ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner IN SCHEMA catalog
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rebuild_job;
ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner IN SCHEMA catalog
  GRANT SELECT ON TABLES TO api_search;

-- catalog: the rebuild job writes every catalog table from the release
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog TO rebuild_job;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO api_search;
GRANT INSERT, UPDATE ON catalog.term_resolution_cache TO api_search;

GRANT SELECT ON catalog.release, catalog.consent_group, catalog.consent_group_eligibility,
                catalog.consent_group_current, catalog.stigmatizing_concept
  TO api_counting;

-- catalog: public summaries get labels, studies, releases and the stigmatizing list;
-- no consent records or eligibility
GRANT SELECT ON catalog.release, catalog.term, catalog.term_closure, catalog.study,
                catalog.stigmatizing_concept, catalog.term_availability
  TO api_public;

-- access: accounts and entitlements are written only by the clearinghouse
GRANT SELECT, INSERT, UPDATE, DELETE ON access.account, access.entitlement TO clearinghouse;
GRANT SELECT ON access.entitlement TO api_counting;

-- access: the query ledger
GRANT SELECT, INSERT ON access.query_ledger TO api_search, api_counting;
-- The public summary app records its own queries. It may insert every column except
-- ras_subject, so with the table's CHECK it can write only public rows (no account, a
-- client_key). It gets no SELECT: it cannot read signed-in or approved history. If public
-- budgets are adopted, give it a SECURITY DEFINER function that sums budget_cost for one
-- client_key, rather than SELECT on the table.
GRANT INSERT (client_key, tier, stage, policy_version, release, expression_hash, result_hash,
              count_mode, budget_cost, created_at)
  ON access.query_ledger TO api_public;

-- api_public gets only the ledger insert above.

COMMIT;

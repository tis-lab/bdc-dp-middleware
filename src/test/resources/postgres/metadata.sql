-- =====================================================================
-- bdc_dp_metadata: schemas catalog and access. No participant-level data.
--   catalog : releases, terms, studies, curated consent meaning and eligibility
--   access  : accounts, entitlements and the query ledger
-- Columns must match metadata.yaml (LinkML); run python schema/tests/check_schema.py
-- after changing either (CI runs it too). Deployed as deploy_migrations acting as
-- bdc_dp_owner, which owns every object; grants are at the end and are safe to re-run.
-- =====================================================================

CREATE SCHEMA catalog;
COMMENT ON SCHEMA catalog IS
  'Releases, terms, studies, curated consent meaning and eligibility. No participant-level data.';

CREATE SCHEMA access;
COMMENT ON SCHEMA access IS
  'Accounts, entitlements and the query ledger.';

-- ---------------------------------------------------------------------
-- catalog: releases, terms, studies, curated consent meaning
-- ---------------------------------------------------------------------

CREATE TABLE catalog.release (
  release         text PRIMARY KEY,                -- e.g. '2026.10'
  delta_version   bigint      NOT NULL,            -- pinned version of the governed Delta tables
  schema_version  text        NOT NULL,            -- bdchm_governed schema version
  kg_version      text        NOT NULL,            -- pinned BDC-KG version for terms, closure and mappings
  status          text        NOT NULL CHECK (status IN ('loading', 'active', 'retired')),
  loaded_at       timestamptz NOT NULL
);

CREATE TABLE catalog.term (
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

CREATE TABLE catalog.term_closure (                -- each term with all its descendants (and itself)
  release     text REFERENCES catalog.release,
  ancestor    text,
  descendant  text,
  PRIMARY KEY (release, ancestor, descendant)
);
CREATE INDEX term_closure_descendant_idx ON catalog.term_closure (release, descendant);

CREATE TABLE catalog.study (
  release            text REFERENCES catalog.release,
  research_study_id  text,
  accession          text NOT NULL,              -- 'phs001234.v3.p1'
  title              text NOT NULL,
  description        text,
  PRIMARY KEY (release, research_study_id)
);

CREATE TABLE catalog.variable_mapping (             -- from the release's BDC-KG version
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
CREATE INDEX variable_mapping_study_idx ON catalog.variable_mapping (release, research_study_id, concept);

CREATE TABLE catalog.consent_group (               -- curated ConsentGroup records, versioned
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

CREATE TABLE catalog.consent_group_eligibility (   -- the portal's policy per consent group, versioned
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

-- Current consent meaning with the current eligibility decision (GovernedConsentGroup). A
-- decision made against an older version of the consent meaning does not apply: the group reads
-- as ineligible until eligibility is decided again. Columns are listed rather than m.*, because
-- Postgres expands * once at creation and would silently omit columns added to consent_group later.
CREATE VIEW catalog.consent_group_current AS
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

CREATE TABLE catalog.stigmatizing_concept (        -- excluded as filters outside approved scope
  list_version  integer,
  root_curie    text,                              -- the whole subtree is excluded
  category      text NOT NULL,                     -- e.g. 'mental health'
  reviewed_by   text NOT NULL,
  PRIMARY KEY (list_version, root_curie)
);

CREATE TABLE catalog.term_availability (           -- studies with at least min_count participants per term
  release            text REFERENCES catalog.release,
  curie              text,
  research_study_id  text,
  PRIMARY KEY (release, curie, research_study_id)
);

CREATE TABLE catalog.term_resolution_cache (       -- BDC-KG results that passed release validation
  release      text REFERENCES catalog.release,
  query_text   text,                               -- normalized search text
  candidates   jsonb       NOT NULL,
  fetched_at   timestamptz NOT NULL,
  PRIMARY KEY (release, query_text)
);

-- ---------------------------------------------------------------------
-- access: accounts, entitlements, the query ledger
-- ---------------------------------------------------------------------

CREATE TABLE access.account (                      -- one row per NIH RAS identity
  ras_subject  text PRIMARY KEY,
  first_seen   timestamptz NOT NULL,
  last_seen    timestamptz NOT NULL
);

CREATE TABLE access.entitlement (                  -- one row per consent group per visa
  ras_subject       text REFERENCES access.account,
  consent_group_id  text CHECK (consent_group_id ~ '^phs\d{6}\.c\d+$'),
  visa_id           text,
  issuer            text        NOT NULL,
  granted_at        timestamptz NOT NULL,
  expires_at        timestamptz NOT NULL,          -- never later than the visa's expiry
  PRIMARY KEY (ras_subject, consent_group_id, visa_id)
);
CREATE INDEX entitlement_expiry_idx ON access.entitlement (expires_at);

-- Public queries have no user: a public row is identified by client_key instead of an account.
-- The CHECK keeps the two apart, so a row naming an account cannot be written as public and a
-- signed-in or approved row cannot omit its account.
CREATE TABLE access.query_ledger (                 -- budgets, overlap checks and audit
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
CREATE INDEX query_ledger_subject_idx ON access.query_ledger (ras_subject, created_at);
CREATE INDEX query_ledger_client_idx  ON access.query_ledger (client_key, created_at);

-- =====================================================================
-- ROLES AND GRANTS
-- Roles are created once per server by an Entra administrator (see README.md); each maps to
-- one Entra managed identity. Re-run this section after every schema change: grants limited
-- to specific tables (api_public's) are not covered by the defaults. Grants for provisioning
-- and doc_generator are future additions, in future/.
-- =====================================================================

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

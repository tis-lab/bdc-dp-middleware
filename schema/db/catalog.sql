-- =====================================================================
-- CATALOG DATABASE
--   catalog : metadata, safe for search and document generation
-- =====================================================================

CREATE SCHEMA catalog;

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
  release     text,
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
  consent_group_id         text,                   -- 'phs001234.c1'
  version                  integer,
  study_accession          text    NOT NULL,
  dbgap_abbreviation       text    NOT NULL,       -- 'HMB-IRB-NPU'
  primary_data_use         text    NOT NULL,       -- PrimaryDataUseEnum
  modifiers                text[]  NOT NULL DEFAULT '{}',   -- DataUseModifierEnum
  disease_restriction      text[]  NOT NULL DEFAULT '{}',   -- MONDO terms, required for DS
  source_consent_text      text    NOT NULL,
  exact_count_eligible     boolean NOT NULL DEFAULT false,
  sharing_eligible         boolean NOT NULL DEFAULT false,
  eligibility_approved_by  text,
  curated_by               text    NOT NULL,
  reviewed_by              text    NOT NULL,
  curated_on               date    NOT NULL,
  PRIMARY KEY (consent_group_id, version),
  CHECK (primary_data_use <> 'DS' OR cardinality(disease_restriction) > 0)
);

CREATE VIEW catalog.consent_group_current AS
  SELECT DISTINCT ON (consent_group_id) *
  FROM catalog.consent_group
  ORDER BY consent_group_id, version DESC;

CREATE TABLE catalog.stigmatizing_concept (        -- excluded as filters outside approved scope
  list_version  integer,
  root_curie    text,                              -- the whole subtree is excluded
  category      text NOT NULL,                     -- e.g. 'mental health'
  reviewed_by   text NOT NULL,
  PRIMARY KEY (list_version, root_curie)
);

CREATE TABLE catalog.term_availability (           -- studies with at least min_count participants per term
  release            text,
  curie              text,
  research_study_id  text,
  PRIMARY KEY (release, curie, research_study_id)
);

CREATE TABLE catalog.term_resolution_cache (       -- BDC-KG results that passed release validation
  release      text,
  query_text   text,                               -- normalized search text
  candidates   jsonb       NOT NULL,
  fetched_at   timestamptz NOT NULL,
  PRIMARY KEY (release, query_text)
);

-- =====================================================================
-- ROLES AND GRANTS
-- Each role maps to one Entra managed identity (Entra-only authentication).
-- Run the catalog section in the catalog database and the counting
-- section in the counting database.
-- =====================================================================
-- Some of these roles are theoretical and don't actually exist yet, but represent different 
-- access levels that may be required for different processes

GRANT USAGE ON SCHEMA catalog TO rebuild_job, doc_generator, api_search, api_counting, provisioning;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog TO rebuild_job;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO doc_generator, api_search;
GRANT INSERT, UPDATE ON catalog.term_resolution_cache TO api_search;
GRANT SELECT ON catalog.consent_group, catalog.consent_group_current,
                catalog.stigmatizing_concept, catalog.release TO api_counting, provisioning;
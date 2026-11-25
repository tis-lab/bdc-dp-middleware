-- =====================================================================
-- FUTURE ADDITION, NOT DEPLOYED: persisted cohorts in bdc_dp_hm_index
--
-- Querying does not store cohorts: a stage 5 request materializes its cohort in
-- a transaction-scoped temporary table (see hm_index.sql). This file adds a
-- cohort schema for cohorts kept beyond one request: reused across a
-- researcher's session, or shared as a participant list by provisioning
-- (provisioning_hm_index.sql builds on it). It also holds cohort.released_set for
-- the overlap check on live responses. See COHORTS.md before applying.
-- When needed, run this file in bdc_dp_hm_index after hm_index.sql, as
-- deploy_migrations acting as bdc_dp_owner.
-- Columns must match cohort_hm_index.yaml; check_schema.py checks them.
-- =====================================================================

CREATE SCHEMA cohort;
COMMENT ON SCHEMA cohort IS
  'Persisted cohorts. Restricted; rows expire.';

CREATE TABLE cohort.cohort (                     -- cohorts kept beyond one request
  cohort_id        uuid PRIMARY KEY,
  release          text        NOT NULL,
  ras_subject      text,                           -- NULL only if public cohorts are ever saved
  expression       jsonb       NOT NULL,           -- cohort expression over BDC-HM
  expression_hash  text        NOT NULL,
  created_at       timestamptz NOT NULL,
  expires_at       timestamptz NOT NULL
);
-- Expiry: readers must ignore rows past expires_at, and a cleanup removes them (COHORTS.md)
CREATE INDEX cohort_expires_idx ON cohort.cohort (expires_at);

CREATE TABLE cohort.cohort_member (
  cohort_id       uuid REFERENCES cohort.cohort ON DELETE CASCADE,
  ordinal         integer,
  in_exact_scope  boolean NOT NULL,
  PRIMARY KEY (cohort_id, ordinal)
);

-- Overlap check: each live query's released cohort, as participant numbers only. Facets and
-- contributions are the cohort intersected with a posting list or consent-group range, so
-- they are derived when checking, not stored. Read and written only through SECURITY DEFINER
-- functions, cohort.record_released_set and cohort.overlap_violations, to be written with
-- this addition (COHORTS.md); a purge function enforces the retention window.
CREATE TABLE cohort.released_set (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ledger_id      bigint      NOT NULL,             -- access.query_ledger.id in bdc_dp_metadata; no FK across databases
  account        text        NOT NULL,             -- RAS subject of the caller
  release        text        NOT NULL,
  matched_count  integer     NOT NULL,
  participants   integer[]   NOT NULL,             -- sorted participant numbers in this release
  created_at     timestamptz NOT NULL
);
CREATE INDEX released_set_account_idx ON cohort.released_set (account, created_at);

-- =====================================================================
-- GRANTS. Safe to re-run; re-run after every schema change.
-- =====================================================================

REVOKE ALL ON SCHEMA cohort FROM PUBLIC;
GRANT USAGE ON SCHEMA cohort TO api_counting;
ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner IN SCHEMA cohort
  GRANT SELECT, INSERT, DELETE ON TABLES TO api_counting;

-- cohort: written, read and expired by the counting API
GRANT SELECT, INSERT, DELETE ON cohort.cohort, cohort.cohort_member TO api_counting;

-- cohort.released_set: no direct access for any API role; the overlap-check functions
-- (SECURITY DEFINER, owner's rights) are the only way in. The REVOKE undoes the default
-- privileges above should the table ever be created after them.
REVOKE ALL ON cohort.released_set FROM api_counting;

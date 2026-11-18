-- =====================================================================
-- FUTURE ADDITION, NOT DEPLOYED: provisioning in bdc_dp_metadata
--
-- The provisioning service shares participant-level data from a materialized
-- cohort with an approved researcher. The portal currently supports querying
-- only, so none of this is deployed. When provisioning is ready, run this file
-- in bdc_dp_metadata after metadata.sql, as deploy_migrations acting as
-- bdc_dp_owner, and add the provisioning role to the server (see README.md).
-- Columns must match provisioning_metadata.yaml; check_schema.py checks them.
-- =====================================================================

CREATE TABLE access.share_record (                 -- every share the provisioning service creates
  share_name      text PRIMARY KEY,
  recipient_name  text        NOT NULL,
  ras_subject     text        NOT NULL REFERENCES access.account,
  cohort_id       uuid        NOT NULL,            -- cohort.cohort in bdc_dp_hm_index; no cross-database key
  release         text        NOT NULL,
  consent_groups  text[]      NOT NULL,
  visa_ids        text[]      NOT NULL,
  bytes_shared    bigint,                          -- size of the shared files at creation, for egress estimates
  created_at      timestamptz NOT NULL,
  expires_at      timestamptz NOT NULL,
  dropped_at      timestamptz
);
CREATE INDEX share_record_subject_idx ON access.share_record (ras_subject) WHERE dropped_at IS NULL;

-- =====================================================================
-- GRANTS for provisioning. Safe to re-run; re-run after every schema change.
-- =====================================================================

GRANT USAGE ON SCHEMA catalog, access TO provisioning;

-- catalog: consent meaning and eligibility, to decide what may be shared
GRANT SELECT ON catalog.release, catalog.consent_group, catalog.consent_group_eligibility,
                catalog.consent_group_current, catalog.stigmatizing_concept
  TO provisioning;

-- access: entitlements and accounts, to check the recipient's visas
GRANT SELECT ON access.entitlement, access.account TO provisioning;

-- access: share records are written only by provisioning
GRANT SELECT, INSERT, UPDATE ON access.share_record TO provisioning;

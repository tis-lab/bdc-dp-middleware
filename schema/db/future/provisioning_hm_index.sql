-- =====================================================================
-- FUTURE ADDITION, NOT DEPLOYED: provisioning in bdc_dp_hm_index
--
-- The provisioning service maps the in-scope members of a materialized cohort
-- back to participant IDs and shares their data, limited to the records that
-- satisfied the cohort's rules. The portal currently supports
-- querying only, so none of this is deployed. When provisioning is ready, run
-- this file in bdc_dp_hm_index after hm_index.sql, as deploy_migrations acting
-- as bdc_dp_owner, and add the provisioning role to the server (see README.md).
-- Columns must match provisioning_hm_index.yaml; check_schema.py checks them.
-- =====================================================================

-- The BDC-HM records that satisfied each rule for each cohort member, so a share can be limited
-- to the matching records. Written by the counting API when it materializes a cohort.
CREATE TABLE cohort.cohort_evidence (            -- which source records satisfied each rule
  cohort_id         uuid REFERENCES cohort.cohort ON DELETE CASCADE,
  ordinal           integer,
  rule_name         text,
  source_record_id  text,
  PRIMARY KEY (cohort_id, ordinal, rule_name, source_record_id)
);

CREATE VIEW cohort.cohort_member_in_scope AS     -- provisioning never sees out-of-scope members
  SELECT cohort_id, ordinal FROM cohort.cohort_member WHERE in_exact_scope;

-- =====================================================================
-- GRANTS for provisioning. Safe to re-run; re-run after every schema change.
-- =====================================================================

GRANT USAGE ON SCHEMA hm, cohort TO provisioning;

-- hm: provisioning maps participant numbers back to participant IDs
GRANT SELECT ON hm.participant_ordinal TO provisioning;

-- cohort: provisioning sees in-scope members only, never cohort_member itself
GRANT SELECT ON cohort.cohort, cohort.cohort_member_in_scope TO provisioning;
GRANT SELECT ON cohort.cohort_member_in_scope TO api_counting;
-- cohort_evidence: written by the counting API with each cohort
GRANT SELECT, INSERT, DELETE ON cohort.cohort_evidence TO api_counting;

-- Run as the disposable cluster administrator, initially connected to bdc_dp_hm_index.
\set ON_ERROR_STOP on
\ir roles.sql
\connect bdc_dp_metadata
SET ROLE bdc_dp_owner;
\ir metadata.sql
RESET ROLE;
\connect bdc_dp_hm_index
SET ROLE bdc_dp_owner;
\ir hm_index.sql
\ir fixture.sql
RESET ROLE;

-- Disposable synthetic CI cluster only. Never run against a shared server.
CREATE ROLE bdc_dp_owner NOLOGIN;
CREATE ROLE rebuild_job NOLOGIN;
CREATE ROLE api_search NOLOGIN;
CREATE ROLE api_counting NOLOGIN;
CREATE ROLE api_public NOLOGIN;
CREATE ROLE clearinghouse NOLOGIN;
CREATE ROLE dp_search_test LOGIN PASSWORD 'test-only-password';
CREATE DATABASE bdc_dp_metadata OWNER bdc_dp_owner;
ALTER DATABASE bdc_dp_hm_index OWNER TO bdc_dp_owner;
REVOKE ALL ON DATABASE bdc_dp_metadata, bdc_dp_hm_index FROM PUBLIC;
GRANT CONNECT ON DATABASE bdc_dp_hm_index TO dp_search_test, rebuild_job, api_counting, api_public;
GRANT CONNECT ON DATABASE bdc_dp_metadata TO rebuild_job, api_search, api_counting, api_public, clearinghouse;

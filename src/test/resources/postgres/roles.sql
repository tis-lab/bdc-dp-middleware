-- Disposable CI database only. Production identities are provisioned separately.
CREATE ROLE rebuild_job;
CREATE ROLE api_counting;
CREATE ROLE api_public;
CREATE ROLE provisioning;
CREATE ROLE dp_search_test LOGIN PASSWORD 'test-only-password';

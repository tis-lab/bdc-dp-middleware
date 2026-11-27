-- =====================================================================
-- Server bootstrap: the roles and the two databases that metadata.sql and hm_index.sql
-- assume exist. Run once per server, by an administrator, before either script:
--
--   psql -v ON_ERROR_STOP=1 -h HOST -U ADMIN -d postgres -f schema/db/bootstrap.sql
--
-- ADMIN is a superuser, or a role with CREATEROLE and CREATEDB (on Azure, a member of the
-- Entra administrators group). Needs psql (it uses \gexec and \connect) and PostgreSQL 10+.
-- Safe to re-run: it creates only what is missing and re-applies attributes and grants.
--
-- After it, the schema scripts run as deploy_migrations, which every session in the two
-- databases turns into bdc_dp_owner (see the end of this file), so the objects they
-- create are owned by bdc_dp_owner:
--
--   psql -v ON_ERROR_STOP=1 -U deploy_migrations -d bdc_dp_metadata -f schema/db/metadata.sql
--   psql -v ON_ERROR_STOP=1 -U deploy_migrations -d bdc_dp_hm_index -f schema/db/hm_index.sql
--   (or: java -jar target/app.jar setup-db --db.user=deploy_migrations --db.name=... hm|metadata)
--
-- Login roles are created without passwords. On a plain PostgreSQL server, give the ones
-- that will log in a password afterwards, e.g. \password deploy_migrations. On Azure,
-- create each login role first as an Entra principal, connected to postgres:
--   SELECT * FROM pgaadauth_create_principal_with_oid('rebuild_job', '<object id>', 'service', false, false);
-- This script then leaves those roles in place and only sets their limits and grants.
-- See schema/db/README.md, "Roles and managed identities".
-- =====================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------
-- Roles. Role names are the same in every environment.
-- ---------------------------------------------------------------------
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('bdc_dp_owner',      false),  -- owns every object; no one logs in as it
      ('deploy_migrations', true),   -- CI/CD pipeline; acts as bdc_dp_owner
      ('rebuild_job',       true),
      ('api_search',        true),
      ('api_counting',      true),
      ('api_public',        true),
      ('clearinghouse',     true)
    ) AS t(name, login)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = r.name) THEN
      EXECUTE format('CREATE ROLE %I %s', r.name, CASE WHEN r.login THEN 'LOGIN' ELSE 'NOLOGIN' END);
      RAISE NOTICE 'created role %', r.name;
    END IF;
  END LOOP;
END
$$;

ALTER ROLE bdc_dp_owner NOLOGIN;

-- Per-role limits (schema/db/README.md). rebuild_job has no statement timeout: it runs off-peak.
ALTER ROLE api_public    CONNECTION LIMIT 40;
ALTER ROLE api_search    CONNECTION LIMIT 20;
ALTER ROLE api_counting  CONNECTION LIMIT 40;
ALTER ROLE clearinghouse CONNECTION LIMIT 5;
ALTER ROLE rebuild_job   CONNECTION LIMIT 10;
ALTER ROLE api_public    SET statement_timeout = '2s';
ALTER ROLE api_search    SET statement_timeout = '5s';
ALTER ROLE api_counting  SET statement_timeout = '30s';
ALTER ROLE clearinghouse SET statement_timeout = '10s';

-- deploy_migrations acts as the owner. The scripts change bdc_dp_owner's default
-- privileges, which only its members may do.
GRANT bdc_dp_owner TO deploy_migrations;

-- A non-superuser administrator must be a member of bdc_dp_owner to create databases
-- owned by it (on PostgreSQL 16+, creating a role no longer makes the creator a member).
SELECT 'GRANT bdc_dp_owner TO CURRENT_USER'
WHERE NOT (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = CURRENT_USER)
  AND NOT pg_catalog.pg_has_role(CURRENT_USER, 'bdc_dp_owner', 'MEMBER')
\gexec

-- ---------------------------------------------------------------------
-- Databases, owned by bdc_dp_owner. hm_index.sql names bdc_dp_hm_index in a grant, so
-- that name is required; metadata.sql names no database.
-- ---------------------------------------------------------------------
SELECT format('CREATE DATABASE %I OWNER bdc_dp_owner', d)
FROM unnest(ARRAY['bdc_dp_metadata', 'bdc_dp_hm_index']) AS d
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = d)
\gexec

ALTER DATABASE bdc_dp_metadata OWNER TO bdc_dp_owner;
ALTER DATABASE bdc_dp_hm_index OWNER TO bdc_dp_owner;

-- Who may connect to which database. PUBLIC loses every database privilege, including
-- CONNECT and TEMPORARY; hm_index.sql grants TEMPORARY on bdc_dp_hm_index to api_counting.
-- A role without CONNECT on bdc_dp_hm_index cannot reach participant-level data at all.
REVOKE ALL ON DATABASE bdc_dp_metadata, bdc_dp_hm_index FROM PUBLIC;

GRANT CONNECT ON DATABASE bdc_dp_metadata
  TO deploy_migrations, rebuild_job, api_search, api_counting, api_public, clearinghouse;
GRANT CONNECT ON DATABASE bdc_dp_hm_index
  TO deploy_migrations, rebuild_job, api_counting, api_public;
REVOKE CONNECT ON DATABASE bdc_dp_hm_index FROM api_search, clearinghouse;

-- Every deploy_migrations session in the two databases starts as SET ROLE bdc_dp_owner,
-- so whatever runs the scripts (psql, setup-db, the pipeline) creates objects owned by
-- bdc_dp_owner, and SECURITY DEFINER functions run with the owner's rights.
ALTER ROLE deploy_migrations IN DATABASE bdc_dp_metadata SET role = 'bdc_dp_owner';
ALTER ROLE deploy_migrations IN DATABASE bdc_dp_hm_index SET role = 'bdc_dp_owner';

-- ---------------------------------------------------------------------
-- Schema public: no one may create objects in it. The scripts revoke this too, but on
-- PostgreSQL 14 and earlier public is owned by the bootstrap superuser, so their REVOKE
-- (run as bdc_dp_owner) only warns and does nothing. On 15+ this is already the default.
-- ---------------------------------------------------------------------
\connect bdc_dp_metadata
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

\connect bdc_dp_hm_index
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

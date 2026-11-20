-- =====================================================================
-- FUTURE ADDITION, NOT DEPLOYED: document generator in bdc_dp_metadata
--
-- The document generator builds search documents from the catalog for Azure AI
-- Search. It is needed only if Azure AI Search is kept, and the portal does not
-- use AI Search capabilities at this time. When it is ready, run this file in
-- bdc_dp_metadata after metadata.sql, as deploy_migrations acting as
-- bdc_dp_owner, and add the doc_generator role to the server (see README.md).
-- It adds no tables, only grants. doc_generator never connects to
-- bdc_dp_hm_index and gets nothing in the access schema.
-- =====================================================================

GRANT USAGE ON SCHEMA catalog TO doc_generator;

-- Reads every catalog table, including ones added later
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO doc_generator;
ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner IN SCHEMA catalog
  GRANT SELECT ON TABLES TO doc_generator;

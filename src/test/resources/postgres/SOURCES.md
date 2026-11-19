# PostgreSQL integration-test contract

`hm_index.sql` and `metadata.sql` are copies from PR #30:
https://github.com/tis-lab/bdc-dp-middleware/pull/30.

These are test dependencies, not application migrations. bootstrap.sql creates
both databases with their actual names, loads DDL as bdc_dp_owner and creates
synthetic fixtures. roles.sql is ONLY for a disposable PostgreSQL cluster.
Production identities are provisioned separately with Entra authentication.

The middleware's dp_search_test login can SELECT only the four hm tables it
queries. It does not inherit api_counting or owner privileges. api_public retains
no SELECT.

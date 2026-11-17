counting.sql is an unmodified copy of schema/db/counting.sql from
https://github.com/tis-lab/bdc-dp-middleware/pull/24.

It is a test dependency, not a migration. roles.sql and fixture.sql create
explicitly synthetic data in a disposable PostgreSQL instance only.
Tests use a separate SELECT-only login. No production tables are created by the middleware.

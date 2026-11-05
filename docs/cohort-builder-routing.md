# Cohort builder service routing

Related task: [BDC-Portal #100](https://github.com/tis-lab/BDC-Portal/issues/100).

The cohort builder UI sends requests through DP-Core, which forwards requests
starting with `/cb/` to this service.

## Request paths

| Purpose                   | External path through DP-Core | Path received by this service    |
|---------------------------|-------------------------------|----------------------------------|
| GraphQL API               | `POST /cb/query`              | `POST /cb/query`                 |
| Optional GraphiQL console | `GET /cb/graphiql`            | `GET /cb/graphiql`               |
| OpenShift liveness check  | Internal only                 | `GET /actuator/health/liveness`  |
| OpenShift readiness check | Internal only                 | `GET /actuator/health/readiness` |

**DP-Core must preserve the `/cb/` prefix.** Forward the HTTP method, request body,
query string, and required headers.

`query` describes the shared API: term lookup, autocomplete, summaries, and
Elasticsearch search all use it. The endpoint still accepts GraphQL JSON bodies;
operation names, variables, and response shapes are unchanged.

There is no global `/cb` servlet context path. The prefix applies to the API and
console, so OpenShift health checks keep their existing URLs.

## Client migration

Change the frontend's API URL from `/graphql` to `/cb/query`.
For local development, use `http://localhost:8080/cb/query`. The old `/graphql`
endpoint is removed.

The same body can be sent to `https://<dp-core-host>/cb/query`. This example checks
routing without depending on Elasticsearch or Monarch. Existing search operations
use the same URL and retain their current search configuration.

# BDC Data Portal Cohort Builder Service

## Build and run

### IntelliJ IDEA

1. **File > Open** and select the project folder. IntelliJ imports the Maven project automatically.
2. **File > Project Structure > Project**: set the SDK to a JDK 21 install and the language level to 21.
3. Open `src/main/java/gov/nih/nhlbi/bdc/dataportal/cohortbuilder/BdcDpCohortBuilderApplication.java` and click
   the green arrow next to the class, or use the generated **DataPortalMiddlewareApplication** run
   configuration.
4. Wait for `Started BdcDpCohortBuilderApplication` in the Run window, then open
   <http://localhost:8080/cb/graphiql>.

### PowerShell

```powershell
# from the project folder
mvn clean package
java -jar .\target\app.jar

# or, without packaging
mvn spring-boot:run
```

Check it is up and running:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Then open <http://localhost:8080/cb/graphiql>

## Load JSON objects into PostgreSQL

The jar can also load files of JSON objects (YAML, JSON, NDJSON) into a PostgreSQL
`jsonb` table in a database and as a user you name:
`java -jar target/app.jar load --db.name=DATABASE --db.user=USER <file-or-dir>`. See
[README-LOAD.md](README-LOAD.md) for options, behavior and troubleshooting.

## Set up the database schemas in PostgreSQL

`java -jar target/app.jar setup-db --db.name=DATABASE --db.user=USER SCRIPT...` creates
the schemas in a new database by running copies of the `schema/db` SQL scripts bundled in
the jar: `hm` (schema `hm`, for `bdc_dp_hm_index`) and `metadata` (schemas `catalog` and
`access`, for `bdc_dp_metadata`). It creates the database, and the roles the scripts grant
to (as `NOLOGIN` placeholders), if they don't exist. On PostgreSQL 10 it runs variants
of the scripts that 10 supports. A script that fails changes nothing. The command takes the same connection options as `load`. See
[README-SETUP-DB.md](README-SETUP-DB.md).

## Routing

The service name is `bdc-data-portal-cohort-builder-service`. The cohort builder UI
sends GraphQL requests to `POST /api/cb/open/query` through DP-Core. DP-Core must forward
the full path unchanged.

## Endpoints

| Endpoint                         | Purpose                                                   |
|----------------------------------|-----------------------------------------------------------|
| `POST /api/cb/open/query`        | Existing public GraphQL operations                        |
| `GET /cb/graphiql`               | Interactive, schema-aware documentation and query console |
| `GET /actuator/health`           | Overall health                                            |
| `GET /actuator/health/liveness`  | OpenShift liveness probe                                  |
| `GET /actuator/health/readiness` | OpenShift readiness probe                                 |

### Queries

Always available:

| Query                         | Returns                                                                                                                        |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `terms(query, limit, offset)` | `TermResults` - one paginated list: matching BDC concepts with specification coverage first, then ranked concepts from Monarch |
| `autocomplete(query)`         | `AutocompleteResults` - Monarch's suggestions unchanged, no BDC data, no pagination                                            |
| `health`                      | `Health!` - process status only                                                                                                |
| `summary`                     | `SummaryResponse` - participant counts for a cohort, study by study                                                            |

## PostgreSQL experiment

`search(filter, focus)` returns the same summary contract from PostgreSQL and is independent of the existing Elasticsearch `summary` field.
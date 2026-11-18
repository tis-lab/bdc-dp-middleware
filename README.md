# BDC Data Portal - Cohort Builder Service

## Endpoints

| Method                    | Purpose                                                                  | 
|---------------------------|--------------------------------------------------------------------------|
| `GET /`                   | Starter status                                                           |         
| `GET /api/v1/status`      | Starter status                                                           |                          
| `POST /api/cb/open/query` | Existing public GraphQL operations                                       |
| `GET /cb/graphiql`        | Interactive, schema-aware documentation and query console (for dev only) |

### Queries

| Query                         | Returns                                                                                                                        |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `terms(query, limit, offset)` | `TermResults` - one paginated list: matching BDC concepts with specification coverage first, then ranked concepts from Monarch |
| `autocomplete(query)`         | `AutocompleteResults` - Monarch's suggestions unchanged, no BDC data, no pagination                                            |
| `health`                      | `Health!` - process status only                                                                                                |
| `summary`                     | `SummaryResponse` - participant counts for a cohort, study by study                                                            |

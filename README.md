# BDC Data Portal Middleware

## Routes

| Method | Path                                      | Purpose/Response                                     |
|--------|-------------------------------------------|------------------------------------------------------|
| GET    | `/actuator/health`                        | Application health                                   |
| GET    | `/api/v1/cohorts`                         | All cohort definitions and condition codes           |
| GET    | `/api/v1/cohorts/{cohortId}`              | One cohort definition                                |
| GET    | `/api/v1/cohorts/{cohortId}/participants` | Matching Participant records, each with its study ID |

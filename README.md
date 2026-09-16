# BDC Data Portal Middleware

## Build and run

### IntelliJ IDEA

1. **File > Open** and select the project folder. IntelliJ imports the Maven project automatically.
2. **File > Project Structure > Project**: set the SDK to a JDK 25 install and the language level to 25.
3. Open `src/main/java/org/biodatacatalyst/middleware/DataPortalMiddlewareApplication.java` and click
   the green arrow next to the class, or use the generated **DataPortalMiddlewareApplication** run
   configuration.
4. Wait for `Started DataPortalMiddlewareApplication` in the Run window, then open
   <http://localhost:8080/swagger-ui.html>.

### PowerShell

```powershell
# from the project folder
mvn clean package
java -jar .\target\app.jar

# or, without packaging
mvn spring-boot:run
```

Check it is up and see which studies loaded:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Then open <http://localhost:8080/swagger-ui.html>

## Routes

| Method | Path | Purpose |
|---|---|---|
| GET | `/actuator/health` | Liveness and readiness probe for CCA |
| GET | `/api/v1/health` | Service status and the studies loaded at startup |
| GET | `/api/v1/studies` | Study selector: id, name, participant count |
| GET | `/api/v1/studies/{studyId}` | One study with per-file record counts |
| GET | `/api/v1/studies/{studyId}/participants` | All Participant records in the study |
| GET | `/api/v1/studies/{studyId}/entities/{entity}` | Browse the original records of any one file |
| GET | `/api/v1/studies/{studyId}/participants/{participantId}` | One Participant record |
| GET | `/api/v1/studies/{studyId}/participants/{participantId}/detail` | The participant joined with every related record type |
| GET | `/api/v1/studies/{studyId}/participants/{participantId}/records/{entity}` | The participant's records of one type, with optional filters |
| GET | `/api/v1/studies/{studyId}/participants/{participantId}/measurements` | Standalone and nested observations, with provenance |
| GET | `/api/v1/cohorts` | Cohort definitions and their condition codes |
| GET | `/api/v1/cohorts/{cohortId}` | One cohort definition |
| GET | `/api/v1/cohorts/{cohortId}/counts` | Matching participants per study, and the total |
| GET | `/api/v1/cohorts/{cohortId}/participants` | Matching Participant records, optionally one study |

`{entity}` is one of `persons`, `participants`, `demography`, `conditions`, `visits`,
`drug-exposures`, `measurements`, `measurement-sets`. On the participant `records` route,
`participants` is not accepted (use the participant route itself) and `persons` resolves the
participant's `associated_person` reference, returning zero or one record.


### Filters

Filters are exact and case sensitive. A blank value is ignored; a filter that does not apply to the
entity returns 400 rather than being silently dropped.

| Parameter | Applies to | Matched field |
|---|---|---|
| `concept` | `conditions` | `condition_concept` |
| `concept` | `drug-exposures` | `drug_concept` |
| `concept` | `measurements` | `observation_type` |
| `status` | `conditions` | `condition_status` |
| `status` | `drug-exposures` | `exposure_status` |
| `relationship` | `conditions` | `relationship_to_participant` |
| `visitId` | measurements route | effective visit |
| `studyId` | cohort participants | restricts to one study |

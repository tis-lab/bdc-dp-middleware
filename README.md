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

## Endpoints

| Endpoint | Purpose |
| --- | --- |
| `POST /graphql` | The entire frontend API |
| `GET /graphiql` | Interactive, schema-aware documentation and query console |
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/liveness` | OpenShift liveness probe |
| `GET /actuator/health/readiness` | OpenShift readiness probe |

### Queries

Always available:

| Query | Returns |
| --- | --- |
| `terms(query, limit, offset)` | `TermResults` — ranked candidates from Monarch |
| `health` | `Health!` — process status only |

With the `synthetic` profile active: `studies`, `study`, `participants`, `entityRecords`,
`participant`, `participantDetail`, `participantRecords`, `measurements`, `definitions`,
`definition`, `definitionCounts`, `definitionParticipants`.

## Optional synthetic feature

Off by default. Synthetic data can be read by activating the profile:

```powershell
$env:SPRING_PROFILES_ACTIVE = "synthetic"
$env:APP_DATA_LOCATION = "classpath:data/"
mvn spring-boot:run
```
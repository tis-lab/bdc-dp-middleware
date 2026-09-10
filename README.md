# BDC Data Portal Middleware

## Routes

| Method | Path | Purpose |
|---|---|---|
| GET | / | Starter status |
| GET | /api/v1/status | Starter status |
| GET | /actuator/health | Application health |
| GET | /actuator/health/liveness | OpenShift liveness probe |
| GET | /actuator/health/readiness | OpenShift readiness probe |

The status endpoint returns HTTP 200 and:

```json
{
  "service": "bdc-dp-middleware",
  "status": "UP",
  "message": "Starter application is running"
}
```
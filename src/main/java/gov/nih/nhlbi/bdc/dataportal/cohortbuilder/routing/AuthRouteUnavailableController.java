package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.routing;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reserves the DP-Core auth route. */
@RestController
public class AuthRouteUnavailableController {

    @PostMapping(path = "/api/cb/auth/query", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(Map.of("errors", List.of(Map.of(
                        "message", "Authenticated GraphQL is unavailable until DP-Core identity verification is configured.",
                        "extensions", Map.of("code", "AUTH_NOT_CONFIGURED")))));
    }
}

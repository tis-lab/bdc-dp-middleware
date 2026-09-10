package org.biodatacatalyst.middleware.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Health", description = "Application availability")
@RestController
public class StatusController {

    @Operation(
            summary = "Check application health",
            description = "Confirms the middleware is responding."
    )
    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of(
                "service", "bdc-dp-middleware",
                "status", "UP",
                "message", "BDC Data Portal middleware is running"
        );
    }
}
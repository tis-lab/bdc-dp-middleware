package org.biodatacatalyst.middleware.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatusController {
    @GetMapping({"/", "/api/v1/status"})
    public Map<String, String> status() {
        return Map.of(
                "service", "bdc-dp-middleware",
                "status", "UP",
                "message", "Starter application is running"
        );
    }
}

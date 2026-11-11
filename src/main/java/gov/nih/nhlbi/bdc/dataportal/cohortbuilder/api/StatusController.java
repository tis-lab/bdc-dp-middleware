package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

@RestController
public class StatusController {
    private final String serviceName;

    public StatusController(@Value("${spring.application.name}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping({"/", "/api/v1/status"})
    public Map<String, String> status() {
        return Map.of(
                "service", serviceName,
                "status", "UP",
                "message", "Starter application is running"
        );
    }
}

package org.biodatacatalyst.middleware.api;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.biodatacatalyst.middleware.service.StudyRepository;

@Tag(name = "Health", description = "Application availability")
@RestController
@RequestMapping("/api/v1/health")
public class StatusController {

    private final StudyRepository repository;

    public StatusController(StudyRepository repository) {
        this.repository = repository;
    }

    @Operation(operationId = "health", summary = "Check the application and the loaded studies",
            description = "Returns UP once the application is serving requests. The study data is read and "
                    + "validated at startup, so the studies listed here are the ones available to every route.")
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "bdc-dp-middleware");
        body.put("status", "UP");
        body.put("studies", repository.studyIds());
        return body;
    }
}

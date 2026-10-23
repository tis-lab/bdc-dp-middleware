package org.biodatacatalyst.middleware.health;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Process status only. OpenShift uses Actuator probes, independent of external data services. */
@Controller
public class HealthQueryController {

    @QueryMapping
    public Health health() {
        return new Health("bdc-dp-middleware", "UP");
    }

    /**
     * @param status always {@code UP}: if the process could not serve this field, there would be
     *               no response to put a status in.
     */
    public record Health(String service, String status) {
    }
}

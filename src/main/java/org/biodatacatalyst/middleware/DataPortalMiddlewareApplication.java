package org.biodatacatalyst.middleware;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
        info = @Info(
                title = "BDC Data Portal Middleware",
                version = "0.2.0",
                description = """
                        Read-only API over synthetic, harmonized BDC study data held in multi-document YAML files."""),
        servers = @Server(url = "/", description = "Current host"))
@SpringBootApplication
public class DataPortalMiddlewareApplication {
    static void main(String[] args) {
        SpringApplication.run(DataPortalMiddlewareApplication.class, args);
    }
}

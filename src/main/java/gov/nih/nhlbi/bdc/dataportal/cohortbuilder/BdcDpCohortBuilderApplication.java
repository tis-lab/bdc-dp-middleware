package gov.nih.nhlbi.bdc.dataportal.cohortbuilder;

import java.util.OptionalInt;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.Commands;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BdcDpCohortBuilderApplication {
    public static void main(String[] args) {
        // `java -jar app.jar COMMAND ...` runs a command instead of the service; see Commands.
        OptionalInt status = Commands.dispatch(args);
        if (status.isPresent()) {
            System.exit(status.getAsInt());
        }
        SpringApplication.run(BdcDpCohortBuilderApplication.class, args);
    }
}

package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsTest {

    @Test
    void serviceArgumentsAreNotCommands() {
        assertThat(Commands.dispatch()).isEmpty();
        assertThat(Commands.dispatch("--server.port=0", "load")).isEmpty();
        assertThat(Commands.dispatch("-Dx")).isEmpty();
    }

    @Test
    void helpListsCommands() {
        assertThat(Commands.dispatch("help")).isEqualTo(OptionalInt.of(CliCommand.EXIT_OK));
        assertThat(Commands.all()).containsKeys("load", "setup-db");
    }

    @Test
    void unknownCommandIsAnError() {
        assertThat(Commands.dispatch("lod", "x")).isEqualTo(OptionalInt.of(CliCommand.EXIT_ERROR));
    }

    @Test
    void commandHelpDoesNotStartTheCommand() {
        assertThat(Commands.dispatch("load", "x", "--help")).isEqualTo(OptionalInt.of(CliCommand.EXIT_OK));
    }

    @Test
    void registeredCommandsAreCliCommandsWithUsage() {
        Commands.all().values().forEach(c -> {
            assertThat(CliCommand.class).isAssignableFrom(c.type());
            assertThat(c.usage()).contains(c.name());
            assertThat(c.description()).isNotBlank();
        });
    }
}

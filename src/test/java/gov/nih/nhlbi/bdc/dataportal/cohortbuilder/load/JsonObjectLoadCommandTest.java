package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.CliCommand;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.Commands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Command-line behavior that needs no database; loading itself is in JsonObjectLoaderPgTest. */
class JsonObjectLoadCommandTest {

    @TempDir
    Path dir;

    private static int load(String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "load";
        System.arraycopy(args, 0, all, 1, args.length);
        return Commands.dispatch(all).orElseThrow();
    }

    /** Runs load and returns what it wrote to standard error. */
    private static String loadErrors(String... args) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertThat(load(args)).isEqualTo(CliCommand.EXIT_ERROR);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void databaseUserAndPathsAreRequired() {
        assertThat(loadErrors()).contains("missing --db.name, --db.user, PATH");
        assertThat(loadErrors("--db.name=d", "x")).contains("missing --db.user").doesNotContain("--db.name,");
        assertThat(loadErrors("--db.user=u", "--db.name=", "x")).contains("missing --db.name");
        assertThat(loadErrors("--db.name=d", "--db.user=u")).contains("missing PATH");
    }

    @Test
    void numbersAreChecked() {
        assertThat(loadErrors("--db.name=d", "--db.user=u", "--db.port=abc", "x"))
                .contains("--db.port must be a number");
    }

    @Test
    void helpSucceeds() {
        assertThat(load("--help")).isEqualTo(CliCommand.EXIT_OK);
    }

    @Test
    void unreachablePostgresIsAnError() throws Exception {
        Path file = Files.writeString(dir.resolve("S-T--data.ndjson"), "{\"id\":\"a\"}\n");

        int status = load("--db.host=127.0.0.1", "--db.port=1", "--db.name=d", "--db.user=u", file.toString());

        assertThat(status).isEqualTo(CliCommand.EXIT_ERROR);
    }
}

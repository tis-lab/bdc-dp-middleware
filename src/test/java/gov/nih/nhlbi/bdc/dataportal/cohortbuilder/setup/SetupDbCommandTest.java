package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.setup;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.CliCommand;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.Commands;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Command-line behavior that needs no database; running the scripts is in SetupDbCommandPgTest. */
class SetupDbCommandTest {

    private static int setup(String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "setup-db";
        System.arraycopy(args, 0, all, 1, args.length);
        return Commands.dispatch(all).orElseThrow();
    }

    /** Runs setup-db and returns what it wrote to standard error. */
    private static String setupErrors(String... args) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertThat(setup(args)).isEqualTo(CliCommand.EXIT_ERROR);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void databaseUserAndScriptsAreRequired() {
        assertThat(setupErrors()).contains("missing --db.name, --db.user, SCRIPT");
        assertThat(setupErrors("--db.name=d", "hm")).contains("missing --db.user").doesNotContain("--db.name,");
        assertThat(setupErrors("--db.user=u", "--db.name=", "hm")).contains("missing --db.name");
        assertThat(setupErrors("--db.name=d", "--db.user=u")).contains("missing SCRIPT");
    }

    @Test
    void unknownScriptsAreRejected() {
        assertThat(setupErrors("--db.name=d", "--db.user=u", "hm", "extra.sql", "meta"))
                .contains("unknown scripts: extra.sql, meta (choose from hm, metadata)");
    }

    @Test
    void numbersAreChecked() {
        assertThat(setupErrors("--db.name=d", "--db.user=u", "--db.port=abc", "hm"))
                .contains("--db.port must be a number");
    }

    @Test
    void helpSucceeds() {
        assertThat(setup("--help")).isEqualTo(CliCommand.EXIT_OK);
    }

    @Test
    void unreachablePostgresIsAnError() {
        assertThat(setup("--db.host=127.0.0.1", "--db.port=1", "--db.name=d", "--db.user=u", "hm", "metadata"))
                .isEqualTo(CliCommand.EXIT_ERROR);
    }

    /** The roles each script's GRANT and REVOKE statements name must be the ones setup-db creates. */
    @Test
    void rolesMatchTheScripts() throws Exception {
        Pattern statement = Pattern.compile("\\b(?:GRANT|REVOKE)\\b[^;]*;");
        Pattern grantee = Pattern.compile("\\b(?:TO|FROM)\\s+([\\w\\s,]+);$");
        for (String name : SetupDbCommand.SCRIPTS.keySet()) {
            String sql = SetupDbCommand.script(name).replaceAll("--[^\\n]*", "");
            Set<String> named = new TreeSet<>();
            Matcher statements = statement.matcher(sql);
            while (statements.find()) {
                Matcher to = grantee.matcher(statements.group());
                if (to.find()) {
                    for (String role : to.group(1).split(",")) {
                        named.add(role.strip());
                    }
                }
            }
            named.remove("PUBLIC");
            Matcher owner = Pattern.compile("FOR ROLE (\\w+)").matcher(sql);
            while (owner.find()) {
                named.add(owner.group(1));
            }
            assertThat(new TreeSet<>(SetupDbCommand.ROLES.get(name))).as(name).isEqualTo(named);
        }
    }

    @Test
    void databaseNamesAreQuoted() {
        assertThat(SetupDbCommand.quoteIdentifier("bdc_dp_hm_index")).isEqualTo("\"bdc_dp_hm_index\"");
        assertThat(SetupDbCommand.quoteIdentifier("My \"db\"")).isEqualTo("\"My \"\"db\"\"\"");
    }

    @Test
    void bundledScriptsAreCopiesOfSchemaDb() throws Exception {
        assertThat(SetupDbCommand.SCRIPTS).containsOnlyKeys("hm", "metadata");
        assertThat(SetupDbCommand.ROLES).containsOnlyKeys("hm", "metadata");
        assertThat(SetupDbCommand.script("hm"))
                .isEqualTo(Files.readString(Path.of("schema/db/hm_index.sql")));
        assertThat(SetupDbCommand.script("metadata"))
                .isEqualTo(Files.readString(Path.of("schema/db/metadata.sql")));
    }
}

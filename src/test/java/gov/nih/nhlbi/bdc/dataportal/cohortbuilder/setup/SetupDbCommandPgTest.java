package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.setup;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.CliCommand;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs setup-db against a real PostgreSQL, in a throwaway database it drops afterwards.
 *
 * <p>Opt-in twice over, because the command and scripts act outside that database:
 * setup-db creates the roles they grant to (bdc_dp_owner, rebuild_job, api_search,
 * api_counting, api_public, clearinghouse) NOLOGIN when missing, and they are left
 * behind; and hm_index.sql grants TEMPORARY on a database named bdc_dp_hm_index, which
 * the test creates if missing and drops afterwards. Runs only when PG_TEST_SETUP_DB=true
 * and PG_TEST_DB and PG_TEST_USER name a database and a user that can create databases
 * and roles (a superuser on a disposable server); PG_TEST_PASSWORD, PG_TEST_HOST (default
 * localhost) and PG_TEST_PORT (default 5432) are optional. For example:
 * {@code PG_TEST_SETUP_DB=true PG_TEST_DB=postgres PG_TEST_USER=postgres PG_TEST_PASSWORD=... mvn test}
 */
class SetupDbCommandPgTest {

    private static final List<String> ROLES =
            List.of("bdc_dp_owner", "rebuild_job", "api_search", "api_counting", "api_public", "clearinghouse");
    private static final String HM_DATABASE = "bdc_dp_hm_index";

    private static final String DB = env("PG_TEST_DB", null);
    private static final String USER = env("PG_TEST_USER", null);
    private static final String PASSWORD = env("PG_TEST_PASSWORD", null);
    private static final String HOST = env("PG_TEST_HOST", "localhost");
    private static final String PORT = env("PG_TEST_PORT", "5432");

    private JdbcTemplate admin;
    private JdbcTemplate target;
    private String database;
    private boolean createdHmDatabase;

    @BeforeEach
    void createDatabase() {
        assumeTrue("true".equals(System.getenv("PG_TEST_SETUP_DB")),
                "Set PG_TEST_SETUP_DB=true (and PG_TEST_DB, PG_TEST_USER) to run the setup-db tests");
        assumeTrue(DB != null && USER != null, "Set PG_TEST_DB and PG_TEST_USER to run the PostgreSQL tests");
        DriverManagerDataSource adminSource = dataSource(DB);
        try (Connection connection = adminSource.getConnection()) {
            assumeTrue(connection.isValid(5), "PostgreSQL connection is not usable");
        } catch (SQLException e) {
            assumeTrue(false, "PostgreSQL not reachable: " + e.getMessage());
        }
        admin = new JdbcTemplate(adminSource);
        if (count("SELECT count(*) FROM pg_database WHERE datname = ?", admin, HM_DATABASE) == 0) {
            admin.execute("CREATE DATABASE " + HM_DATABASE);
            createdHmDatabase = true;
        }
        database = "setup_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        target = new JdbcTemplate(dataSource(database));
    }

    @AfterEach
    void dropDatabase() {
        if (admin != null && database != null) {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
        if (admin != null && createdHmDatabase) {
            admin.execute("DROP DATABASE IF EXISTS " + HM_DATABASE + " WITH (FORCE)");
        }
    }

    @Test
    void createsTheHmSchema() {
        assertThat(setup("hm")).isEqualTo(CliCommand.EXIT_OK);

        assertThat(tables("hm")).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM pg_proc WHERE pronamespace = 'hm'::regnamespace", target))
                .isEqualTo(3);
        assertThat(schemaExists("catalog")).isFalse();
    }

    @Test
    void theRolesExistAsPlaceholdersAfterwards() {
        assertThat(setup("metadata")).isEqualTo(CliCommand.EXIT_OK);

        for (String role : ROLES) {
            assertThat(count("SELECT count(*) FROM pg_roles WHERE rolname = ?", admin, role)).as(role).isEqualTo(1);
        }
    }

    @Test
    void createsTheMetadataSchemas() {
        assertThat(setup("metadata")).isEqualTo(CliCommand.EXIT_OK);

        assertThat(tables("catalog")).isEqualTo(10);
        assertThat(tables("access")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pg_views "
                + "WHERE schemaname = 'catalog' AND viewname = 'consent_group_current'", target)).isEqualTo(1);
        assertThat(schemaExists("hm")).isFalse();
    }

    @Test
    void runsBothScriptsInOneDatabase() {
        assertThat(setup("hm", "metadata", "hm")).isEqualTo(CliCommand.EXIT_OK);

        assertThat(tables("hm")).isEqualTo(8);
        assertThat(tables("catalog")).isEqualTo(10);
        assertThat(tables("access")).isEqualTo(3);
    }

    @Test
    void createsAMissingDatabase() {
        admin.execute("DROP DATABASE " + database);

        assertThat(setup("metadata")).isEqualTo(CliCommand.EXIT_OK);

        assertThat(count("SELECT count(*) FROM pg_database WHERE datname = ?", admin, database)).isEqualTo(1);
        assertThat(tables("catalog")).isEqualTo(10);
    }

    @Test
    void aUserWhoCannotCreateTheDatabaseStops() {
        admin.execute("DROP DATABASE " + database);
        String user = "setup_test_user_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + user + " LOGIN PASSWORD 'test-only' NOCREATEDB");
        try {
            int status = Commands.dispatch("setup-db", "--db.host=" + HOST, "--db.port=" + PORT,
                    "--db.name=" + database, "--db.user=" + user, "--db.password=test-only", "metadata")
                    .orElseThrow();

            assertThat(status).isEqualTo(CliCommand.EXIT_ERROR);
            assertThat(count("SELECT count(*) FROM pg_database WHERE datname = ?", admin, database)).isZero();
        } finally {
            admin.execute("DROP ROLE " + user);
        }
    }

    @Test
    void aFailedScriptChangesNothing() {
        target.execute("CREATE SCHEMA access");  // metadata.sql creates catalog, then fails on access

        assertThat(setup("metadata")).isEqualTo(SetupDbCommand.EXIT_SCRIPT_FAILED);

        assertThat(schemaExists("catalog")).isFalse();
        assertThat(tables("access")).isZero();
    }

    @Test
    void aFailedScriptStopsTheRestAndKeepsTheOnesBefore() {
        assertThat(setup("hm")).isEqualTo(CliCommand.EXIT_OK);

        assertThat(setup("metadata", "hm")).isEqualTo(SetupDbCommand.EXIT_SCRIPT_FAILED);  // hm exists

        assertThat(tables("catalog")).isEqualTo(10);
        assertThat(tables("hm")).isEqualTo(8);
    }

    private int setup(String... scripts) {
        List<String> args = new ArrayList<>(List.of("setup-db", "--db.host=" + HOST, "--db.port=" + PORT,
                "--db.name=" + database, "--db.user=" + USER));
        if (PASSWORD != null) {
            args.add("--db.password=" + PASSWORD);
        }
        args.addAll(List.of(scripts));
        return Commands.dispatch(args.toArray(String[]::new)).orElseThrow();
    }

    /** Tables in {@code schema}, partitions not counted. */
    private int tables(String schema) {
        return count("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relkind IN ('r', 'p') AND NOT c.relispartition", target, schema);
    }

    private boolean schemaExists(String schema) {
        return count("SELECT count(*) FROM pg_namespace WHERE nspname = ?", target, schema) > 0;
    }

    /** The single number a count(*) query returns. */
    private static int count(String sql, JdbcTemplate jdbc, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args), sql);
    }

    private DriverManagerDataSource dataSource(String name) {
        return new DriverManagerDataSource("jdbc:postgresql://" + HOST + ":" + PORT + "/" + name, USER, PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

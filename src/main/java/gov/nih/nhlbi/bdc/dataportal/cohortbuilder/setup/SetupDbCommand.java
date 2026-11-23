package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.CliCommand;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.PostgresConnectionOptions;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load.JsonObjectPathLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/**
 * The {@code setup-db} command: runs bundled SQL scripts against a database, each
 * creating its schemas, tables, indexes, views, functions and grants. The scripts to
 * run are named as arguments ({@link #SCRIPTS}). See {@link #USAGE}.
 *
 * <p>Takes the same connection options as {@code load} ({@link PostgresConnectionOptions}).
 * If the database doesn't exist, it's created first (owned by the user), by connecting to
 * the maintenance database {@code --setup.maintenance-db} (default {@value #DEFAULT_MAINTENANCE_DB}).
 * The scripts are copies of schema/db and create their objects unconditionally, so they
 * are for a database that doesn't have them yet. The roles they grant to ({@link #ROLES})
 * are created NOLOGIN first when missing: placeholders, not users; when the command
 * creates {@value #OWNER_ROLE}, a user who isn't a superuser is made a member of it, since
 * the scripts change its default privileges. A script is sent as one statement, so the
 * server, not this class, splits it, and runs it as one implicit transaction: a script
 * that fails changes nothing. The scripts run in the order named, and a failed script
 * stops the ones after it; those before it stay committed.
 *
 * <p>Exit status: 0 when every script ran, 1 when a script failed (that one changed
 * nothing), 2 on a usage error, when PostgreSQL is unavailable, or when the database or
 * a role couldn't be created.
 */
public class SetupDbCommand extends CliCommand {

    public static final int EXIT_SCRIPT_FAILED = 1;

    /** The roles each script grants to; missing ones are created NOLOGIN before it runs. */
    public static final Map<String, List<String>> ROLES = Map.of(
            "hm", List.of("bdc_dp_owner", "rebuild_job", "api_counting", "api_public"),
            "metadata", List.of("bdc_dp_owner", "rebuild_job", "api_search", "api_counting", "api_public",
                    "clearinghouse"));

    /** The role the scripts change default privileges for, which needs membership in it. */
    public static final String OWNER_ROLE = "bdc_dp_owner";

    /** The database connected to for CREATE DATABASE; every PostgreSQL server has it. */
    public static final String DEFAULT_MAINTENANCE_DB = "postgres";

    private static final String INVALID_CATALOG_NAME = "3D000";  // the database doesn't exist
    private static final String DUPLICATE_DATABASE = "42P04";
    private static final String DUPLICATE_OBJECT = "42710";  // e.g. the role exists

    /** The scripts by the name given on the command line: classpath resource of each. */
    public static final Map<String, String> SCRIPTS;

    static {
        Map<String, String> scripts = new LinkedHashMap<>();  // in the order help lists them
        scripts.put("hm", "db/hm_index.sql");                 // schema hm, for database bdc_dp_hm_index
        scripts.put("metadata", "db/metadata.sql");           // schemas catalog and access, for bdc_dp_metadata
        SCRIPTS = Collections.unmodifiableMap(scripts);
    }

    public static final String USAGE = """
            Usage: java -jar app.jar setup-db --db.name=DATABASE --db.user=USER [OPTIONS] SCRIPT...
              Creates schemas in a new PostgreSQL database by running the bundled SQL scripts named,
              in order. Creates the database first if it doesn't exist (the user needs CREATEDB),
              and the roles the scripts grant to, as NOLOGIN placeholders, if they don't (CREATEROLE).
              A script that fails (e.g. its objects already exist) changes nothing.
            Scripts:
              hm                            schema hm (%s), for bdc_dp_hm_index
              metadata                      schemas catalog and access (%s), for bdc_dp_metadata
            %sOptions:
              --setup.maintenance-db=NAME   database to connect to for creating DATABASE (default %s)
            Exit status: 0 all set up, 1 a script failed (it changed nothing), 2 usage error,
              PostgreSQL unavailable, or the database or a role couldn't be created.
            """.formatted(SCRIPTS.get("hm"), SCRIPTS.get("metadata"),
            PostgresConnectionOptions.usage("database to set up (created if missing)"), DEFAULT_MAINTENANCE_DB);

    private static final Logger log = LoggerFactory.getLogger(SetupDbCommand.class);

    @Override
    protected int execute(ApplicationArguments args) throws IOException {
        Set<String> names = new LinkedHashSet<>(args.getNonOptionArgs());  // repeated names run once
        List<String> unknown = names.stream().filter(n -> !SCRIPTS.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            System.err.println("setup-db: unknown scripts: " + String.join(", ", unknown)
                    + " (choose from " + String.join(", ", SCRIPTS.keySet()) + ")");
            System.err.print(USAGE);
            return EXIT_ERROR;
        }
        List<String> missing = new ArrayList<>(PostgresConnectionOptions.missing(args));
        if (names.isEmpty()) {
            missing.add("SCRIPT");
        }
        if (!missing.isEmpty()) {
            System.err.println("setup-db: missing " + String.join(", ", missing));
            System.err.print(USAGE);
            return EXIT_ERROR;
        }
        PostgresConnectionOptions connection;
        try {
            connection = PostgresConnectionOptions.from(args);
        } catch (IllegalArgumentException e) {
            System.err.println("setup-db: " + e.getMessage());
            return EXIT_ERROR;
        }

        String maintenanceDb = orDefault(option(args, "setup.maintenance-db"), DEFAULT_MAINTENANCE_DB);

        Map<String, String> scripts = new LinkedHashMap<>();
        for (String name : names) {
            scripts.put(name, script(name));
        }
        log.info("Setting up {} with {}", connection, String.join(", ", scripts.keySet()));

        Connection opened;
        try {
            opened = connectCreatingDatabase(connection, maintenanceDb);
        } catch (CreateDatabaseFailed e) {
            log.error("Stopped: can't create database {}: {}", connection.database(), e.getMessage());
            return EXIT_ERROR;
        } catch (SQLException e) {
            log.error("Stopped: PostgreSQL is unavailable: {}", e.getMessage());
            return EXIT_ERROR;
        }
        try (Connection db = opened) {
            db.setAutoCommit(true);  // the server runs each script as one implicit transaction
            try {
                createMissingRoles(db, scripts.keySet());
            } catch (SQLException e) {
                if (JsonObjectPathLoader.connectionFailure(e) != null) {
                    throw e;
                }
                log.error("Stopped: can't create the roles the scripts grant to: {}", e.getMessage());
                return EXIT_ERROR;
            }
            for (Map.Entry<String, String> script : scripts.entrySet()) {
                String resource = SCRIPTS.get(script.getKey());
                log.info("Running {} ({})", script.getKey(), resource);
                try {
                    run(db, script.getValue());
                } catch (SQLException e) {
                    if (JsonObjectPathLoader.connectionFailure(e) != null) {
                        throw e;
                    }
                    log.error("Script {} failed; its transaction was rolled back, it changed nothing: {}",
                            script.getKey(), e.getMessage());
                    return EXIT_SCRIPT_FAILED;
                }
                log.info("Ran {} ({})", script.getKey(), resource);
            }
            log.info("Set up {} with {}", connection, String.join(", ", scripts.keySet()));
            return EXIT_OK;
        } catch (SQLException e) {
            log.error("Stopped: PostgreSQL is unavailable: {}", e.getMessage());
            return EXIT_ERROR;
        }
    }

    /**
     * Connects to the database, first creating it, from {@code maintenanceDb}, if the server
     * says it doesn't exist. Creating it concurrently with another session is fine.
     */
    private static Connection connectCreatingDatabase(PostgresConnectionOptions connection, String maintenanceDb)
            throws SQLException, CreateDatabaseFailed {
        try {
            return connection.dataSource().getConnection();
        } catch (SQLException e) {
            if (!INVALID_CATALOG_NAME.equals(e.getSQLState())) {
                throw e;
            }
        }
        log.info("Database {} does not exist; creating it from database {}", connection.database(), maintenanceDb);
        try (Connection admin = connection.withDatabase(maintenanceDb).dataSource().getConnection();
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(connection.database()));
            log.info("Created database {}", connection.database());
        } catch (SQLException e) {
            if (DUPLICATE_DATABASE.equals(e.getSQLState())) {
                log.info("Database {} was created by another session", connection.database());
            } else if (JsonObjectPathLoader.connectionFailure(e) != null) {
                throw e;
            } else {
                throw new CreateDatabaseFailed(e.getMessage());
            }
        }
        return connection.dataSource().getConnection();
    }

    /**
     * Creates, NOLOGIN, each role the named scripts grant to that the server lacks. Roles
     * belong to the server, not the database, and each is committed on its own, so they
     * stay even if a script fails. When it creates {@value #OWNER_ROLE} and the user isn't
     * a superuser, it makes the user a member, which ALTER DEFAULT PRIVILEGES FOR ROLE
     * needs; PostgreSQL lets a role's creator grant it.
     */
    private static void createMissingRoles(Connection db, Set<String> scriptNames) throws SQLException {
        Set<String> roles = new LinkedHashSet<>();
        scriptNames.forEach(name -> roles.addAll(ROLES.get(name)));
        try (Statement statement = db.createStatement()) {
            for (String role : roles) {
                if (roleExists(db, role)) {
                    continue;
                }
                try {
                    statement.execute("CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN");
                    log.info("Created missing role {} (NOLOGIN placeholder)", role);
                } catch (SQLException e) {
                    if (!DUPLICATE_OBJECT.equals(e.getSQLState())) {
                        throw e;
                    }
                    continue;  // created by another session
                }
                if (role.equals(OWNER_ROLE) && !isSuperuser(db)) {
                    statement.execute("GRANT " + quoteIdentifier(role) + " TO CURRENT_USER");
                    log.info("Made the user a member of {}", role);
                }
            }
        }
    }

    private static boolean roleExists(Connection db, String role) throws SQLException {
        try (var query = db.prepareStatement("SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?")) {
            query.setString(1, role);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean isSuperuser(Connection db) throws SQLException {
        try (Statement statement = db.createStatement();
                var rows = statement.executeQuery(
                        "SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = CURRENT_USER")) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    /** {@code name} as a quoted SQL identifier; case is kept, as JDBC connects by the exact name. */
    static String quoteIdentifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** CREATE DATABASE failed: no privilege, or the maintenance database is missing. */
    private static final class CreateDatabaseFailed extends Exception {
        CreateDatabaseFailed(String message) {
            super(message);
        }
    }

    /** Runs one script on {@code db} and logs the server's notices. */
    private static void run(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            boolean committed = false;
            try {
                // Step through every statement's result, so an error in any of them is thrown here.
                int results = 0;
                for (boolean resultSet = statement.execute(sql);
                        resultSet || statement.getUpdateCount() != -1;
                        resultSet = statement.getMoreResults()) {
                    results++;
                }
                log.debug("The script's statements returned {} results", results);
                committed = true;
            } finally {
                logNotices(statement.getWarnings(), committed);
            }
        }
    }

    /** The text of the script registered as {@code name}, from the classpath. */
    static String script(String name) throws IOException {
        String resource = SCRIPTS.get(name);
        try (InputStream in = SetupDbCommand.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Logs the server's NOTICE messages. When the script failed, they're marked as
     * rolled back, since none of it was kept.
     */
    private static void logNotices(SQLWarning warning, boolean committed) {
        String prefix = committed ? "PostgreSQL: " : "PostgreSQL (rolled back): ";
        for (SQLWarning w = warning; w != null; w = w.getNextWarning()) {
            log.info("{}{}", prefix, w.getMessage());
        }
    }
}

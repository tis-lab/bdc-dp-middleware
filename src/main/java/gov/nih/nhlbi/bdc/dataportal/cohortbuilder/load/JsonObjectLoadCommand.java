package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.CliCommand;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/**
 * The {@code load} command: loads files with {@link JsonObjectPathLoader}. See
 * {@link #USAGE}.
 *
 * <p>Each PATH is a file, or a directory searched recursively. The database and user
 * are required ({@code --db.name}, {@code --db.user}); there are no defaults. The
 * password is {@code --db.password}, else the {@code PGPASSWORD} environment variable,
 * else none (for servers that don't ask for one). The command only connects to that
 * database as that user, and only creates the schema and table it loads into, when
 * they're missing.
 *
 * <p>Exit status: 0 when every file loaded (some rows may have been skipped; they're
 * logged), 1 when some file failed, 2 on a usage error or when PostgreSQL is
 * unavailable (no connection, or the connection was lost).
 */
public class JsonObjectLoadCommand extends CliCommand {

    public static final int EXIT_FILES_FAILED = 1;

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 5432;

    public static final String USAGE = """
            Usage: java -jar app.jar load --db.name=DATABASE --db.user=USER [OPTIONS] PATH...
              Loads the JSON objects in each file, or each supported file under each
              directory, into PostgreSQL. Files: .yaml .yml .json .ndjson .jsonl, optionally .gz.
            Connection (name and user are required):
              --db.name=DATABASE            database to load into
              --db.user=USER                user to connect as
              --db.password=PASSWORD        its password (default: PGPASSWORD, else none)
              --db.host=HOST                (default %s)
              --db.port=PORT                (default %d)
            Options:
              --load.schema=NAME            schema of the table (default %s)
              --load.table=NAME             table (default %s)
              --load.batch-size=N           rows per batch and commit (default %d)
            Exit status: 0 all files loaded, 1 some file failed, 2 usage error or PostgreSQL unavailable.
            """.formatted(DEFAULT_HOST, DEFAULT_PORT, JsonObjectLoader.DEFAULT_SCHEMA,
            JsonObjectLoader.DEFAULT_TABLE, JsonObjectLoader.DEFAULT_BATCH_SIZE);

    private static final Logger log = LoggerFactory.getLogger(JsonObjectLoadCommand.class);

    @Override
    protected int execute(ApplicationArguments args) {
        List<Path> paths = args.getNonOptionArgs().stream().map(Path::of).toList();
        String database = option(args, "db.name");
        String user = option(args, "db.user");
        List<String> missing = new ArrayList<>();
        if (database == null) {
            missing.add("--db.name");
        }
        if (user == null) {
            missing.add("--db.user");
        }
        if (paths.isEmpty()) {
            missing.add("PATH");
        }
        if (!missing.isEmpty()) {
            System.err.println("load: missing " + String.join(", ", missing));
            System.err.print(USAGE);
            return EXIT_ERROR;
        }

        int port;
        int batchSize;
        try {
            port = intOption(args, "db.port", DEFAULT_PORT);
            batchSize = intOption(args, "load.batch-size", JsonObjectLoader.DEFAULT_BATCH_SIZE);
        } catch (IllegalArgumentException e) {
            System.err.println("load: " + e.getMessage());
            return EXIT_ERROR;
        }

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {orDefault(option(args, "db.host"), DEFAULT_HOST)});
        dataSource.setPortNumbers(new int[] {port});
        dataSource.setDatabaseName(database);
        dataSource.setUser(user);
        String password = option(args, "db.password");
        if (password == null) {
            password = System.getenv("PGPASSWORD");
        }
        if (password != null) {
            dataSource.setPassword(password);
        }

        String schema = orDefault(option(args, "load.schema"), JsonObjectLoader.DEFAULT_SCHEMA);
        String table = orDefault(option(args, "load.table"), JsonObjectLoader.DEFAULT_TABLE);
        JsonObjectLoader loader = new JsonObjectLoader(dataSource, schema, table, batchSize);
        log.info("Loading into {}.{} of database {} on {}:{} as {}", schema, table, database,
                dataSource.getServerNames()[0], dataSource.getPortNumbers()[0], user);

        try {
            JsonObjectPathLoader.Result result = new JsonObjectPathLoader(file -> loader).load(paths);
            return result.failed().isEmpty() ? EXIT_OK : EXIT_FILES_FAILED;
        } catch (SQLException e) {
            log.error("Stopped: PostgreSQL is unavailable: {}", e.getMessage());
            return EXIT_ERROR;
        }
    }

    /**
     * The last value of {@code --name=value} on the command line, or null if missing or
     * blank. Only the command line counts, so environment variables like DB_NAME can't
     * stand in for a required option.
     */
    private static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getLast();
        return value == null || value.isBlank() ? null : value;
    }

    private static int intOption(ApplicationArguments args, String name, int fallback) {
        String value = option(args, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number: " + value);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}

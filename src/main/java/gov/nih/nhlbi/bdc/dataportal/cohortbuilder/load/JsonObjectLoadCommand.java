package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.CliCommand;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.PostgresConnectionOptions;
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

    public static final String USAGE = """
            Usage: java -jar app.jar load --db.name=DATABASE --db.user=USER [OPTIONS] PATH...
              Loads the JSON objects in each file, or each supported file under each
              directory, into PostgreSQL. Files: .yaml .yml .json .ndjson .jsonl, optionally .gz.
            %sOptions:
              --load.schema=NAME            schema of the table (default %s)
              --load.table=NAME             table (default %s)
              --load.batch-size=N           rows per batch and commit (default %d)
            Exit status: 0 all files loaded, 1 some file failed, 2 usage error or PostgreSQL unavailable.
            """.formatted(PostgresConnectionOptions.usage("database to load into"), JsonObjectLoader.DEFAULT_SCHEMA,
            JsonObjectLoader.DEFAULT_TABLE, JsonObjectLoader.DEFAULT_BATCH_SIZE);

    private static final Logger log = LoggerFactory.getLogger(JsonObjectLoadCommand.class);

    @Override
    protected int execute(ApplicationArguments args) {
        List<Path> paths = args.getNonOptionArgs().stream().map(Path::of).toList();
        List<String> missing = new ArrayList<>(PostgresConnectionOptions.missing(args));
        if (paths.isEmpty()) {
            missing.add("PATH");
        }
        if (!missing.isEmpty()) {
            System.err.println("load: missing " + String.join(", ", missing));
            System.err.print(USAGE);
            return EXIT_ERROR;
        }

        PostgresConnectionOptions connection;
        int batchSize;
        try {
            connection = PostgresConnectionOptions.from(args);
            batchSize = intOption(args, "load.batch-size", JsonObjectLoader.DEFAULT_BATCH_SIZE);
        } catch (IllegalArgumentException e) {
            System.err.println("load: " + e.getMessage());
            return EXIT_ERROR;
        }
        PGSimpleDataSource dataSource = connection.dataSource();

        String schema = orDefault(option(args, "load.schema"), JsonObjectLoader.DEFAULT_SCHEMA);
        String table = orDefault(option(args, "load.table"), JsonObjectLoader.DEFAULT_TABLE);
        JsonObjectLoader loader = new JsonObjectLoader(dataSource, schema, table, batchSize);
        log.info("Loading into {}.{} of {}", schema, table, connection);

        try {
            JsonObjectPathLoader.Result result = new JsonObjectPathLoader(file -> loader).load(paths);
            return result.failed().isEmpty() ? EXIT_OK : EXIT_FILES_FAILED;
        } catch (SQLException e) {
            log.error("Stopped: PostgreSQL is unavailable: {}", e.getMessage());
            return EXIT_ERROR;
        }
    }
}

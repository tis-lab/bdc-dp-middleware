package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.ApplicationArguments;

/**
 * The PostgreSQL connection options shared by the commands that connect to a database:
 * {@code --db.name} and {@code --db.user} (required, no defaults), {@code --db.password}
 * (else the {@code PGPASSWORD} environment variable, else none), {@code --db.host} and
 * {@code --db.port}. Only the command line counts, so environment variables like DB_NAME
 * can't stand in for a required option.
 */
public record PostgresConnectionOptions(String host, int port, String database, String user, String password) {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 5432;

    /**
     * The usage lines for these options, {@code databasePurpose} describing
     * {@code --db.name} (e.g. "database to load into").
     */
    public static String usage(String databasePurpose) {
        return """
                Connection (name and user are required):
                  --db.name=DATABASE            %s
                  --db.user=USER                user to connect as
                  --db.password=PASSWORD        its password (default: PGPASSWORD, else none)
                  --db.host=HOST                (default %s)
                  --db.port=PORT                (default %d)
                """.formatted(databasePurpose, DEFAULT_HOST, DEFAULT_PORT);
    }

    /** The required options missing from {@code args}, as {@code --db.name}, {@code --db.user}. */
    public static List<String> missing(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        if (CliCommand.option(args, "db.name") == null) {
            missing.add("--db.name");
        }
        if (CliCommand.option(args, "db.user") == null) {
            missing.add("--db.user");
        }
        return missing;
    }

    /**
     * The options in {@code args}, after {@link #missing} found none missing.
     *
     * @throws IllegalArgumentException if {@code --db.port} isn't a number
     */
    public static PostgresConnectionOptions from(ApplicationArguments args) {
        String password = CliCommand.option(args, "db.password");
        if (password == null) {
            password = System.getenv("PGPASSWORD");
        }
        return new PostgresConnectionOptions(
                CliCommand.orDefault(CliCommand.option(args, "db.host"), DEFAULT_HOST),
                CliCommand.intOption(args, "db.port", DEFAULT_PORT),
                CliCommand.option(args, "db.name"),
                CliCommand.option(args, "db.user"),
                password);
    }

    /** The same server, user and password, connecting to {@code otherDatabase}. */
    public PostgresConnectionOptions withDatabase(String otherDatabase) {
        return new PostgresConnectionOptions(host, port, otherDatabase, user, password);
    }

    /** A data source for this database and user; nothing is connected yet. */
    public PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {host});
        dataSource.setPortNumbers(new int[] {port});
        dataSource.setDatabaseName(database);
        dataSource.setUser(user);
        if (password != null) {
            dataSource.setPassword(password);
        }
        return dataSource;
    }

    /** Never shows the password. */
    @Override
    public @NonNull String toString() {
        return "database " + database + " on " + host + ":" + port + " as " + user;
    }
}

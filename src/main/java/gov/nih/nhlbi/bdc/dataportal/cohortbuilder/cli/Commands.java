package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load.JsonObjectLoadCommand;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.setup.SetupDbCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/**
 * The commands the jar runs instead of the service: {@code java -jar app.jar NAME [ARGS...]}.
 *
 * <p>The service starts when there are no arguments or the first one is an option
 * ({@code --key=value}). Otherwise the first argument names a command: {@code help}
 * lists them, {@code NAME --help} shows one's usage, and an unknown name is an error.
 *
 * <p>To add a command, write a {@link CliCommand} and add it to {@link #COMMANDS}.
 */
public final class Commands {

    /** A registered command: its name, a one-line description, its usage text and class. */
    public record Command(String name, String description, String usage, Class<? extends CliCommand> type) {}

    private static final Map<String, Command> COMMANDS = registry(
            new Command("load", "Load JSON objects from files into a PostgreSQL table",
                    JsonObjectLoadCommand.USAGE, JsonObjectLoadCommand.class),
            new Command("setup-db", "Create the hm or metadata schemas in a new PostgreSQL database",
                    SetupDbCommand.USAGE, SetupDbCommand.class));

    private static final Logger log = LoggerFactory.getLogger(Commands.class);

    private Commands() {}

    /** The registered commands, in the order {@code help} lists them. */
    public static Map<String, Command> all() {
        return COMMANDS;
    }

    /**
     * Runs the command {@code args} name and returns its exit status, or returns empty
     * when {@code args} are for the service.
     */
    public static OptionalInt dispatch(String... args) {
        if (args.length == 0 || args[0].startsWith("-")) {
            return OptionalInt.empty();
        }
        String name = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (name.equals("help")) {
            printHelp(System.out);
            return OptionalInt.of(CliCommand.EXIT_OK);
        }
        Command command = COMMANDS.get(name);
        if (command == null) {
            System.err.println("Unknown command: " + name);
            printHelp(System.err);
            return OptionalInt.of(CliCommand.EXIT_ERROR);
        }
        if (List.of(rest).contains("--help") || List.of(rest).contains("-h")) {
            System.out.print(command.usage());
            return OptionalInt.of(CliCommand.EXIT_OK);
        }
        return OptionalInt.of(run(command, rest));
    }

    /** Starts {@code command}'s Spring application with {@code args} and returns its exit status. */
    static int run(Command command, String... args) {
        SpringApplication app = new SpringApplication(command.type());
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.setRegisterShutdownHook(false);
        // application.yml takes the log level from APP_LOG_LEVEL, which the service requires.
        app.setDefaultProperties(Map.of("APP_LOG_LEVEL", "INFO"));
        try {
            return SpringApplication.exit(app.run(args));
        } catch (RuntimeException e) {
            log.error("Command {} failed: {}", command.name(), e.getMessage(), e);
            return CliCommand.EXIT_ERROR;
        }
    }

    private static void printHelp(PrintStream out) {
        out.println("Usage: java -jar app.jar [COMMAND [ARGS...]]");
        out.println("  Without a command, starts the service.");
        out.println("Commands:");
        out.printf("  %-10s %s%n", "help", "List the commands");
        COMMANDS.values().forEach(c -> out.printf("  %-10s %s%n", c.name(), c.description()));
        out.println("Run 'java -jar app.jar COMMAND --help' for a command's options.");
    }

    private static Map<String, Command> registry(Command... commands) {
        Map<String, Command> map = new LinkedHashMap<>();
        for (Command c : commands) {
            if (c.name().equals("help") || c.name().startsWith("-") || map.put(c.name(), c) != null) {
                throw new IllegalStateException("Bad or repeated command name: " + c.name());
            }
        }
        return Collections.unmodifiableMap(map);
    }
}

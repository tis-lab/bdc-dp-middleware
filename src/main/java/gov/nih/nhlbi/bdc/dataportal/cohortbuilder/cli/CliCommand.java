package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * A command run with {@code java -jar app.jar NAME [ARGS...]} instead of the service.
 *
 * <p>A subclass is the primary source of its own small Spring application, started by
 * {@link Commands}, without the web server or the service's beans. If it needs Spring
 * infrastructure, it brings in only that (e.g. with {@code @ImportAutoConfiguration})
 * and gets those beans through its constructor.
 * Don't annotate it with {@code @Component} or {@code @Configuration}, or the service's
 * component scan would pick it up. Register it in {@link Commands}.
 *
 * <p>Options of the form {@code --key=value} are Spring properties, read from the
 * {@code Environment} or from {@link ApplicationArguments}; other arguments are
 * {@link ApplicationArguments#getNonOptionArgs() non-option arguments}.
 */
public abstract class CliCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final int EXIT_OK = 0;
    /** A usage error, or a failure that kept the command from doing its work. */
    public static final int EXIT_ERROR = 2;

    private int exitCode = EXIT_OK;

    /** Does the command's work and returns its exit status. */
    protected abstract int execute(ApplicationArguments args) throws Exception;

    @Override
    public final void run(@NonNull ApplicationArguments args) throws Exception {
        exitCode = execute(args);
    }

    @Override
    public final int getExitCode() {
        return exitCode;
    }

    /**
     * The last value of {@code --name=value} on the command line, or null if missing or
     * blank. Only the command line counts, so environment variables like DB_NAME can't
     * stand in for a required option.
     */
    protected static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getLast();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The number given as {@code --name=N}, or {@code fallback} if missing.
     *
     * @throws IllegalArgumentException if the value isn't a number
     */
    protected static int intOption(ApplicationArguments args, String name, int fallback) {
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

    protected static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}

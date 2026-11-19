package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli;

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
    public final void run(ApplicationArguments args) throws Exception {
        exitCode = execute(args);
    }

    @Override
    public final int getExitCode() {
        return exitCode;
    }
}

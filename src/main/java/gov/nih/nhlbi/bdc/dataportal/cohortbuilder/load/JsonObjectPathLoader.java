package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the files at a set of paths, each with its own {@link JsonObjectLoader}.
 *
 * <p>A path that is a file is loaded as it is, whatever its extension. A path that is a
 * directory is searched recursively, and every regular file in it that its loader
 * {@link JsonObjectLoader#supports supports} is loaded, in path order. Symbolic links
 * to directories aren't followed.
 *
 * <p>A file that fails (no type in its name, unreadable, a database error that isn't
 * about a single row, ...) is logged and recorded in the result, and the next file is
 * loaded. Batches the failed file committed before the error stay committed.
 *
 * <p>The exception is losing the database: when a file fails because no connection can
 * be obtained ({@link DatabaseUnavailableException}: down, unreachable, bad credentials
 * or database) or the connection is lost (SQLSTATE class 08, or 57P01-57P03), loading
 * stops and that error is thrown, since every later file would fail the same way.
 */
public class JsonObjectPathLoader {

    private static final Logger log = LoggerFactory.getLogger(JsonObjectPathLoader.class);

    /** What a {@link #load} did: the files loaded, and the paths that failed with why. */
    public record Result(List<JsonObjectLoader.LoadResult> loaded, Map<Path, String> failed) {

        public long inserted() {
            return loaded.stream().mapToLong(JsonObjectLoader.LoadResult::inserted).sum();
        }

        public long skipped() {
            return loaded.stream().mapToLong(JsonObjectLoader.LoadResult::skipped).sum();
        }
    }

    private final Function<Path, ? extends JsonObjectLoader> loaders;

    /** Loads every file with a default {@link JsonObjectLoader} on {@code dataSource}. */
    public JsonObjectPathLoader(DataSource dataSource) {
        this(file -> new JsonObjectLoader(dataSource));
        Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Loads each file with the loader {@code loaders} creates for it, so a file can get
     * a loader subclass, table or batch size of its own.
     */
    public JsonObjectPathLoader(Function<Path, ? extends JsonObjectLoader> loaders) {
        this.loaders = Objects.requireNonNull(loaders, "loaders");
    }

    public Result load(Path... paths) throws SQLException {
        return load(Arrays.asList(paths));
    }

    /**
     * Loads the files at {@code paths}, in the given order.
     *
     * @throws SQLException if PostgreSQL can't be reached; files loaded before stay loaded
     */
    public Result load(Collection<Path> paths) throws SQLException {
        List<JsonObjectLoader.LoadResult> loaded = new ArrayList<>();
        Map<Path, String> failed = new LinkedHashMap<>();
        for (Path path : paths) {
            List<Path> files;
            try {
                files = files(path);
            } catch (IOException | RuntimeException e) {
                fail(failed, path, e);
                continue;
            }
            if (Files.isDirectory(path)) {
                log.info("Found {} supported files under {}", files.size(), path);
            }
            for (Path file : files) {
                try {
                    loaded.add(loaders.apply(file).load(file));
                } catch (Exception e) {
                    SQLException unreachable = connectionFailure(e);
                    if (unreachable != null) {
                        log.error("Stopped loading at {}: PostgreSQL is unavailable ({} files loaded, {} failed before)",
                                file, loaded.size(), failed.size());
                        throw unreachable;
                    }
                    fail(failed, file, e);
                }
            }
        }
        Result result = new Result(List.copyOf(loaded), Collections.unmodifiableMap(failed));
        log.info("Loaded {} files: inserted {}, skipped {}; {} failed",
                loaded.size(), result.inserted(), result.skipped(), failed.size());
        return result;
    }

    /**
     * The files to load for {@code path}: the path itself if it's a file, or the
     * supported regular files under it, sorted, if it's a directory.
     */
    protected List<Path> files(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                return walk.filter(Files::isRegularFile).filter(this::supports).sorted().toList();
            }
        }
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        throw new IOException("Not a file or directory: " + path);
    }

    /** Whether a file found in a directory is loaded: its loader decides. */
    protected boolean supports(Path file) {
        return loaders.apply(file).supports(file);
    }

    /** The SQLException in {@code e}'s causes that means the database is unreachable, or null. */
    static SQLException connectionFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DatabaseUnavailableException
                    || t instanceof SQLTransientConnectionException || t instanceof SQLNonTransientConnectionException) {
                return (SQLException) t;
            }
            if (t instanceof SQLException s && s.getSQLState() != null
                    && (s.getSQLState().startsWith("08") || s.getSQLState().matches("57P0[123]"))) {
                return s;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static void fail(Map<Path, String> failed, Path path, Exception e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        failed.put(path, reason);
        log.error("Failed to load {}: {}", path, reason, e);
    }
}

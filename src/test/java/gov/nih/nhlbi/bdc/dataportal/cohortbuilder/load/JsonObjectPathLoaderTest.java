package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** File selection and failure handling, with a loader that records instead of inserting. */
class JsonObjectPathLoaderTest {

    @TempDir
    Path dir;

    private final List<Path> loadedFiles = new ArrayList<>();

    /** Records each file it's asked to load; fails for files named {@code *fail*}. */
    private class RecordingLoader extends JsonObjectLoader {
        RecordingLoader() {
            super(new DriverManagerDataSource());
        }

        @Override
        public LoadResult load(Path file) throws IOException {
            if (file.getFileName().toString().contains("fail")) {
                throw new IOException("boom");
            }
            loadedFiles.add(file);
            return new LoadResult(file, typeOf(file), 2, 1, 1);
        }
    }

    private final JsonObjectPathLoader pathLoader = new JsonObjectPathLoader(file -> new RecordingLoader());

    @Test
    void loadsSupportedFilesInDirectoryRecursivelyInOrder() throws Exception {
        Path b = touch("b/S-T--data.ndjson.gz");
        Path a = touch("a/S-T--data.yaml");
        Path deep = touch("a/x/y/S-T--data.JSON");
        touch("a/README.md");
        touch("a/S-T--data.parquet");
        touch("S-T--data.jsonl.bak");

        JsonObjectPathLoader.Result result = pathLoader.load(dir);

        assertThat(loadedFiles).containsExactly(a, deep, b);
        assertThat(result.loaded()).hasSize(3);
        assertThat(result.inserted()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void loadsAFileWhateverItsExtension() throws Exception {
        Path file = touch("S-T--data.txt");

        pathLoader.load(file);

        assertThat(loadedFiles).containsExactly(file);
    }

    @Test
    void recordsFailuresAndContinues() throws Exception {
        Path missing = dir.resolve("missing");
        Path failing = touch("d/S-T--fail.yaml");
        Path ok = touch("d/S-T--ok.yaml");
        Path noType = touch("notype.yaml");

        JsonObjectPathLoader.Result result =
                new JsonObjectPathLoader(file -> new RecordingLoader() {
                    @Override
                    public LoadResult load(Path f) throws IOException {
                        typeOf(f); // throws for a name without a type
                        return super.load(f);
                    }
                }).load(missing, dir.resolve("d"), noType);

        assertThat(loadedFiles).containsExactly(ok);
        assertThat(result.failed().keySet()).containsExactly(missing, failing, noType);
        assertThat(result.failed().get(failing)).contains("boom");
    }

    @Test
    void emptyDirectoryLoadsNothing() throws Exception {
        Files.createDirectories(dir.resolve("empty"));

        JsonObjectPathLoader.Result result = pathLoader.load(dir.resolve("empty"));

        assertThat(result.loaded()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void stopsAtFirstFileWhenPostgresIsUnreachable() throws Exception {
        touch("S-T--a.yaml");
        touch("S-T--b.yaml");
        touch("S-T--c.yaml");
        DriverManagerDataSource nowhere =
                new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/d?connectTimeout=2", "u", "p");
        List<Path> attempted = new ArrayList<>();
        JsonObjectPathLoader unreachable = new JsonObjectPathLoader(file -> new JsonObjectLoader(nowhere) {
            @Override
            public LoadResult load(Path f) throws IOException, SQLException {
                attempted.add(f);
                return super.load(f);
            }
        });

        assertThatThrownBy(() -> unreachable.load(dir))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).startsWith("08"));
        assertThat(attempted).hasSize(1);
    }

    @Test
    void rowAndOtherDatabaseErrorsDontStopLoading() {
        assertThat((Object) JsonObjectPathLoader.connectionFailure(new SQLException("dup", "23505"))).isNull();
        assertThat((Object) JsonObjectPathLoader.connectionFailure(new SQLException("denied", "42501"))).isNull();
        assertThat((Object) JsonObjectPathLoader.connectionFailure(new SQLException("shutdown", "57P01"))).isNotNull();
        assertThat((Object) JsonObjectPathLoader.connectionFailure(
                new RuntimeException(new SQLException("refused", "08001")))).isNotNull();
        assertThat((Object) JsonObjectPathLoader.connectionFailure(new DatabaseUnavailableException(
                "no role", new SQLException("role \"u\" does not exist", "28000")))).isNotNull();
    }

    private Path touch(String relative) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "");
    }
}

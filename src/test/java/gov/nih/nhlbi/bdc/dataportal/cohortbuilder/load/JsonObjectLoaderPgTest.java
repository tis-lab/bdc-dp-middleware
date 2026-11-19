package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.cli.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads into a real PostgreSQL, in a throwaway schema it drops afterwards.
 *
 * <p>Opt-in: runs only when PG_TEST_DB and PG_TEST_USER name a database and a user
 * that can create schemas in it; PG_TEST_PASSWORD, PG_TEST_HOST (default localhost)
 * and PG_TEST_PORT (default 5432) are optional. Otherwise skipped, so a build never
 * touches a server nobody chose. For example:
 * {@code PG_TEST_DB=mydb PG_TEST_USER=me PG_TEST_PASSWORD=... mvn test}
 */
class JsonObjectLoaderPgTest {

    private static final Path SAMPLE =
            Path.of("src/main/resources/data/study_one/SYNTH1-Participant--data.yaml");

    @TempDir
    Path dir;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private String schema;

    private static final String DB = env("PG_TEST_DB", null);
    private static final String USER = env("PG_TEST_USER", null);
    private static final String PASSWORD = env("PG_TEST_PASSWORD", null);
    private static final String HOST = env("PG_TEST_HOST", "localhost");
    private static final String PORT = env("PG_TEST_PORT", "5432");

    @BeforeEach
    void connect() {
        assumeTrue(DB != null && USER != null, "Set PG_TEST_DB and PG_TEST_USER to run the PostgreSQL tests");
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, PASSWORD);
        try (Connection ignored = dataSource.getConnection()) {
            // reachable
        } catch (SQLException e) {
            assumeTrue(false, "PostgreSQL not reachable: " + e.getMessage());
        }
        jdbc = new JdbcTemplate(dataSource);
        schema = "load_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void dropSchema() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void loadsSampleYamlAndCreatesTable() throws Exception {
        long documents = Files.readAllLines(SAMPLE).stream().filter(l -> l.startsWith("id: ")).count();

        JsonObjectLoader.LoadResult result = loader(1000).load(SAMPLE);

        assertThat(result.type()).isEqualTo("Participant");
        assertThat(result.inserted()).isEqualTo(documents).isPositive();
        assertThat(result.skipped()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".obj WHERE type = 'Participant' AND data->>'id' = id",
                Long.class)).isEqualTo(documents);
        assertThat(jdbc.queryForObject(
                "SELECT data->>'identity' FROM " + schema + ".obj WHERE id = 'ded1d538-866f-5c18-8aa2-0b24f1f08a5a'",
                String.class)).isEqualTo("100001");
        assertThat(jdbc.queryForList("SELECT DISTINCT file_path FROM " + schema + ".obj", String.class))
                .containsExactly(SAMPLE.toAbsolutePath().normalize().toString());
        assertThat(primaryKey()).containsExactly("type", "id", "file_path");
    }

    @Test
    void sameObjectInTwoFilesGivesTwoRows() throws Exception {
        Path a = Files.writeString(dir.resolve("A-Thing--data.json"), "{\"id\":\"x\",\"v\":1}");
        Path b = Files.writeString(Files.createDirectories(dir.resolve("sub")).resolve("../B-Thing--data.json"),
                "{\"id\":\"x\",\"v\":2}");

        assertThat(loader(10).load(a).inserted()).isEqualTo(1);
        assertThat(loader(10).load(b).inserted()).isEqualTo(1);
        assertThat(loader(10).load(a).skipped()).isEqualTo(1); // same file again

        assertThat(jdbc.queryForList("SELECT file_path FROM " + schema + ".obj WHERE id = 'x' ORDER BY file_path",
                String.class))
                .containsExactly(a.toAbsolutePath().toString(), dir.resolve("B-Thing--data.json").toAbsolutePath().toString());
    }

    @Test
    void replacesTheOldPrimaryKeyOfAnEmptyTable() throws Exception {
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".obj (type text, id text, data jsonb, PRIMARY KEY (type, id))");
        Path file = Files.writeString(dir.resolve("S-Thing--data.json"), "{\"id\":\"a\"}");

        assertThat(loader(10).load(file).inserted()).isEqualTo(1);
        assertThat(primaryKey()).containsExactly("type", "id", "file_path");
    }

    @Test
    void refusesToChangeTheKeyOfATableWithRowsWithoutFilePath() throws Exception {
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".obj (type text, id text, data jsonb, PRIMARY KEY (type, id))");
        jdbc.execute("INSERT INTO " + schema + ".obj VALUES ('Thing', 'old', '{}')");
        Path file = Files.writeString(dir.resolve("S-Thing--data.json"), "{\"id\":\"a\"}");

        assertThatThrownBy(() -> loader(10).load(file))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("rows without type, id or file_path")
                .hasMessageContaining("Nothing was changed");

        assertThat(primaryKey()).containsExactly("type", "id");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'obj' AND column_name = 'file_path'", Long.class, schema)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".obj", Long.class)).isEqualTo(1);
    }

    @Test
    void skipsExactlyTheBadRowsInEveryBatch() throws Exception {
        // 10 rows, batch size 4. Bad: a duplicate id (row 3 repeats row 1), a NUL
        // character jsonb rejects (row 6), and no id (row 9).
        StringBuilder ndjson = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            String line = switch (i) {
                case 3 -> "{\"id\":\"r1\",\"dup\":true}";
                case 6 -> "{\"id\":\"r6\",\"v\":\"bad\\u0000\"}";
                case 9 -> "{\"name\":\"no id\"}";
                default -> "{\"id\":\"r" + i + "\",\"v\":" + i + "}";
            };
            ndjson.append(line).append('\n');
        }
        Path file = Files.writeString(dir.resolve("S-Thing--data.ndjson"), ndjson);

        JsonObjectLoader.LoadResult result = loader(4).load(file);

        assertThat(result.read()).isEqualTo(10);
        assertThat(result.inserted()).isEqualTo(7);
        assertThat(result.skipped()).isEqualTo(3);
        Map<String, Object> rows = jdbc.queryForList("SELECT id, data->>'v' AS v FROM " + schema + ".obj")
                .stream().collect(Collectors.toMap(r -> (String) r.get("id"), r -> String.valueOf(r.get("v"))));
        assertThat(rows.keySet()).containsExactlyInAnyOrder("r1", "r2", "r4", "r5", "r7", "r8", "r10");
        assertThat(rows.get("r1")).isEqualTo("1"); // the first r1 was kept, not the duplicate
    }

    @Test
    void reloadingSkipsExistingRows() throws Exception {
        Path file = Files.writeString(dir.resolve("S-Thing--data.json"), "[{\"id\":\"a\"},{\"id\":\"b\"}]");
        assertThat(loader(10).load(file).inserted()).isEqualTo(2);

        JsonObjectLoader.LoadResult again = loader(10).load(file);
        assertThat(again.inserted()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
    }

    @Test
    void pathLoaderLoadsAStudyDirectory() throws Exception {
        JsonObjectPathLoader.Result result = new JsonObjectPathLoader(file -> loader(500))
                .load(SAMPLE.getParent());

        assertThat(result.failed()).isEmpty();
        assertThat(result.loaded()).hasSize(8);
        assertThat(result.skipped()).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT type FROM " + schema + ".obj", String.class))
                .containsExactlyInAnyOrder("Condition", "Demography", "DrugExposure", "MeasurementObservation",
                        "MeasurementObservationSet", "Participant", "Person", "Visit");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".obj", Long.class))
                .isEqualTo(result.inserted());
    }

    @Test
    void commandLoadsAPath() {
        int status = Commands.dispatch("load",
                "--db.host=" + HOST, "--db.port=" + PORT, "--db.name=" + DB, "--db.user=" + USER,
                "--db.password=" + (PASSWORD == null ? "" : PASSWORD),
                "--load.schema=" + schema, "--load.table=obj", "--load.batch-size=3",
                SAMPLE.toString()).orElseThrow();

        assertThat(status).isEqualTo(JsonObjectLoadCommand.EXIT_OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".obj WHERE type = 'Participant'",
                Long.class)).isPositive();
    }

    @Test
    void addsMissingColumnsAndPrimaryKeyToExistingTable() throws Exception {
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".obj (type text)");
        Path file = Files.writeString(dir.resolve("S-Thing--data.json"), "{\"id\":\"a\"}{\"id\":\"a\"}");

        JsonObjectLoader.LoadResult result = loader(10).load(file);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    private List<String> primaryKey() {
        return jdbc.queryForList("SELECT a.attname FROM pg_constraint c"
                + " JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace"
                + " CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)"
                + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum"
                + " WHERE c.contype = 'p' AND n.nspname = ? AND t.relname = 'obj' ORDER BY k.ord", String.class, schema);
    }

    private JsonObjectLoader loader(int batchSize) {
        return new JsonObjectLoader(dataSource, schema, "obj", batchSize);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Type, format and reading behavior; needs no database. */
class JsonObjectLoaderReadTest {

    private final JsonObjectLoader loader = new JsonObjectLoader(new DriverManagerDataSource());

    @TempDir
    Path dir;

    @Test
    void typeIsBetweenDashAndDoubleDash() {
        assertThat(loader.typeOf(Path.of("/x/SYNTH1-Participant--data.yaml"))).isEqualTo("Participant");
        assertThat(loader.typeOf(Path.of("A-B-MeasurementObservation--data.ndjson.gz")))
                .isEqualTo("MeasurementObservation");
    }

    @Test
    void typeIsRequired() {
        assertThatThrownBy(() -> loader.typeOf(Path.of("participants.yaml")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loader.typeOf(Path.of("Participant--data.yaml")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsMultiDocumentYaml() throws Exception {
        Path file = write("S-T--data.yaml", "id: a\nn: 1\n---\nid: b\nn: 2\n---\n");
        assertThat(ids(file)).containsExactly("a", "b");
    }

    @Test
    void readsNdjson() throws Exception {
        Path file = write("S-T--data.ndjson", "{\"id\":\"a\"}\n{\"id\":\"b\"}\n\n{\"id\":\"c\"}\n");
        assertThat(ids(file)).containsExactly("a", "b", "c");
    }

    @Test
    void unwrapsJsonArray() throws Exception {
        Path file = write("S-T--data.json", "[{\"id\":\"a\"},{\"id\":\"b\"}]");
        assertThat(ids(file)).containsExactly("a", "b");
    }

    @Test
    void readsSingleJsonObject() throws Exception {
        Path file = write("S-T--data.json", "{\"id\":\"a\",\"nested\":{\"id\":\"x\"}}");
        assertThat(ids(file)).containsExactly("a");
    }

    @Test
    void readsGzipAndSniffsFormatWithoutExtension() throws Exception {
        Path json = dir.resolve("S-T--data.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(json))) {
            out.write("{\"id\":\"a\"}\n{\"id\":\"b\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(ids(json)).containsExactly("a", "b");

        Path yaml = write("S-T--data", "id: a\n---\nid: b\n");
        assertThat(ids(yaml)).containsExactly("a", "b");
    }

    @Test
    void idMustBeStringOrNumber() throws Exception {
        assertThat(loader.idOf(node("{\"id\":\"a\"}"))).isEqualTo("a");
        assertThat(loader.idOf(node("{\"id\":7}"))).isEqualTo("7");
        assertThat(loader.idOf(node("{\"id\":\"\"}"))).isNull();
        assertThat(loader.idOf(node("{\"id\":null}"))).isNull();
        assertThat(loader.idOf(node("{\"id\":{\"a\":1}}"))).isNull();
        assertThat(loader.idOf(node("{\"other\":1}"))).isNull();
    }

    private JsonNode node(String json) throws Exception {
        Path file = write("S-T--n.json", json);
        List<JsonNode> nodes = new ArrayList<>();
        loader.read(file, nodes::add);
        return nodes.getFirst();
    }

    private List<String> ids(Path file) throws IOException, SQLException {
        List<String> ids = new ArrayList<>();
        loader.read(file, node -> {
            if (!node.isNull()) {
                ids.add(loader.idOf(node));
            }
        });
        return ids;
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}

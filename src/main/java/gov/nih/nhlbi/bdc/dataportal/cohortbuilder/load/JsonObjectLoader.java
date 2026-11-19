package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads the JSON objects in a file into a PostgreSQL table, one row per object, as
 * {@code (type text, id text, data jsonb, file_path text)} with primary key
 * {@code (type, id, file_path)}. {@code file_path} is the file's absolute path
 * ({@link #filePathOf}), so the same object loaded from two files gives two rows.
 *
 * <p>The table, its columns and its primary key are created if missing. A table whose
 * primary key is different (such as the earlier {@code (type, id)}) gets the new one;
 * that fails, changing nothing, if it has rows without a {@code file_path}. The file's
 * format is detected from its extension, or from its content when the extension is
 * unknown: multi-document YAML, or JSON (a single value, an array, or a sequence of
 * values such as NDJSON/JSONL). Either can be gzip-compressed. Top-level arrays are
 * unwrapped, so each element is one object.
 *
 * <p>Rows are inserted in batches, committing after each one. When a batch fails
 * because of its data (SQLSTATE class 22, 23 or 54, including a duplicate
 * {@code (type, id, file_path)}), it's rolled back and split in halves until each failing row
 * is isolated; those rows are logged and skipped and the rest are inserted. Objects
 * without a usable top-level {@code id} are logged and skipped without being sent.
 * Any other database error (connection, permissions, ...) ends the load: the batches
 * committed before it stay committed.
 *
 * <p>Subclasses can change how a file's type is derived ({@link #typeOf}), what's
 * stored as its path ({@link #filePathOf}), which files are picked from directories
 * ({@link #supports}), how its format is detected ({@link #formatOf}), how objects are
 * read ({@link #read}) and how an object's id is extracted ({@link #idOf}).
 */
public class JsonObjectLoader {

    public static final String DEFAULT_SCHEMA = "dp_data";
    public static final String DEFAULT_TABLE = "json_object";
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /** SQLSTATE classes that mean a row's data is bad, not that the database is unusable. */
    private static final Set<String> ROW_ERROR_CLASSES = Set.of("22", "23", "54");

    private static final Logger log = LoggerFactory.getLogger(JsonObjectLoader.class);

    public enum Format { YAML, JSON }

    /** The primary key columns, in order. */
    public static final List<String> PRIMARY_KEY = List.of("type", "id", "file_path");

    /** What one {@link #load} did. {@code read} counts every non-null object read from the file. */
    public record LoadResult(Path file, String type, long read, long inserted, long skipped) {}

    /** Receives the objects read from a file. */
    @FunctionalInterface
    protected interface ObjectSink {
        void accept(JsonNode node) throws SQLException;
    }

    private record Row(long position, String id, JsonNode node) {}

    private final DataSource dataSource;
    private final String schema;
    private final String table;
    private final int batchSize;
    // Trailing tokens are the next values of a stream, read one at a time.
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final YAMLMapper yamlMapper = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public JsonObjectLoader(DataSource dataSource) {
        this(dataSource, DEFAULT_SCHEMA, DEFAULT_TABLE, DEFAULT_BATCH_SIZE);
    }

    public JsonObjectLoader(DataSource dataSource, String schema, String table, int batchSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schema = requireName(schema, "schema");
        this.table = requireName(table, "table");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** Loads every object in {@code file}, with the type {@link #typeOf} gives for it. */
    public LoadResult load(Path file) throws IOException, SQLException {
        String type = typeOf(file);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("No type for " + file);
        }
        log.info("Loading {} as type {} into {}.{}", file, type, schema, table);
        Connection opened;
        try {
            opened = dataSource.getConnection();
        } catch (SQLException | RuntimeException e) {
            throw new DatabaseUnavailableException("Can't connect to the database: " + e.getMessage(), e);
        }
        try (Connection connection = opened) {
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                ensureTable(connection);
                LoadResult result = new Load(connection, file, type).run();
                log.info("Loaded {}: read {}, inserted {}, skipped {}",
                        file, result.read(), result.inserted(), result.skipped());
                return result;
            } finally {
                if (!connection.isClosed()) {
                    connection.setAutoCommit(autoCommit);
                }
            }
        }
    }

    /**
     * The type of the objects in {@code file}. This one returns the part of the file
     * name between the last {@code -} before the first {@code --}, and that {@code --}:
     * {@code SYNTH1-Participant--data.yaml} gives {@code Participant}.
     */
    protected String typeOf(Path file) {
        String name = file.getFileName().toString();
        int end = name.indexOf("--");
        int start = end > 0 ? name.lastIndexOf('-', end - 1) + 1 : 0;
        if (end < 0 || start == 0 || start == end) {
            throw new IllegalArgumentException(
                    "File name has no type between '-' and '--': " + name);
        }
        return name.substring(start, end);
    }

    /**
     * The value stored in {@code file_path} for objects from {@code file}: its absolute,
     * normalized path. Symbolic links aren't resolved.
     */
    protected String filePathOf(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    /**
     * Whether this loader handles {@code file}, judged by its name only: true when
     * {@link #formatOf} knows its extension. Used to pick files when scanning
     * directories; {@link #load} itself accepts any file.
     */
    public boolean supports(Path file) {
        return formatFromName(file) != null;
    }

    /**
     * The format of {@code file}, from its extension (ignoring a trailing {@code .gz}):
     * {@code .yaml}/{@code .yml} are YAML and {@code .json}/{@code .ndjson}/{@code .jsonl}
     * are JSON. Otherwise a file whose first non-blank character is {@code &#123;} or
     * {@code [} is JSON, and anything else is YAML.
     *
     * @param head the start of the (decompressed) content
     */
    protected Format formatOf(Path file, byte[] head) {
        Format byName = formatFromName(file);
        if (byName != null) {
            return byName;
        }
        for (byte b : head) {
            if (!Character.isWhitespace(b)) {
                return b == '{' || b == '[' ? Format.JSON : Format.YAML;
            }
        }
        return Format.YAML;
    }

    /**
     * Reads the objects in {@code file} and passes each to {@code sink}, in file order,
     * without holding the whole file in memory. Top-level arrays are unwrapped. Null
     * values (such as empty YAML documents) are passed on too; the caller ignores them.
     */
    protected void read(Path file, ObjectSink sink) throws IOException, SQLException {
        try (InputStream in = open(file)) {
            in.mark(64);
            byte[] head = in.readNBytes(64);
            in.reset();
            ObjectMapper mapper = formatOf(file, head) == Format.YAML ? yamlMapper : jsonMapper;
            try (JsonParser parser = mapper.createParser(in)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_ARRAY) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            sink.accept(mapper.readTree(parser));
                        }
                    } else {
                        sink.accept(mapper.readTree(parser));
                    }
                }
            }
        }
    }

    /**
     * The id of {@code node}: its top-level {@code id} as text, if that's a string or a
     * number. Null means the object has no usable id and is skipped.
     */
    protected String idOf(JsonNode node) {
        JsonNode id = node.get("id");
        if (id == null || !(id.isString() || id.isNumber())) {
            return null;
        }
        String text = id.asString();
        return text.isEmpty() ? null : text;
    }

    /** The format {@code file}'s extension names, or null if it names none. */
    private static Format formatFromName(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return Format.YAML;
        }
        if (name.endsWith(".json") || name.endsWith(".ndjson") || name.endsWith(".jsonl")) {
            return Format.JSON;
        }
        return null;
    }

    /** {@code file} as a buffered stream that supports mark, decompressed if gzipped. */
    private static InputStream open(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file));
        try {
            in.mark(2);
            int b1 = in.read();
            int b2 = in.read();
            in.reset();
            if (b1 == 0x1f && b2 == 0x8b) {
                in = new BufferedInputStream(new GZIPInputStream(in));
            }
            return in;
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** Columns and their types; all are NOT NULL in a table this creates. */
    private static final Map<String, String> COLUMNS = columns();

    private static Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("type", "text");
        columns.put("id", "text");
        columns.put("data", "jsonb");
        columns.put("file_path", "text");
        return Collections.unmodifiableMap(columns);
    }

    /**
     * Creates the schema, the table, missing columns or the primary key, each only when
     * the catalog shows it's missing. A table that's already right needs no privilege
     * beyond inserting, so a user that can't create schemas or tables can load into one
     * made for it.
     */
    private void ensureTable(Connection connection) throws SQLException {
        String qualified = quote(schema) + "." + quote(table);
        try (Statement st = connection.createStatement()) {
            if (!exists(connection, "SELECT 1 FROM pg_namespace WHERE nspname = ?", schema)) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema));
                log.info("Created schema {}", schema);
            }
            Set<String> existing = existingColumns(connection);
            if (existing.isEmpty()) {
                StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS " + qualified + " (");
                COLUMNS.forEach((name, type) -> ddl.append(name).append(' ').append(type).append(" NOT NULL, "));
                ddl.append("PRIMARY KEY (").append(String.join(", ", PRIMARY_KEY)).append("))");
                st.execute(ddl.toString());
                log.info("Created table {}.{}", schema, table);
            } else {
                for (Map.Entry<String, String> column : COLUMNS.entrySet()) {
                    if (!existing.contains(column.getKey())) {
                        st.execute("ALTER TABLE " + qualified + " ADD COLUMN IF NOT EXISTS "
                                + column.getKey() + " " + column.getValue());
                        log.info("Added column {} to {}.{}", column.getKey(), schema, table);
                    }
                }
            }
        }
        PrimaryKey current = primaryKey(connection);
        if (!PRIMARY_KEY.equals(current.columns())) {
            replacePrimaryKey(connection, qualified, current);
        }
        connection.commit();
    }

    /** The table's columns, or none if it doesn't exist. */
    private Set<String> existingColumns(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT a.attname FROM pg_attribute a JOIN pg_class t ON t.oid = a.attrelid"
                        + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " WHERE n.nspname = ? AND t.relname = ? AND t.relkind IN ('r', 'p')"
                        + " AND a.attnum > 0 AND NOT a.attisdropped")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            Set<String> columns = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            return columns;
        }
    }

    private static boolean exists(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record PrimaryKey(String name, List<String> columns) {}

    /** Gives the table the primary key {@link #PRIMARY_KEY}, replacing {@code current} if it has one. */
    private void replacePrimaryKey(Connection connection, String qualified, PrimaryKey current) throws SQLException {
        try (Statement st = connection.createStatement()) {
            if (current.name() != null) {
                st.execute("ALTER TABLE " + qualified + " DROP CONSTRAINT " + quote(current.name()));
            }
            st.execute("ALTER TABLE " + qualified + " ALTER COLUMN type SET NOT NULL,"
                    + " ALTER COLUMN id SET NOT NULL, ALTER COLUMN file_path SET NOT NULL");
            st.execute("ALTER TABLE " + qualified + " ADD PRIMARY KEY (type, id, file_path)");
        } catch (SQLException e) {
            connection.rollback();
            if ("23502".equals(e.getSQLState())) { // not_null_violation
                throw new SQLException("Table " + schema + "." + table + " has rows without type, id or file_path"
                        + " (file_path is new: rows loaded before it have none), so its primary key can't become ("
                        + String.join(", ", PRIMARY_KEY) + "). Set file_path on them, or delete them"
                        + " (e.g. TRUNCATE " + schema + "." + table + ") and load again. Nothing was changed.",
                        e.getSQLState(), e);
            }
            throw e;
        }
        log.info("Primary key of {}.{} changed from {} to {}", schema, table,
                current.columns().isEmpty() ? "none" : current.columns(), PRIMARY_KEY);
    }

    private PrimaryKey primaryKey(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.conname, a.attname FROM pg_constraint c"
                        + " JOIN pg_class t ON t.oid = c.conrelid"
                        + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)"
                        + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum"
                        + " WHERE c.contype = 'p' AND n.nspname = ? AND t.relname = ? ORDER BY k.ord")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            String name = null;
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    name = rs.getString(1);
                    columns.add(rs.getString(2));
                }
            }
            return new PrimaryKey(name, columns);
        }
    }

    /** The state of one {@link #load} call. */
    private final class Load {
        private final Connection connection;
        private final Path file;
        private final String type;
        private final String filePath;
        private final String insertSql;
        private final List<Row> batch = new ArrayList<>();
        private long position;
        private long read;
        private long inserted;
        private long skipped;

        Load(Connection connection, Path file, String type) {
            this.connection = connection;
            this.file = file;
            this.type = type;
            this.filePath = filePathOf(file);
            this.insertSql = "INSERT INTO " + quote(schema) + "." + quote(table)
                    + " (type, id, file_path, data) VALUES (?, ?, ?, ?::jsonb)";
        }

        LoadResult run() throws IOException, SQLException {
            read(file, this::accept);
            flush();
            return new LoadResult(file, type, read, inserted, skipped);
        }

        private void accept(JsonNode node) throws SQLException {
            position++;
            if (node == null || node.isNull() || node.isMissingNode()) {
                log.debug("Ignoring empty value #{} in {}", position, file);
                return;
            }
            read++;
            if (!node.isObject()) {
                skip(new Row(position, null, node), "not an object");
                return;
            }
            String id = idOf(node);
            if (id == null) {
                skip(new Row(position, null, node), "no string or number id");
                return;
            }
            batch.add(new Row(position, id, node));
            if (batch.size() >= batchSize) {
                flush();
            }
        }

        private void flush() throws SQLException {
            if (!batch.isEmpty()) {
                insert(List.copyOf(batch));
                batch.clear();
            }
        }

        /** Inserts {@code rows} and commits, or splits them until the failing rows are found. */
        private void insert(List<Row> rows) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                for (Row row : rows) {
                    ps.setString(1, type);
                    ps.setString(2, row.id());
                    ps.setString(3, filePath);
                    ps.setString(4, jsonMapper.writeValueAsString(row.node()));
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
                inserted += rows.size();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    // e.g. the connection is gone; report the error that caused it
                    e.addSuppressed(rollbackFailure);
                    throw e;
                }
                if (!isRowError(e)) {
                    throw e;
                }
                if (rows.size() == 1) {
                    skip(rows.getFirst(), describe(e));
                    return;
                }
                log.debug("Batch of {} rows from {} failed, splitting it: {}", rows.size(), file, describe(e));
                int half = rows.size() / 2;
                insert(rows.subList(0, half));
                insert(rows.subList(half, rows.size()));
            }
        }

        private void skip(Row row, String reason) {
            skipped++;
            log.warn("Skipped object #{} in {} (type {}, id {}): {}: {}",
                    row.position(), file, type, row.id(), reason, jsonMapper.writeValueAsString(row.node()));
        }
    }

    private static boolean isRowError(SQLException e) {
        String state = sqlState(e);
        return state != null && state.length() >= 2 && ROW_ERROR_CLASSES.contains(state.substring(0, 2));
    }

    /** The first SQLSTATE in {@code e}'s chain; batch errors can carry it on a nested exception. */
    private static String sqlState(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                for (SQLException n = s; n != null; n = n.getNextException()) {
                    if (n.getSQLState() != null) {
                        return n.getSQLState();
                    }
                }
            }
        }
        return null;
    }

    /** The most specific message in {@code e}'s chain (the batch wrapper's is generic). */
    private static String describe(SQLException e) {
        SQLException last = e;
        for (SQLException n = e.getNextException(); n != null; n = n.getNextException()) {
            last = n;
        }
        return "[" + sqlState(e) + "] " + last.getMessage();
    }

    private static String requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return name;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}

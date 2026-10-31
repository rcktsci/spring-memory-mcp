package se.rocketscien.mcp.springmemorymcpserver.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;
import se.rocketscien.mcp.springmemorymcpserver.connections.AgentConnectionsManager;
import se.rocketscien.mcp.springmemorymcpserver.connections.AuthService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class DataWriteController {

    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");

    private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");

    /** PostgreSQL identifiers are truncated to 63 bytes, so a longer name would silently collide. */
    private static final int MAX_IDENTIFIER_BYTES = 63;

    private final AgentConnectionsManager connectionsManager;

    private final AuthService authService;

    private final ObjectMapper objectMapper;

    @PostMapping("/api/data")
    public ResponseEntity<Map<String, Object>> write(HttpServletRequest request) {
        AgentConfig agent = authService.current();
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        try {
            var json = isJson(request.getContentType());
            var target = requireName(request.getHeader("X-Target-Table"), "X-Target-Table");
            var function = optionalName(request.getHeader("X-Function-Name"), "X-Function-Name");

            var parts = target.split("\\.", 2);
            var schema = parts.length == 2 ? parts[0] : "public";
            var table = parts[parts.length - 1];

            var body = new PushbackInputStream(request.getInputStream(), 3);
            return json
                    ? writeJson(agent, body, schema, table, function)
                    : writeCsv(agent, body, schema, table, function);
        } catch (BadRequest e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // A database that cannot be reached is not the caller's mistake; a rejected statement or
            // unparseable input is. Anything else is a server-side surprise.
            var status = SqlErrors.isConnectionFailure(e) ? HttpStatus.SERVICE_UNAVAILABLE
                    : e instanceof SQLException || e instanceof JacksonException ? HttpStatus.BAD_REQUEST
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            var message = e.getMessage() != null ? e.getMessage() : e.toString();
            return ResponseEntity.status(status).body(Map.of("error", message));
        }
    }

    private ResponseEntity<Map<String, Object>> writeCsv(AgentConfig agent, PushbackInputStream body,
                                                         String schema, String table, String function) throws Exception {
        skipUtf8Bom(body);
        var header = readCsvHeader(body);

        var next = body.read();
        if (next == -1) {
            throw new BadRequest("The CSV body has no data rows");
        }
        body.unread(next);

        var counter = new CsvRecordCounter(body);
        try (var data = new SequenceInputStream(new ByteArrayInputStream(header.raw()), counter)) {
            return load(agent, schema, table, function, header.columns(), (copyManager, sql) -> {
                var written = copyStream(copyManager, sql, data);
                return new CopyResult(counter.records(), written);
            });
        }
    }

    private ResponseEntity<Map<String, Object>> writeJson(AgentConfig agent, InputStream body,
                                                          String schema, String table, String function) throws Exception {
        try (var parser = objectMapper.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new BadRequest("The JSON body must be an array of objects");
            }
            if (parser.nextToken() == JsonToken.END_ARRAY) {
                throw new BadRequest("The JSON array is empty: no columns");
            }
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new BadRequest("Every element of the JSON array must be an object");
            }

            var first = readJsonObject(parser, null);
            if (first.isEmpty()) {
                throw new BadRequest("The first JSON object has no keys: no columns");
            }
            var columns = List.copyOf(first.keySet());

            return load(agent, schema, table, function, columns,
                    (copyManager, sql) -> copyJson(copyManager, sql, columns, first, parser));
        }
    }

    /** The rows found in the input and the rows PostgreSQL's COPY actually inserted. */
    private record CopyResult(long received, long written) {
    }

    @FunctionalInterface
    private interface CopyWriter {
        CopyResult copy(CopyManager copyManager, String sql) throws Exception;
    }

    private ResponseEntity<Map<String, Object>> load(AgentConfig agent, String schema, String table,
                                                     String function, List<String> columns,
                                                     CopyWriter copyWriter) throws Exception {
        validateColumns(columns);

        var columnList = String.join(", ", columns.stream().map(DataWriteController::quote).toList());
        var copySql = "COPY " + quote(schema) + "." + quote(table) + " (" + columnList
                + ") FROM STDIN WITH (FORMAT csv, HEADER true, ON_ERROR ignore)";

        var functionName = function == null ? null : lastPart(function);
        // An unqualified routine resolves to public, like the table: the session search_path is never used.
        var functionSchema = function == null || function.indexOf('.') < 0 ? "public" : schemaOf(function);

        try (var connection = connectionsManager.getDataSource(agent).getConnection()) {
            connection.setAutoCommit(false);
            try {
                // The memory tool can SET these on a pooled connection, so pin them for the COPY:
                // client_encoding and datestyle decide how the same bytes are decoded and parsed.
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL client_encoding = 'UTF8'");
                    statement.execute("SET LOCAL datestyle = 'ISO, YMD'");
                    statement.execute("SET LOCAL intervalstyle = 'postgres'");
                }

                var created = ensureTable(connection, schema, table, columns);

                var copyManager = new CopyManager(connection.unwrap(BaseConnection.class));
                var copy = copyWriter.copy(copyManager, copySql);
                var written = copy.written();
                var ignored = copy.received() - written;

                var result = function == null ? null : callFunction(connection, functionSchema, functionName);

                if (created && function != null) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("DROP TABLE IF EXISTS " + quote(schema) + "." + quote(table));
                    }
                }

                connection.commit();

                var response = new LinkedHashMap<String, Object>();
                response.put("rows_received", copy.received());
                response.put("rows_ignored", ignored);
                response.put("rows_written", written);
                response.put("result", result);
                return ResponseEntity.ok(response);
            } finally {
                connection.rollback();
            }
        }
    }

    private static long copyStream(CopyManager copyManager, String sql, InputStream data) throws Exception {
        var copy = copyManager.copyIn(sql);
        try {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = data.read(buffer)) != -1) {
                copy.writeToCopy(buffer, 0, read);
            }
            return copy.endCopy();
        } catch (Exception e) {
            cancel(copy);
            throw e;
        }
    }

    private CopyResult copyJson(CopyManager copyManager, String sql, List<String> columns,
                                Map<String, JsonNode> first, JsonParser parser) throws Exception {
        var copy = copyManager.copyIn(sql);
        try {
            write(copy, csvHeader(columns));
            write(copy, csvRow(columns, first));
            var received = 1L;

            var known = new HashSet<>(columns);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.START_OBJECT) {
                    throw new BadRequest("Every element of the JSON array must be an object");
                }
                write(copy, csvRow(columns, readJsonObject(parser, known)));
                received++;
            }
            return new CopyResult(received, copy.endCopy());
        } catch (Exception e) {
            cancel(copy);
            throw e;
        }
    }

    private static void write(CopyIn copy, byte[] data) throws SQLException {
        copy.writeToCopy(data, 0, data.length);
    }

    /** Cancels a COPY that is still running so the connection is released; the transaction rolls back anyway. */
    private static void cancel(CopyIn copy) {
        try {
            copy.cancelCopy();
        } catch (SQLException ignored) {
            // already finished or the connection is gone
        }
    }

    /** Reads one JSON object: keys checked against {@code known} (null for the first object), duplicate keys rejected. */
    private static Map<String, JsonNode> readJsonObject(JsonParser parser, Set<String> known) throws JacksonException {
        var row = new LinkedHashMap<String, JsonNode>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var name = parser.currentName();
            if (known != null && !known.contains(name)) {
                throw new BadRequest("Unknown column in the JSON object: " + name);
            }
            parser.nextToken();
            JsonNode value = parser.readValueAsTree();
            if (row.put(name, value) != null) {
                throw new BadRequest("Duplicate column in the JSON object: " + name);
            }
        }
        return row;
    }

    private byte[] csvRow(List<String> columns, Map<String, JsonNode> row) throws JacksonException {
        var line = new StringBuilder();
        for (var i = 0; i < columns.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            var value = row.get(columns.get(i));
            if (value == null || value.isNull()) {
                continue; // unquoted empty field is SQL NULL
            }
            if (value.isString()) {
                line.append(quoteCsv(value.asString()));
            } else if (value.isObject() || value.isArray()) {
                line.append(quoteCsv(objectMapper.writeValueAsString(value)));
            } else {
                line.append(value.asString());
            }
        }
        line.append('\n');
        return line.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] csvHeader(List<String> columns) {
        var line = String.join(",", columns.stream().map(DataWriteController::quoteCsv).toList());
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private record CsvHeader(byte[] raw, List<String> columns) {
    }

    /**
     * Excel writes a UTF-8 BOM before the first column name. It is dropped before the header is parsed,
     * so the replayed header bytes and the parsed columns stay consistent (PostgreSQL skips the header
     * record anyway via HEADER true).
     */
    private static void skipUtf8Bom(PushbackInputStream in) throws IOException {
        var bom = new byte[3];
        var read = in.readNBytes(bom, 0, bom.length);
        if (!(read == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF)) {
            in.unread(bom, 0, read);
        }
    }

    /**
     * Reads the first CSV record with PostgreSQL's rule: a quote starts a quoted section anywhere in
     * the field, {@code ""} inside it is a literal quote, a single quote closes it and the following
     * characters are ordinary data again. The raw bytes are replayed into COPY, which skips this
     * record via HEADER true.
     */
    private static CsvHeader readCsvHeader(PushbackInputStream in) throws IOException {
        var raw = new ByteArrayOutputStream();
        var columns = new ArrayList<String>();
        var field = new ByteArrayOutputStream();
        var inQuotes = false;
        var quotePending = false;

        int b;
        while ((b = in.read()) != -1) {
            raw.write(b);
            if (inQuotes) {
                if (quotePending) {
                    if (b == '"') {
                        field.write('"');
                        quotePending = false;
                        continue;
                    }
                    inQuotes = false;
                    quotePending = false;
                } else if (b == '"') {
                    quotePending = true;
                    continue;
                } else {
                    field.write(b);
                    continue;
                }
            }

            if (b == '"') {
                inQuotes = true;
            } else if (b == ',') {
                columns.add(field.toString(StandardCharsets.UTF_8));
                field.reset();
            } else if (b == '\r' || b == '\n') {
                if (b == '\r') {
                    var next = in.read();
                    if (next == '\n') {
                        raw.write(next);
                    } else if (next != -1) {
                        in.unread(next);
                    }
                }
                break;
            } else {
                field.write(b);
            }
        }

        if (raw.size() == 0) {
            throw new BadRequest("The CSV body is empty: no columns");
        }
        columns.add(field.toString(StandardCharsets.UTF_8));

        if (new HashSet<>(columns).size() != columns.size()) {
            throw new BadRequest("Duplicate column in the CSV header: " + columns);
        }
        return new CsvHeader(raw.toByteArray(), columns);
    }

    /**
     * Counts the records COPY reads, with the same dialect rule as {@link #readCsvHeader}: a record
     * ends at an unquoted line terminator, so quoted commas, quotes and newlines do not end it.
     */
    private static final class CsvRecordCounter extends FilterInputStream {

        private long records;
        private boolean inQuotes;
        private boolean quotePending;
        private boolean inRecord;
        private boolean pendingCr;

        CsvRecordCounter(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            var b = super.read();
            if (b != -1) {
                count(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var read = super.read(buffer, offset, length);
            for (var i = 0; i < read; i++) {
                count(buffer[offset + i]);
            }
            return read;
        }

        /** Records seen so far, including a final record that has no line terminator. */
        long records() {
            return records + (inRecord ? 1 : 0);
        }

        private void count(int b) {
            if (pendingCr) {
                pendingCr = false;
                if (b == '\n') {
                    return; // second half of a CRLF pair, already counted with the CR
                }
            }

            inRecord = true;
            if (inQuotes) {
                if (quotePending) {
                    if (b == '"') {
                        quotePending = false;
                        return;
                    }
                    inQuotes = false;
                    quotePending = false;
                } else if (b == '"') {
                    quotePending = true;
                    return;
                } else {
                    return;
                }
            }

            if (b == '"') {
                inQuotes = true;
            } else if (b == '\r') {
                records++;
                inRecord = false;
                pendingCr = true;
            } else if (b == '\n') {
                records++;
                inRecord = false;
            }
        }
    }

    /** An existing table keeps its own types and defaults; only the input columns are written. */
    private static boolean ensureTable(Connection connection, String schema, String table,
                                       List<String> columns) throws SQLException {
        if (tableExists(connection, schema, table)) {
            var existing = tableColumns(connection, schema, table);
            for (var column : columns) {
                if (!existing.contains(column)) {
                    throw new BadRequest("Table " + schema + "." + table + " has no column: " + column);
                }
            }
            return false;
        }

        var definition = String.join(", ", columns.stream().map(column -> quote(column) + " text").toList());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema));
            statement.execute("CREATE TABLE " + quote(schema) + "." + quote(table) + " (" + definition + ")");
        }
        return true;
    }

    private static void validateColumns(List<String> columns) {
        for (var column : columns) {
            if (column.isEmpty()) {
                throw new BadRequest("Column name must not be empty");
            }
            if (column.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
                throw new BadRequest("Column name is longer than " + MAX_IDENTIFIER_BYTES + " bytes: " + column);
            }
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static Set<String> tableColumns(Connection connection, String schema, String table) throws SQLException {
        var columns = new HashSet<String>();
        try (var statement = connection.prepareStatement(
                "SELECT a.attname FROM pg_attribute a"
                        + " JOIN pg_class c ON c.oid = a.attrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    /** Calls the routine with no arguments; a function's rows are returned, a procedure's result is null. */
    private static List<Map<String, Object>> callFunction(Connection connection, String schema,
                                                          String name) throws SQLException {
        var kind = functionKind(connection, schema, name);
        var qualified = quote(schema) + "." + quote(name) + "()";

        try (var statement = connection.createStatement()) {
            if ("p".equals(kind)) {
                statement.execute("CALL " + qualified);
                return null;
            }
            try (var resultSet = statement.executeQuery("SELECT * FROM " + qualified)) {
                return readRows(resultSet);
            }
        }
    }

    private static String functionKind(Connection connection, String schema, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT p.prokind FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE n.nspname = ? AND p.proname = ? AND p.pronargs = 0"
                        + " AND p.prokind IN ('f', 'p')")) {
            statement.setString(1, schema);
            statement.setString(2, name);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new BadRequest(hasNonCallableRoutine(connection, schema, name)
                            ? "Not a callable function: " + schema + "." + name
                            : "Function not found: " + schema + "." + name);
                }
                var kind = resultSet.getString(1);
                if (resultSet.next()) {
                    throw new BadRequest("Function name is ambiguous: " + schema + "." + name);
                }
                return kind;
            }
        }
    }

    /** True when the name is a zero-argument aggregate or window function instead of a function or procedure. */
    private static boolean hasNonCallableRoutine(Connection connection, String schema,
                                                 String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE n.nspname = ? AND p.proname = ? AND p.pronargs = 0"
                        + " AND p.prokind NOT IN ('f', 'p')")) {
            statement.setString(1, schema);
            statement.setString(2, name);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        var columns = meta.getColumnCount();
        var rows = new ArrayList<Map<String, Object>>();
        while (resultSet.next()) {
            var row = new LinkedHashMap<String, Object>();
            for (var i = 1; i <= columns; i++) {
                row.put(meta.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static boolean isJson(String contentType) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            throw new BadRequest("Unsupported Content-Type: " + contentType);
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
            return true;
        }
        if (TEXT_CSV.isCompatibleWith(mediaType)) {
            return false;
        }
        throw new BadRequest("Unsupported Content-Type: " + contentType);
    }

    private static String requireName(String name, String header) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new BadRequest(header + " must match " + NAME.pattern());
        }
        return name;
    }

    private static String optionalName(String name, String header) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return requireName(name, header);
    }

    private static String lastPart(String name) {
        return name.substring(name.indexOf('.') + 1);
    }

    private static String schemaOf(String name) {
        return name.substring(0, name.indexOf('.'));
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String quoteCsv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static class BadRequest extends RuntimeException {

        BadRequest(String message) {
            super(message);
        }
    }
}

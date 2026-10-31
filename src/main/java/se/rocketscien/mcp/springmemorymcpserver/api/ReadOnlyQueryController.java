package se.rocketscien.mcp.springmemorymcpserver.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;
import se.rocketscien.mcp.springmemorymcpserver.connections.AuthService;
import se.rocketscien.mcp.springmemorymcpserver.connections.AgentConnectionsManager;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ReadOnlyQueryController {

    private final AgentConnectionsManager connectionsManager;

    private final AuthService authService;

    public record QueryRequest(String query) {
    }

    @PostMapping("/api/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody QueryRequest request) {
        AgentConfig agent = authService.current();
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        try (var connection = connectionsManager.getDataSource(agent).getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);

            try (var statement = connection.createStatement()) {
                // Server-side guarantee: PostgreSQL rejects any write in a read-only transaction,
                // including additional statements appended to the query text.
                statement.execute("SET TRANSACTION READ ONLY");

                try (var resultSet = statement.executeQuery(request.query())) {
                    var meta = resultSet.getMetaData();
                    int columns = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(meta.getColumnLabel(i), resultSet.getObject(i));
                        }
                        rows.add(row);
                    }
                    var response = new LinkedHashMap<String, Object>();
                    response.put("rows", rows);
                    response.put("rowCount", rows.size());
                    return ResponseEntity.ok(response);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception e) {
            // The error is reported with a non-2xx status: a rejected query (read-only, bad SQL)
            // must not look like a successful response to the caller. A database that cannot be
            // reached is not the caller's mistake, so it gets 503 and can be retried.
            var status = SqlErrors.isConnectionFailure(e) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
            var message = e.getMessage() != null ? e.getMessage() : e.toString();
            return ResponseEntity.status(status).body(Map.of("error", message));
        }
    }
}

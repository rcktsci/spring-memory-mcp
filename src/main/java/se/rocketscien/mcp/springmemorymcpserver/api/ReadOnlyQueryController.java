package se.rocketscien.mcp.springmemorymcpserver.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import se.rocketscien.mcp.springmemorymcpserver.auth.TokenAuthFilter;
import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;
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

    private final TokenAuthFilter tokenAuthFilter;

    public record QueryRequest(String query) {
    }

    @PostMapping("/api/query")
    public Map<String, Object> query(@RequestBody QueryRequest request) {
        AgentConfig agent = tokenAuthFilter.currentAgent();
        if (agent == null) {
            return Map.of("error", "Unauthorized");
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
                    return response;
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}

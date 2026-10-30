package se.rocketscien.mcp.springmemorymcpserver.tests;

import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import se.rocketscien.mcp.springmemorymcpserver.BaseApplicationTest;

public class BaseCallToolTest extends BaseApplicationTest {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    @Autowired
    protected ObjectMapper objectMapper;

    protected String memory(String token, String sql) {
        return callTool(token, "memory", Map.of("sql", sql));
    }

    @SneakyThrows
    protected String callTool(String token, String toolName, Object arguments) {
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "%s",
                    "arguments": %s
                  }
                }
                """.formatted(toolName, objectMapper.writeValueAsString(arguments));

        var response = post(token, body, sessionId(token));
        return extractDataPayload(response.body());
    }

    @SneakyThrows
    protected String sessionId(String token) {
        return sessions.computeIfAbsent(token, this::openSession);
    }

    @SneakyThrows
    private String openSession(String token) {
        var response = post(token, """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "e2e-test", "version": "0.0.1"}
                  }
                }
                """, null);

        if (response.statusCode() != 200) {
            throw new IllegalStateException("initialize failed: " + response.statusCode() + " " + response.body());
        }

        var sessionId = response.headers().firstValue("mcp-session-id")
                .orElseThrow(() -> new IllegalStateException("no mcp-session-id in initialize response"));

        post(token, "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}", sessionId);
        return sessionId;
    }

    protected String extractDataPayload(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .map(line -> line.substring(5).trim())
                .orElse(body);
    }

    @SneakyThrows
    protected String getResponseText(String rawResponse) {
        return objectMapper.readTree(rawResponse)
                .get("result").get("content").get(0).get("text").asText();
    }

    @SneakyThrows
    protected JsonNode getResponseJson(String rawResponse) {
        return objectMapper.readTree(getResponseText(rawResponse));
    }

    @SneakyThrows
    protected void executeJdbc(String database, String sql) {
        var url = DATABASE.getJdbcUrl().replace(DATABASE.getDatabaseName(), database);
        try (var connection = DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @SneakyThrows
    protected int rowCount(String database, String table) {
        var url = DATABASE.getJdbcUrl().replace(DATABASE.getDatabaseName(), database);
        try (var connection = DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword());
             var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}

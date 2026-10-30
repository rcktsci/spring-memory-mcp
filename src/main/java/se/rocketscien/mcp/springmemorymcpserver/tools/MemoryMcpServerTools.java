package se.rocketscien.mcp.springmemorymcpserver.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import se.rocketscien.mcp.springmemorymcpserver.auth.TokenAuthFilter;
import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;
import se.rocketscien.mcp.springmemorymcpserver.connections.AgentConnectionsManager;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryMcpServerTools {

    private final AgentConnectionsManager connectionsManager;

    private final TokenAuthFilter tokenAuthFilter;

    public MemoryMcpServerTools(AgentConnectionsManager connectionsManager, TokenAuthFilter tokenAuthFilter) {
        this.connectionsManager = connectionsManager;
        this.tokenAuthFilter = tokenAuthFilter;
    }

    @Tool(
            name = "memory",
            description = "Persistent memory backed by a dedicated PostgreSQL 18 database. " +
                    "This is full, unrestricted access to a real relational database: the agent may create and alter " +
                    "any tables, views, functions, procedures, indexes, triggers, enable any extensions (CREATE EXTENSION), " +
                    "and store any data in any form (including JSONB, arrays, full-text and vector types) to keep it " +
                    "available between sessions. Execute any SQL statement - DDL, DML or SELECT; multiple statements " +
                    "separated by ';' are allowed and all their results are returned. " +
                    "Data persists in the database across sessions and conversations."
    )
    public Map<String, Object> memory(
            @ToolParam(description = "SQL statement(s) to execute against the PostgreSQL memory database") String sql
    ) {
        var agent = tokenAuthFilter.currentAgent();
        if (agent == null) {
            return Map.of("error", "Unauthorized: missing or invalid access token");
        }

        try (var connection = connectionsManager.getDataSource(agent).getConnection();
             var statement = connection.createStatement()) {

            List<Object> results = new ArrayList<>();
            boolean hasResultSet = statement.execute(sql);
            while (true) {
                if (hasResultSet) {
                    results.add(Map.of("rows", readRows(statement.getResultSet())));
                } else {
                    results.add(Map.of("updateCount", statement.getUpdateCount()));
                }
                hasResultSet = statement.getMoreResults();
                if (!hasResultSet && statement.getUpdateCount() == -1) {
                    break;
                }
            }
            return results.size() == 1 ? (Map<String, Object>) results.get(0) : Map.of("results", results);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private List<Map<String, Object>> readRows(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columns = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                row.put(meta.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}

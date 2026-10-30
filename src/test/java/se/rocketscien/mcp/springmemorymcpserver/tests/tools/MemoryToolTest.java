package se.rocketscien.mcp.springmemorymcpserver.tests.tools;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import se.rocketscien.mcp.springmemorymcpserver.tests.BaseCallToolTest;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryToolTest extends BaseCallToolTest {

    private static final String ALPHA_DB = "agent_alpha";
    private static final String BETA_DB = "agent_beta";

    @Test
    void selectReturnsRows() {
        var response = memory(ALPHA_TOKEN, "SELECT 42 AS answer");

        assertThat(getResponseJson(response).get("rows").get(0).get("answer").asInt()).isEqualTo(42);
    }

    @Test
    void ddlAndDmlWork() {
        memory(ALPHA_TOKEN, "CREATE TABLE IF NOT EXISTS notes (id serial PRIMARY KEY, note text)");
        memory(ALPHA_TOKEN, "INSERT INTO notes (note) VALUES ('hello')");

        assertThat(rowCount(ALPHA_DB, "notes")).isEqualTo(1);
    }

    @Test
    void multiStatementReturnsAllResults() {
        var response = memory(ALPHA_TOKEN,
                "CREATE TABLE IF NOT EXISTS multi (v int); INSERT INTO multi VALUES (1), (2); SELECT COUNT(*) AS n FROM multi");

        var json = getResponseJson(response);
        assertThat(json.has("results")).isTrue();
        assertThat(json.get("results").size()).isEqualTo(3);
        assertThat(json.get("results").get(2).get("rows").get(0).get("n").asInt()).isEqualTo(2);
    }

    @Test
    void agentsAreIsolatedInTheirOwnDatabases() {
        memory(ALPHA_TOKEN, "CREATE TABLE IF NOT EXISTS alpha_only (v int)");
        memory(BETA_TOKEN, "CREATE TABLE IF NOT EXISTS beta_only (v int)");

        // Alpha sees only its own tables, beta sees only its own
        var alphaTables = memory(ALPHA_TOKEN,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name");
        var betaTables = memory(BETA_TOKEN,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name");

        assertThat(alphaTables).doesNotContain("beta_only");
        assertThat(betaTables).doesNotContain("alpha_only");
    }

    @Test
    void extensionCanBeEnabled() {
        var response = memory(ALPHA_TOKEN, "CREATE EXTENSION IF NOT EXISTS pg_trgm");

        assertThat(getResponseJson(response).has("error")).isFalse();
    }

    @Test
    void getConnectionInfoReturnsEndpointAndSha256OfToken() {
        var response = callTool(ALPHA_TOKEN, "get_connection_info", java.util.Map.of());

        var json = getResponseJson(response);
        assertThat(json.get("endpoint").asText()).isEqualTo("http://localhost:8080/api/query");
        assertThat(json.get("authentication").asText()).isEqualTo(sha256Hex(ALPHA_TOKEN));
        assertThat(response).doesNotContain(ALPHA_TOKEN);
    }

    @Test
    void invalidSqlReturnsError() {
        var response = memory(ALPHA_TOKEN, "SELECT * FROM no_such_table");

        assertThat(getResponseJson(response).get("error").asText()).isNotBlank();
    }
}

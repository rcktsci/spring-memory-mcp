package se.rocketscien.mcp.springmemorymcpserver.tests.rest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.rocketscien.mcp.springmemorymcpserver.tests.BaseCallToolTest;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyQueryApiTest extends BaseCallToolTest {

    private static final String ALPHA_DB = "agent_alpha";

    @BeforeEach
    void setupTable() {
        memory(ALPHA_TOKEN, "CREATE TABLE IF NOT EXISTS api_notes (id serial PRIMARY KEY, note text)");
        memory(ALPHA_TOKEN, "DELETE FROM api_notes");
        memory(ALPHA_TOKEN, "INSERT INTO api_notes (note) VALUES ('keep-me')");
    }

    @Test
    void selectWithSha256TokenReturnsRows() {
        var response = postApi(sha256Hex(ALPHA_TOKEN), "SELECT note FROM api_notes");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("keep-me");
        assertThat(response.body()).contains("rowCount");
    }

    @Test
    void rawUuidTokenIsRejected() {
        assertThat(postApi(ALPHA_TOKEN, "SELECT 1").statusCode()).isEqualTo(401);
    }

    @Test
    void unknownSha256TokenIsRejected() {
        assertThat(postApi(sha256Hex("no-such-token"), "SELECT 1").statusCode()).isEqualTo(401);
    }

    @Test
    void missingTokenIsRejected() {
        assertThat(postApi(null, "SELECT 1").statusCode()).isEqualTo(401);
    }

    @Test
    void insertIsRejectedByDatabase() {
        var response = postApi(sha256Hex(ALPHA_TOKEN), "INSERT INTO api_notes (note) VALUES ('nope')");

        assertThat(response.body()).contains("read-only");
        assertThat(rowCount(ALPHA_DB, "api_notes")).isEqualTo(1);
    }

    @Test
    void deleteAppendedToSelectIsRejected() {
        var response = postApi(sha256Hex(ALPHA_TOKEN), "SELECT 1; DELETE FROM api_notes");

        assertThat(response.body()).contains("read-only");
        assertThat(rowCount(ALPHA_DB, "api_notes")).isEqualTo(1);
    }

    @Test
    void dataModifyingCteIsRejected() {
        var response = postApi(sha256Hex(ALPHA_TOKEN),
                "WITH d AS (DELETE FROM api_notes RETURNING *) SELECT * FROM d");

        assertThat(response.body()).contains("read-only");
        assertThat(rowCount(ALPHA_DB, "api_notes")).isEqualTo(1);
    }

    @Test
    void dropTableIsRejected() {
        var response = postApi(sha256Hex(ALPHA_TOKEN), "DROP TABLE api_notes");

        assertThat(response.body()).contains("read-only");
    }

    @SneakyThrows
    protected HttpResponse<String> postApi(String token, String query) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(java.util.Map.of("query", query))));

        if (token != null) {
            builder.header("X-Access-Token", token);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

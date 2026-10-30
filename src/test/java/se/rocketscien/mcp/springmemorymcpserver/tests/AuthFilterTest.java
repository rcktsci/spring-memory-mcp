package se.rocketscien.mcp.springmemorymcpserver.tests;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import se.rocketscien.mcp.springmemorymcpserver.BaseApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest extends BaseApplicationTest {

    private static final String INITIALIZE = """
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
            """;

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        var response = post(null, INITIALIZE, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Missing token");
    }

    @Test
    void requestWithUnknownTokenIsUnauthorized() throws Exception {
        assertThat(post("no-such-token", INITIALIZE, null).statusCode()).isEqualTo(401);
    }

    @Test
    void initializeWithBearerTokenOpensSession() throws Exception {
        var response = post(ALPHA_TOKEN, INITIALIZE, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("mcp-session-id")).isPresent();
        assertThat(response.body()).contains("serverInfo");
    }

    @Test
    void initializeWithXAccessTokenHeaderOpensSession() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl()))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("X-Access-Token", ALPHA_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("serverInfo");
    }

    @Test
    void instructionsMentionReadOnlyEndpoint() throws Exception {
        var response = post(ALPHA_TOKEN, INITIALIZE, null);

        assertThat(response.body()).contains("get_connection_info");
        assertThat(response.body()).doesNotContain("${memory.public-url}");
    }
}

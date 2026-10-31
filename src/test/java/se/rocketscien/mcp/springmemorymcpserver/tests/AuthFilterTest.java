package se.rocketscien.mcp.springmemorymcpserver.tests;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

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

    /** The derived read and write tokens must never open the write-capable MCP endpoint. */
    @Test
    void derivedTokensCannotOpenTheMcpEndpoint() throws Exception {
        assertThat(post(roToken(ALPHA_TOKEN), INITIALIZE, null).statusCode()).isEqualTo(401);
        assertThat(post(rwToken(ALPHA_TOKEN), INITIALIZE, null).statusCode()).isEqualTo(401);
    }

    /**
     * The public read-only hash must not open the MCP endpoint. The filter decides by the routed path,
     * not by the raw request target, so a path like "/api/../mcp" cannot smuggle the hash into the
     * write-capable endpoint.
     */
    @Test
    void hashTokenCannotReachTheMcpEndpointThroughAnApiPath() throws Exception {
        var response = rawPost("/api/../mcp", roToken(ALPHA_TOKEN));

        assertThat(statusCode(response)).isIn(401, 404);
    }

    /** Percent-encoded dot segments must not help either. */
    @Test
    void hashTokenCannotReachTheMcpEndpointThroughAnEncodedApiPath() throws Exception {
        var response = rawPost("/api/%2e%2e/mcp", roToken(ALPHA_TOKEN));

        assertThat(statusCode(response)).isIn(401, 404);
    }

    /** The filter guards the MCP endpoint and the API, nothing else: other paths must not ask for a token. */
    @Test
    void pathsOutsideMcpAndApiAreNotGuarded() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + localServerPort + "/no-such-endpoint"))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isNotEqualTo(401);
    }

    /** A CORS preflight carries no token by design: it must not be answered with 401. */
    @Test
    void corsPreflightDoesNotRequireAToken() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl()))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isNotEqualTo(401);
    }

    private static int statusCode(String rawResponse) {
        return Integer.parseInt(rawResponse.split(" ", 3)[1]);
    }

    /**
     * Sends a request with the path exactly as written: {@link java.net.http.HttpClient} normalizes
     * dot segments itself, so a raw socket is the only way to reproduce the attack.
     */
    private String rawPost(String rawPath, String token) throws Exception {
        var body = INITIALIZE.getBytes(StandardCharsets.UTF_8);
        var request = "POST " + rawPath + " HTTP/1.1\r\n"
                + "Host: localhost:" + localServerPort + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: text/event-stream, application/json\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        try (var socket = new Socket("localhost", localServerPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();

            try (var input = socket.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}

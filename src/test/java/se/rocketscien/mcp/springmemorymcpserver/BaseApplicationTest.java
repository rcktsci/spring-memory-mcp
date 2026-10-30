package se.rocketscien.mcp.springmemorymcpserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;

import lombok.SneakyThrows;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class BaseApplicationTest {

    protected static final String ALPHA_TOKEN = "550e8400-e29b-41d4-a716-446655440000";
    protected static final String BETA_TOKEN = "550e8400-e29b-41d4-a716-446655440001";

    protected static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("agent_alpha")
            .withUsername("mcp")
            .withPassword("mcp");

    /**
     * Runs at class-load time, before the Spring context reads {@code config.yml} via
     * {@code spring.config.import}: the container port is only known at runtime, so the config file
     * must be planted before the context starts. The file is deleted by a shutdown hook; the container
     * is left for Ryuk to reap when the JVM exits.
     */
    static {
        if (Files.exists(Path.of("config.yml"))) {
            throw new IllegalStateException(
                    "config.yml already exists in the test working directory; tests plant their own config.yml and refuse to overwrite it");
        }

        DATABASE.start();

        createBetaDatabase();

        var config = """
                memory:
                  public-url: "http://localhost:8080"
                  agents:
                    - token: "%s"
                      host: "%s"
                      port: %d
                      database: "%s"
                      username: "%s"
                      password: "%s"
                    - token: "%s"
                      host: "%s"
                      port: %d
                      database: agent_beta
                      username: "%s"
                      password: "%s"
                """.formatted(
                ALPHA_TOKEN, DATABASE.getHost(), DATABASE.getMappedPort(5432),
                DATABASE.getDatabaseName(), DATABASE.getUsername(), DATABASE.getPassword(),
                BETA_TOKEN, DATABASE.getHost(), DATABASE.getMappedPort(5432),
                DATABASE.getUsername(), DATABASE.getPassword());

        try {
            Files.writeString(Path.of("config.yml"), config);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(Path.of("config.yml"));
            } catch (Exception ignored) {
            }
        }));
    }

    @SneakyThrows
    private static void createBetaDatabase() {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE agent_beta");
        }
    }

    @LocalServerPort
    protected int localServerPort;

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected String mcpUrl() {
        return "http://localhost:" + localServerPort + "/mcp";
    }

    protected String apiUrl() {
        return "http://localhost:" + localServerPort + "/api/query";
    }

    @SneakyThrows
    protected HttpResponse<String> post(String token, String body, String sessionId) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (sessionId != null) {
            builder.header("mcp-session-id", sessionId);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

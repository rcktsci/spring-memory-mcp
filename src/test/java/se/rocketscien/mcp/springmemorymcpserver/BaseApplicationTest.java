package se.rocketscien.mcp.springmemorymcpserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
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

    /** SHA-256 hex hash of an agent token: the public read-only credential of the REST endpoint. */
    protected static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every test starts with an empty database: the agent databases keep whatever a previous test
     * created (tables, rows, extensions), so without this reset the tests depend on their order.
     */
    @AfterEach
    final void cleanupAgentDatabases() {
        cleanupDatabase("agent_alpha");
        cleanupDatabase("agent_beta");
    }

    @SneakyThrows
    private static void cleanupDatabase(String database) {
        var url = DATABASE.getJdbcUrl().replace(DATABASE.getDatabaseName(), database);
        try (var connection = DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            // A fresh schema belongs to the connecting role, the database owner owns it by default (PostgreSQL 15+).
            statement.execute("ALTER SCHEMA public OWNER TO pg_database_owner");
        }
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

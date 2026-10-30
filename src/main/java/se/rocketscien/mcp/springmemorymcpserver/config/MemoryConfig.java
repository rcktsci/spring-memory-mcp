package se.rocketscien.mcp.springmemorymcpserver.config;

import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "memory")
@Value
public class MemoryConfig {

    String publicUrl;

    List<AgentConfig> agents;

    public record AgentConfig(
            String token,
            String host,
            int port,
            String database,
            String username,
            String password
    ) {

        public String getJdbcUrl() {
            return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        }
    }
}

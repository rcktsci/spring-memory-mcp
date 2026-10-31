package se.rocketscien.mcp.springmemorymcpserver.config;

import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "memory")
@Value
public class MemoryConfig {

    String publicUrl;

    List<AgentConfig> agents;

    String saltRo;

    String saltRw;

    public MemoryConfig(String publicUrl, List<AgentConfig> agents, String saltRo, String saltRw) {
        // Equal salts would make the published read token unlock the write endpoint, so both are
        // required and must differ - the server refuses to start otherwise.
        if (saltRo == null || saltRo.isBlank() || saltRw == null || saltRw.isBlank()) {
            throw new IllegalStateException("memory.salt-ro and memory.salt-rw are required");
        }
        if (saltRo.equals(saltRw)) {
            throw new IllegalStateException("memory.salt-ro and memory.salt-rw must differ");
        }
        this.publicUrl = publicUrl;
        this.agents = agents;
        this.saltRo = saltRo;
        this.saltRw = saltRw;
    }

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

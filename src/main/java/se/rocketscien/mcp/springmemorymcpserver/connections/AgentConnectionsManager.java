package se.rocketscien.mcp.springmemorymcpserver.connections;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;

import javax.sql.DataSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AgentConnectionsManager {

    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    public DataSource getDataSource(AgentConfig agent) {
        return dataSources.computeIfAbsent(
                agent.token(),
                token -> createDataSource(agent)
        );
    }

    private static HikariDataSource createDataSource(AgentConfig agent) {
        var hikariConfig = new HikariConfig();

        hikariConfig.setJdbcUrl(agent.getJdbcUrl());
        hikariConfig.setUsername(agent.username());
        hikariConfig.setPassword(agent.password());

        hikariConfig.setMinimumIdle(1);
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setConnectionTimeout(5_000L);
        hikariConfig.setIdleTimeout(60_000L);
        hikariConfig.setMaxLifetime(600_000L);

        return new HikariDataSource(hikariConfig);
    }
}

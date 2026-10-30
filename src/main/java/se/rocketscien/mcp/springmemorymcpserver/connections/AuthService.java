package se.rocketscien.mcp.springmemorymcpserver.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig;
import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;

@Service
public class AuthService {

    public static final ScopedValue<AgentConfig> CURRENT = ScopedValue.newInstance();

    private final MemoryConfig memoryConfig;

    public AuthService(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
    }

    public AgentConfig resolve(String token) {
        if (token == null) {
            return null;
        }
        for (var agent : memoryConfig.getAgents()) {
            if (MessageDigest.isEqual(agent.token().getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                return agent;
            }
        }
        return null;
    }

    public AgentConfig resolveByTokenHash(String tokenHash) {
        if (tokenHash == null) {
            return null;
        }
        for (var agent : memoryConfig.getAgents()) {
            if (MessageDigest.isEqual(sha256Hex(agent.token()).getBytes(StandardCharsets.UTF_8),
                    tokenHash.getBytes(StandardCharsets.UTF_8))) {
                return agent;
            }
        }
        return null;
    }

    public AgentConfig current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    public static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

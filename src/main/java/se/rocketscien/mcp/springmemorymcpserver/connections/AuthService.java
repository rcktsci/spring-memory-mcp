package se.rocketscien.mcp.springmemorymcpserver.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Service;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig;
import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;

@Service
public class AuthService {

    public static final ScopedValue<AgentConfig> CURRENT = ScopedValue.newInstance();

    /** Which credential an endpoint accepts: the raw agent UUID, the read token or the write token. */
    public enum Role {
        AGENT, READ, WRITE
    }

    private final MemoryConfig memoryConfig;

    public AuthService(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
    }

    public AgentConfig resolve(String token, Role role) {
        if (token == null) {
            return null;
        }
        for (var agent : memoryConfig.getAgents()) {
            var expected = switch (role) {
                case AGENT -> agent.token();
                case READ -> readToken(agent);
                case WRITE -> writeToken(agent);
            };
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                return agent;
            }
        }
        return null;
    }

    /** {@code SHA256(lowercase uuid + ":" + saltRo)} in hex - the token published in user pages. */
    public String readToken(AgentConfig agent) {
        return deriveToken(agent, memoryConfig.getSaltRo());
    }

    /** {@code SHA256(lowercase uuid + ":" + saltRw)} in hex - the token used from the shell. */
    public String writeToken(AgentConfig agent) {
        return deriveToken(agent, memoryConfig.getSaltRw());
    }

    public AgentConfig current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    private static String deriveToken(AgentConfig agent, String salt) {
        return sha256Hex(agent.token().toLowerCase(Locale.ROOT) + ":" + salt);
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

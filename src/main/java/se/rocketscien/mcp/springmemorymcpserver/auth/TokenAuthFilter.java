package se.rocketscien.mcp.springmemorymcpserver.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig;
import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class TokenAuthFilter implements Filter {

    private final ThreadLocal<AgentConfig> currentAgent = new ThreadLocal<>();

    private final MemoryConfig memoryConfig;

    public TokenAuthFilter(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
    }

    public AgentConfig currentAgent() {
        return currentAgent.get();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        var httpRequest = (HttpServletRequest) request;
        var httpResponse = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        var token = extractToken(httpRequest);
        if (token == null) {
            unauthorized(httpResponse, "Missing token");
            return;
        }

        AgentConfig agent = httpRequest.getRequestURI().startsWith("/api/")
                ? findByTokenHash(token)
                : findByToken(token);

        if (agent == null) {
            unauthorized(httpResponse, "Invalid token");
            return;
        }

        currentAgent.set(agent);
        try {
            chain.doFilter(request, response);
        } finally {
            currentAgent.remove();
        }
    }

    private String extractToken(HttpServletRequest request) {
        var auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return request.getHeader("X-Access-Token");
    }

    private AgentConfig findByToken(String token) {
        for (var agent : memoryConfig.getAgents()) {
            if (MessageDigest.isEqual(agent.token().getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                return agent;
            }
        }
        return null;
    }

    private AgentConfig findByTokenHash(String tokenHash) {
        for (var agent : memoryConfig.getAgents()) {
            if (MessageDigest.isEqual(sha256Hex(agent.token()).getBytes(StandardCharsets.UTF_8),
                    tokenHash.getBytes(StandardCharsets.UTF_8))) {
                return agent;
            }
        }
        return null;
    }

    public static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}

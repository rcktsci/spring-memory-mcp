package se.rocketscien.mcp.springmemorymcpserver.auth;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ServletRequestPathUtils;

import se.rocketscien.mcp.springmemorymcpserver.config.MemoryConfig.AgentConfig;
import se.rocketscien.mcp.springmemorymcpserver.connections.AuthService;

import java.nio.charset.StandardCharsets;

@Component
public class TokenAuthFilter implements Filter {

    private final AuthService authService;

    public TokenAuthFilter(AuthService authService) {
        this.authService = authService;
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

        // The token kind follows the path the request is actually routed to, not the raw request target:
        // the raw target is undecoded, so it can spell a path differently than the one the router uses.
        // The MCP endpoint takes the agent token, the read-only API takes the SHA-256 hash of it.
        AgentConfig agent = routedPath(httpRequest).startsWith("/api/")
                ? authService.resolveByTokenHash(token)
                : authService.resolve(token);

        if (agent == null) {
            unauthorized(httpResponse, "Invalid token");
            return;
        }

        try {
            ScopedValue.where(AuthService.CURRENT, agent)
                    .call(() -> {
                        chain.doFilter(request, response);
                        return null;
                    });
        } catch (ServletException | IOException | RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The path as the {@code DispatcherServlet} routes it: the same one the router uses - percent-decoded,
     * matrix parameters stripped, without the context path. {@code getRequestURI()} must not be used here,
     * it is the raw undecoded request target and can spell the path differently than the router does.
     */
    private static String routedPath(HttpServletRequest request) {
        var path = ServletRequestPathUtils.parse(request).pathWithinApplication();

        var routed = new StringBuilder();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                routed.append('/').append(segment.valueToMatch());
            }
        }
        return routed.isEmpty() ? "/" : routed.toString();
    }

    private String extractToken(HttpServletRequest request) {
        var auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return request.getHeader("X-Access-Token");
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}

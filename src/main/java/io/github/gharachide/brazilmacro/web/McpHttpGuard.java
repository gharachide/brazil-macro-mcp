package io.github.gharachide.brazilmacro.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects the Streamable HTTP endpoint. Requests that carry an {@code Origin} header must come
 * from an allowed origin, as the MCP specification requires against DNS rebinding; when a bearer
 * token is configured, every request must present it.
 */
public final class McpHttpGuard extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final Set<String> allowedOrigins;
    private final byte[] token;

    public McpHttpGuard(List<String> allowedOrigins, String bearerToken) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.token = bearerToken == null || bearerToken.isBlank()
                ? null
                : bearerToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
            return;
        }
        if (token != null && !hasValidToken(request.getHeader("Authorization"))) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean hasValidToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return false;
        }
        byte[] presented = authorization.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}

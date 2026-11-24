package io.github.gharachide.brazilmacro.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpHttpGuardTest {

    private static MockHttpServletResponse call(McpHttpGuard guard, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        guard.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(299); // marker: reached the endpoint
        }
        return response;
    }

    @Test
    void nonBrowserClientsWithoutOriginPass() throws Exception {
        McpHttpGuard guard = new McpHttpGuard(List.of(), null);

        assertThat(call(guard, new MockHttpServletRequest("POST", "/mcp")).getStatus()).isEqualTo(299);
    }

    @Test
    void unknownBrowserOriginsAreRejected() throws Exception {
        McpHttpGuard guard = new McpHttpGuard(List.of("https://app.example.com"), null);
        MockHttpServletRequest evil = new MockHttpServletRequest("POST", "/mcp");
        evil.addHeader("Origin", "https://attacker.example");
        MockHttpServletRequest allowed = new MockHttpServletRequest("POST", "/mcp");
        allowed.addHeader("Origin", "https://app.example.com");

        assertThat(call(guard, evil).getStatus()).isEqualTo(403);
        assertThat(call(guard, allowed).getStatus()).isEqualTo(299);
    }

    @Test
    void bearerTokenIsRequiredWhenConfigured() throws Exception {
        McpHttpGuard guard = new McpHttpGuard(List.of(), "s3cret-token");
        MockHttpServletRequest missing = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/mcp");
        wrong.addHeader("Authorization", "Bearer nope");
        MockHttpServletRequest right = new MockHttpServletRequest("POST", "/mcp");
        right.addHeader("Authorization", "Bearer s3cret-token");

        MockHttpServletResponse rejected = call(guard, missing);
        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(rejected.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(call(guard, wrong).getStatus()).isEqualTo(401);
        assertThat(call(guard, right).getStatus()).isEqualTo(299);
    }
}

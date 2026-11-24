package io.github.gharachide.brazilmacro.web;

import io.github.gharachide.brazilmacro.config.BrazilMacroProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link McpHttpGuard} on the MCP endpoint when the server runs over HTTP. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpEndpointConfiguration {

    @Bean
    FilterRegistrationBean<McpHttpGuard> mcpHttpGuard(BrazilMacroProperties properties,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        FilterRegistrationBean<McpHttpGuard> registration = new FilterRegistrationBean<>(
                new McpHttpGuard(properties.http().allowedOrigins(), properties.http().bearerToken()));
        registration.addUrlPatterns(endpoint, endpoint + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

package io.github.gharachide.brazilmacro;

import io.github.gharachide.brazilmacro.audit.AuditVerifierCli;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP server that exposes Banco Central do Brasil data (SGS time series and the Focus market
 * survey) to AI agents, with a citation on every number and fail-closed errors.
 *
 * <p>Run with {@code --spring.profiles.active=stdio} for clients that launch the server as a
 * process (IDEs, desktop assistants, the Ollama example agent), or without a profile for
 * Streamable HTTP on {@code /mcp}.
 *
 * <p>{@code java -jar brazil-macro-mcp.jar --verify-audit <file>} checks the integrity of an
 * audit log without starting the server.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BrazilMacroMcpApplication {

    public static void main(String[] args) {
        Optional<String> auditFile = AuditVerifierCli.auditFileArgument(args);
        if (auditFile.isPresent()) {
            System.exit(AuditVerifierCli.run(auditFile.get(), System.out));
        }
        SpringApplication.run(BrazilMacroMcpApplication.class, args);
    }
}

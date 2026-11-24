package io.github.gharachide.brazilmacro.agent;

import io.github.gharachide.brazilmacro.agent.eval.EvalRunner;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Asks one question and prints the answer:
 *
 * <pre>java -jar target/ollama-agent.jar "Qual foi o retorno real do CDI nos últimos 12 meses?"</pre>
 *
 * or runs the evaluation suite against one or more local models:
 *
 * <pre>java -jar target/ollama-agent.jar --eval --models=gemma4:e2b,gemma4:e4b --runs=3</pre>
 *
 * The model runs locally in Ollama; every number comes from the brazil-macro-mcp server, which
 * this application launches over STDIO.
 */
@SpringBootApplication
public class OllamaAgentApplication {

    private static volatile int exitCode;

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(OllamaAgentApplication.class, args)));
    }

    @Bean
    ExitCodeGenerator exitCode() {
        return () -> exitCode;
    }

    @Bean
    ApplicationRunner runFromCommandLine(MacroAgent agent,
            @Value("${spring.ai.ollama.chat.model}") String configuredModel) {
        return args -> {
            if (args.containsOption("eval")) {
                Map<String, List<String>> options = new HashMap<>();
                args.getOptionNames().forEach(n -> options.put(n, args.getOptionValues(n)));
                EvalRunner.Report report = new EvalRunner(agent, Clock.systemUTC())
                        .run(EvalRunner.options(options, configuredModel));
                report.summaries().forEach(s -> System.out.printf("%s: %.0f%% passed (p50 %.1fs)%n",
                        s.model(), s.passRate() * 100, s.p50Millis() / 1000.0));
                System.out.println("Report: " + report.markdown().toAbsolutePath());
                System.out.println("Transcripts: " + report.json().toAbsolutePath());
                exitCode = report.belowThreshold() ? 1 : 0;
                return;
            }
            String question = String.join(" ", args.getNonOptionArgs()).trim();
            if (!question.isEmpty()) {
                System.out.println(agent.ask(question));
            }
        };
    }
}

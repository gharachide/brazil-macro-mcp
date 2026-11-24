package io.github.gharachide.brazilmacro.agent.eval;

import io.github.gharachide.brazilmacro.agent.MacroAgent;
import io.github.gharachide.brazilmacro.agent.MacroAgent.Turn;
import io.github.gharachide.brazilmacro.agent.eval.Grader.Grade;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the evaluation cases against one or more local models and writes a Markdown report and a
 * JSON file with every transcript:
 *
 * <pre>java -jar target/ollama-agent.jar --eval --models=gemma4:e2b,gemma4:e4b --runs=3</pre>
 *
 * Options: {@code --models} (default: the configured model), {@code --runs} (default 1),
 * {@code --cases=id,id} (default all), {@code --cases-file=path} (default the bundled
 * eval/cases.json), {@code --out=dir} (default target/eval), {@code --min-pass-rate=0.8} (exit
 * with status 1 below it), {@code --baseline} (also run every model without tools, graded against
 * the official answers: the control that shows what the MCP server adds).
 */
public final class EvalRunner {

    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public record Options(List<String> models, int runs, Set<String> caseIds, Path casesFile, Path out,
            Double minPassRate, boolean baseline) {

        public Options(List<String> models, int runs, Set<String> caseIds, Path casesFile, Path out,
                Double minPassRate) {
            this(models, runs, caseIds, casesFile, out, minPassRate, false);
        }
    }

    /** One graded attempt. */
    public record Result(String model, int run, EvalCase evalCase, Turn turn, Grade grade, String failure) {

        boolean passed() {
            return failure == null && grade.passed();
        }
    }

    /** Pass rates and latency of one model. */
    public record Summary(String model, int attempts, double passRate, double tool, double args,
            double grounded, double cited, double abstained, long p50Millis, long p95Millis, int failures,
            double invented) {
    }

    static final String NO_TOOLS = " (no tools)";

    public record Report(Path markdown, Path json, List<Summary> summaries, boolean belowThreshold) {
    }

    private final MacroAgent agent;
    private final Clock clock;

    public EvalRunner(MacroAgent agent, Clock clock) {
        this.agent = agent;
        this.clock = clock;
    }

    public Report run(Options options) {
        LocalDate today = LocalDate.now(clock.withZone(BRAZIL));
        List<EvalCase> cases = load(options.casesFile()).stream()
                .filter(c -> options.caseIds().isEmpty() || options.caseIds().contains(c.id()))
                .map(c -> c.resolve(today))
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No evaluation cases selected.");
        }
        Map<String, String> references = options.baseline() ? references(cases) : Map.of();
        List<Result> results = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String model : options.models()) {
            for (boolean withTools : options.baseline() ? List.of(true, false) : List.of(true)) {
                String label = withTools ? model : model + NO_TOOLS;
                labels.add(label);
                models:
                for (int run = 1; run <= options.runs(); run++) {
                    for (EvalCase c : cases) {
                        Result r = attempt(label, model, withTools, run, c, references.get(c.id()));
                        results.add(r);
                        if (r.failure() != null && r.failure().contains("not found")) {
                            System.err.printf(Locale.ROOT, "[%s] model not found in Ollama; skipping it. "
                                    + "Pull it first: ollama pull %s%n", model, model);
                            break models;
                        }
                        System.err.printf(Locale.ROOT, "[%s run %d] %-24s %s%n", label, run, c.id(),
                                r.passed() ? "PASS" : "FAIL" + (r.failure() != null ? " (" + r.failure() + ")" : ""));
                    }
                }
            }
        }
        List<Summary> summaries = labels.stream()
                .map(m -> summarize(m, results.stream().filter(r -> r.model().equals(m)).toList()))
                .toList();
        boolean below = options.minPassRate() != null
                && summaries.stream().anyMatch(s -> s.passRate() < options.minPassRate());
        return write(options, today, cases, results, summaries, below);
    }

    private Result attempt(String label, String model, boolean withTools, int run, EvalCase c, String reference) {
        try {
            Turn turn = agent.turn(c.question(), model, withTools);
            Grade grade = withTools ? Grader.grade(c, turn) : Grader.gradeBaseline(c, turn, reference);
            return new Result(label, run, c, turn, grade, null);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage()).lines().findFirst().orElse("error");
            return new Result(label, run, c, null, null, e.getClass().getSimpleName() + ": " + message);
        }
    }

    /** The official answer to each question, fetched once through the MCP server itself. */
    private Map<String, String> references(List<EvalCase> cases) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EvalCase c : cases) {
            if (c.referenceTool() == null) {
                continue;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            c.referenceArgs().forEach((k, v) -> args.put(k, v.matches("\\d+") ? Integer.valueOf(v) : v));
            try {
                out.put(c.id(), agent.callTool(c.referenceTool(), JSON.writeValueAsString(args)));
            } catch (RuntimeException e) {
                System.err.printf(Locale.ROOT, "[reference] %s: %s%n", c.id(), e.getMessage());
            }
        }
        return out;
    }

    static Summary summarize(String model, List<Result> results) {
        List<Result> graded = results.stream().filter(r -> r.failure() == null).toList();
        List<Long> latencies = graded.stream().map(r -> r.turn().millis()).sorted().toList();
        List<Result> traps = graded.stream().filter(r -> r.evalCase().isTrap()).toList();
        return new Summary(model, results.size(),
                rate(results, Result::passed),
                rate(graded.stream().filter(r -> r.grade().tool() != null).toList(), r -> r.grade().tool()),
                rate(graded.stream().filter(r -> r.grade().args() != null).toList(), r -> r.grade().args()),
                rate(graded, r -> r.grade().grounded()),
                rate(graded.stream().filter(r -> !r.evalCase().isTrap()).toList(), r -> r.grade().cited()),
                rate(traps, r -> Boolean.TRUE.equals(r.grade().abstained())),
                percentile(latencies, 50), percentile(latencies, 95),
                results.size() - graded.size(),
                rate(graded, r -> r.grade().invented()));
    }

    private static double rate(List<Result> results, Predicate<Result> ok) {
        return results.isEmpty() ? Double.NaN : (double) results.stream().filter(ok).count() / results.size();
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    static List<EvalCase> load(Path file) {
        try (InputStream in = file != null ? Files.newInputStream(file)
                : new ClassPathResource("eval/cases.json").getInputStream()) {
            return JSON.readValue(in, new TypeReference<List<EvalCase>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ report

    private Report write(Options options, LocalDate today, List<EvalCase> cases, List<Result> results,
            List<Summary> summaries, boolean below) {
        String stamp = LocalDateTime.now(clock.withZone(BRAZIL)).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = "eval-" + stamp;
        try {
            Files.createDirectories(options.out());
            Path md = options.out().resolve(name + ".md");
            Path json = options.out().resolve(name + ".json");
            Files.writeString(md, markdown(today, cases, results, summaries, options));
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("date", today.toString());
            raw.put("runs", options.runs());
            raw.put("summaries", summaries);
            raw.put("results", results);
            Files.writeString(json, JSON.writeValueAsString(raw));
            return new Report(md, json, summaries, below);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String markdown(LocalDate today, List<EvalCase> cases, List<Result> results,
            List<Summary> summaries, Options options) {
        StringBuilder md = new StringBuilder();
        md.append("# brazil-macro-mcp eval — ").append(today).append("\n\n");
        md.append(cases.size()).append(" cases × ").append(options.runs()).append(" run(s). ")
                .append("Checks are deterministic: right tool, right arguments, every decimal in the answer ")
                .append("found in a tool result, source cited, and declining on trap questions. ")
                .append("Latency includes tool calls; the first case of each model also includes loading it.\n\n");
        if (options.baseline()) {
            md.append("Rows marked *(no tools)* are the control: the same model and questions with no tools, graded ")
                    .append("against the official answers fetched through the MCP server. *Invented* is the share of ")
                    .append("answers with at least one decimal number that matches no official data.\n\n");
        }
        md.append("| Model | Pass | Invented | Tool | Args | Grounded | Cited | Abstained on traps | p50 | p95 | Errors |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Summary s : summaries) {
            md.append(String.format(Locale.ROOT, "| %s | **%s** | %s | %s | %s | %s | %s | %s | %.1fs | %.1fs | %d |%n",
                    s.model(), pct(s.passRate()), pct(s.invented()), pct(s.tool()), pct(s.args()), pct(s.grounded()),
                    pct(s.cited()), pct(s.abstained()), s.p50Millis() / 1000.0, s.p95Millis() / 1000.0, s.failures()));
        }
        md.append("\n## Per case\n\n| Case | Category |");
        summaries.forEach(s -> md.append(' ').append(s.model()).append(" |"));
        md.append("\n|---|---|");
        summaries.forEach(s -> md.append("---|"));
        md.append('\n');
        for (EvalCase c : cases) {
            md.append("| ").append(c.id()).append(" | ").append(c.category()).append(" |");
            for (Summary s : summaries) {
                List<Result> rs = results.stream()
                        .filter(r -> r.model().equals(s.model()) && r.evalCase().id().equals(c.id())).toList();
                long passed = rs.stream().filter(Result::passed).count();
                md.append(' ').append(passed).append('/').append(rs.size());
                if (passed < rs.size()) {
                    md.append(" ").append(why(rs.stream().filter(r -> !r.passed()).findFirst().orElseThrow()));
                }
                md.append(" |");
            }
            md.append('\n');
        }
        md.append("\n## Failures\n");
        results.stream().filter(r -> !r.passed()).forEach(r -> {
            md.append("\n### ").append(r.model()).append(" · ").append(r.evalCase().id())
                    .append(" (run ").append(r.run()).append(")\n\n");
            md.append("**Question:** ").append(r.evalCase().question()).append("\n\n");
            if (r.failure() != null) {
                md.append("**Error:** ").append(r.failure()).append("\n");
                return;
            }
            md.append("**Failed:** ").append(why(r)).append("\n\n");
            md.append("**Tools called:** ").append(r.turn().toolCalls().isEmpty() ? "none"
                    : r.turn().toolCalls().stream().map(t -> "`" + t.tool() + " " + t.arguments() + "`"
                            + (t.error() ? " (error)" : "")).collect(Collectors.joining(", ")))
                    .append("\n\n");
            md.append("**Answer:**\n\n> ").append(String.valueOf(r.turn().answer()).replace("\n", "\n> ")).append('\n');
        });
        if (results.stream().allMatch(Result::passed)) {
            md.append("\nNone.\n");
        }
        return md.toString();
    }

    private static String why(Result r) {
        if (r.failure() != null) {
            return "error";
        }
        Grade g = r.grade();
        List<String> failed = new ArrayList<>();
        if (Boolean.FALSE.equals(g.tool())) {
            failed.add("tool");
        }
        if (Boolean.FALSE.equals(g.args())) {
            failed.add("args");
        }
        if (!g.grounded()) {
            String answer = r.turn().answer() == null ? "" : r.turn().answer();
            failed.add(!g.ungrounded().isEmpty() ? "ungrounded " + g.ungrounded()
                    : answer.isBlank() ? "empty answer" : "no figure");
        }
        if (g.abstained() == null && !g.cited()) {
            failed.add("no citation");
        }
        if (Boolean.FALSE.equals(g.abstained())) {
            failed.add("did not abstain");
        }
        return String.join(", ", failed);
    }

    private static String pct(double value) {
        return Double.isNaN(value) ? "–" : Math.round(value * 100) + "%";
    }

    // ------------------------------------------------------------------ options

    public static Options options(Map<String, List<String>> args, String configuredModel) {
        List<String> models = split(first(args, "models", configuredModel));
        int runs = Integer.parseInt(first(args, "runs", "1"));
        if (runs < 1) {
            throw new IllegalArgumentException("--runs must be at least 1.");
        }
        Set<String> ids = Set.copyOf(split(first(args, "cases", "")));
        String file = first(args, "cases-file", null);
        Path out = Path.of(first(args, "out", "target/eval"));
        String min = first(args, "min-pass-rate", null);
        return new Options(models, runs, ids, file == null ? null : Path.of(file), out,
                min == null ? null : Double.valueOf(min), args.containsKey("baseline"));
    }

    private static String first(Map<String, List<String>> args, String name, String fallback) {
        List<String> values = args.get(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }

    private static List<String> split(String csv) {
        return csv == null ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

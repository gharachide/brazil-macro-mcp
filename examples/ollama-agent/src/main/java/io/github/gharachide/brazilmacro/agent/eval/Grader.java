package io.github.gharachide.brazilmacro.agent.eval;

import io.github.gharachide.brazilmacro.agent.MacroAgent.ToolCall;
import io.github.gharachide.brazilmacro.agent.MacroAgent.Turn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grades one answer with deterministic checks, no judge model:
 * <ul>
 * <li><b>tool</b>: the model called one of the expected tools;</li>
 * <li><b>args</b>: one of those calls carried the expected arguments;</li>
 * <li><b>grounded</b>: every decimal number in the answer appears in a tool result (allowing for
 * rounding and for a dropped minus sign) or in the question — the check that catches invented
 * values and the model's own arithmetic;</li>
 * <li><b>cited</b>: the answer names its source (SGS, Focus, BCB);</li>
 * <li><b>abstained</b> (trap questions only): the model got a tool error, gave no figure, or answered
 * with a listed alternative (e.g. the Focus projection for a month that has not happened).</li>
 * </ul>
 */
public final class Grader {

    /** A decimal number written either way: 13.75, 13,75, 1.234,56. */
    private static final Pattern DECIMAL = Pattern.compile("-?\\d{1,3}(?:\\.\\d{3})+,\\d+|-?\\d+[.,]\\d+");
    private static final Pattern TOOL_NUMBER = Pattern.compile("-?\\d+\\.\\d+(?:[eE][-+]?\\d+)?");
    private static final List<String> CITATIONS = List.of("sgs", "focus", "bcb", "banco central");
    private static final JsonMapper JSON = JsonMapper.shared();

    /**
     * {@code tool} and {@code args} are null for an answer given without tools. {@code ungrounded}
     * lists the numbers that match no official data: invented, misremembered or computed by the model.
     */
    public record Grade(Boolean tool, Boolean args, boolean grounded, boolean cited, Boolean abstained,
            List<String> ungrounded) {

        public boolean passed() {
            return !Boolean.FALSE.equals(tool) && !Boolean.FALSE.equals(args) && grounded
                    && (abstained == null ? cited : abstained);
        }

        public boolean invented() {
            return !ungrounded.isEmpty();
        }
    }

    private Grader() {
    }

    public static Grade grade(EvalCase c, Turn turn) {
        String answer = turn.answer() == null ? "" : turn.answer();
        List<ToolCall> calls = turn.toolCalls();
        List<ToolCall> expected = calls.stream()
                .filter(t -> c.expectTools().isEmpty() || c.expectTools().contains(t.tool())).toList();
        boolean alternative = c.alsoAccept().stream().anyMatch(a -> calls.stream()
                .anyMatch(t -> t.tool().equals(a.tool()) && !t.error() && carries(t, a.args())));
        boolean tool = c.expectTools().isEmpty() || !expected.isEmpty() || alternative;
        boolean args = c.expectArgs().isEmpty() || alternative
                || expected.stream().anyMatch(t -> carries(t, c.expectArgs()));
        List<String> ungrounded = ungrounded(answer, c.question(), calls);
        boolean cited = CITATIONS.stream().anyMatch(answer.toLowerCase(Locale.ROOT)::contains);
        Boolean abstained = c.isTrap()
                ? calls.stream().anyMatch(ToolCall::error) || numbers(answer).isEmpty() || alternative
                : null;
        return new Grade(tool, args, ungrounded.isEmpty() && !answer.isBlank(), cited, abstained, ungrounded);
    }

    /**
     * Grades an answer given without tools against the official answer, fetched separately with the
     * case's reference call. A number recalled correctly counts as grounded; a wrong one does not. A
     * normal question passes only with at least one correct figure and a named source; a trap
     * question passes when the answer gives no figure.
     */
    public static Grade gradeBaseline(EvalCase c, Turn turn, String reference) {
        String answer = turn.answer() == null ? "" : turn.answer();
        List<ToolCall> official = reference == null ? List.of()
                : List.of(new ToolCall(c.referenceTool(), "{}", reference, false));
        List<String> ungrounded = ungrounded(answer, c.question(), official);
        boolean figures = !numbers(answer).isEmpty();
        boolean grounded = ungrounded.isEmpty() && !answer.isBlank() && (c.isTrap() || figures);
        boolean cited = CITATIONS.stream().anyMatch(answer.toLowerCase(Locale.ROOT)::contains);
        Boolean abstained = c.isTrap() ? !figures : null;
        return new Grade(null, null, grounded, cited, abstained, ungrounded);
    }

    static boolean carries(ToolCall call, Map<String, String> expected) {
        JsonNode args;
        try {
            args = JSON.readTree(call.arguments());
        } catch (RuntimeException e) {
            return false;
        }
        return expected.entrySet().stream().allMatch(e -> {
            JsonNode value = args.path(e.getKey());
            return !value.isMissingNode() && value.asString().trim().equalsIgnoreCase(e.getValue());
        });
    }

    /** The decimal numbers in the answer that no tool returned. */
    static List<String> ungrounded(String answer, String question, List<ToolCall> calls) {
        List<BigDecimal> known = new ArrayList<>();
        calls.forEach(t -> known.addAll(toolNumbers(t.result())));
        known.addAll(numbers(question));
        List<String> missing = new ArrayList<>();
        Matcher m = DECIMAL.matcher(answer);
        while (m.find()) {
            BigDecimal n = parse(m.group());
            if (known.stream().noneMatch(k -> matches(n, k))) {
                missing.add(m.group());
            }
        }
        return missing;
    }

    static boolean matches(BigDecimal said, BigDecimal source) {
        BigDecimal a = said.abs();
        BigDecimal b = source.abs();
        int scale = Math.max(a.scale(), 0);
        return b.compareTo(a) == 0
                || b.setScale(scale, RoundingMode.HALF_UP).compareTo(a) == 0
                || b.setScale(scale, RoundingMode.HALF_EVEN).compareTo(a) == 0
                || b.setScale(scale, RoundingMode.DOWN).compareTo(a) == 0;
    }

    static List<BigDecimal> numbers(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = DECIMAL.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(parse(m.group()));
        }
        return out;
    }

    private static List<BigDecimal> toolNumbers(String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = TOOL_NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(new BigDecimal(m.group()));
        }
        return out;
    }

    /** Reads 13.75, 13,75 and 1.234,56 (pt-BR) as numbers. */
    static BigDecimal parse(String token) {
        String t = token.contains(",") ? token.replace(".", "").replace(',', '.') : token;
        return new BigDecimal(t);
    }
}

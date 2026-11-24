package io.github.gharachide.brazilmacro.agent.eval;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One evaluation question and what a good answer needs.
 *
 * @param id         stable identifier, used with {@code --cases=}
 * @param category   latest, accumulated, real_return, series, focus or abstain
 * @param question   asked verbatim; may contain {@code {LAST_YEAR}}, {@code {CURRENT_YEAR}} or
 *                   {@code {NEXT_YEAR}}
 * @param expectTools the model passes "tool" if it calls any of these (empty: any tool, or none)
 * @param expectArgs  arguments one of those calls must carry (case-insensitive; placeholders allowed)
 * @param abstain     the right answer is to decline: the data does not exist or cannot be computed
 * @param alsoAccept  other correct ways to answer: a tool and the arguments it must carry
 * @param referenceArgs full arguments for the call that fetches the official answer, used to grade
 *                   answers given without tools (default: {@code expectArgs})
 */
public record EvalCase(String id, String category, String question, List<String> expectTools,
        Map<String, String> expectArgs, Boolean abstain, List<Alternative> alsoAccept,
        Map<String, String> referenceArgs) {

    /** Another correct tool call for the same question. */
    public record Alternative(String tool, Map<String, String> args) {

        public Alternative {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    public EvalCase(String id, String category, String question, List<String> expectTools,
            Map<String, String> expectArgs, Boolean abstain) {
        this(id, category, question, expectTools, expectArgs, abstain, null, null);
    }

    public EvalCase(String id, String category, String question, List<String> expectTools,
            Map<String, String> expectArgs, Boolean abstain, List<Alternative> alsoAccept) {
        this(id, category, question, expectTools, expectArgs, abstain, alsoAccept, null);
    }

    public EvalCase {
        expectTools = expectTools == null ? List.of() : List.copyOf(expectTools);
        expectArgs = expectArgs == null ? Map.of() : Map.copyOf(expectArgs);
        abstain = Boolean.TRUE.equals(abstain);
        alsoAccept = alsoAccept == null ? List.of() : List.copyOf(alsoAccept);
        referenceArgs = referenceArgs == null ? expectArgs : Map.copyOf(referenceArgs);
    }

    /** The tool whose result is the official answer, or null for a trap question. */
    public String referenceTool() {
        return abstain || expectTools.isEmpty() ? null : expectTools.getFirst();
    }

    public boolean isTrap() {
        return abstain;
    }

    /** Replaces the year placeholders, so the cases stay valid as time passes. */
    public EvalCase resolve(LocalDate today) {
        Map<String, String> args = new LinkedHashMap<>();
        expectArgs.forEach((k, v) -> args.put(k, fill(v, today)));
        List<Alternative> alternatives = alsoAccept.stream().map(a -> {
            Map<String, String> filled = new LinkedHashMap<>();
            a.args().forEach((k, v) -> filled.put(k, fill(v, today)));
            return new Alternative(a.tool(), filled);
        }).toList();
        Map<String, String> reference = new LinkedHashMap<>();
        referenceArgs.forEach((k, v) -> reference.put(k, fill(v, today)));
        return new EvalCase(id, category, fill(question, today), expectTools, args, abstain, alternatives,
                reference);
    }

    static String fill(String text, LocalDate today) {
        int year = today.getYear();
        return text.replace("{LAST_YEAR}", String.valueOf(year - 1))
                .replace("{CURRENT_YEAR}", String.valueOf(year))
                .replace("{NEXT_YEAR}", String.valueOf(year + 1));
    }
}

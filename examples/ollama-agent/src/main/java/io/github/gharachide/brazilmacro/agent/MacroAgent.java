package io.github.gharachide.brazilmacro.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * A local open model with the brazil-macro-mcp tools. The model decides which tools to call;
 * the server supplies the numbers, the arithmetic and the citations. Every tool call of a turn is
 * recorded, so answers can be evaluated against what the tools actually returned.
 */
@Service
public class MacroAgent {

    static final String SYSTEM_PROMPT = """
            You answer questions about Brazil's economy using only the brazil-macro tools.
            Today is %s (America/Sao_Paulo).
            - Quote every number exactly as a tool returns it and cite it with its citation.citeAs.
            - Use accumulated_change and real_return for any compounding; never do the arithmetic yourself.
            - Monthly values are dated on the first day of the month; a month still in progress cannot be compounded.
            - If a tool returns an error, say which data is unavailable and why. Never estimate or use remembered values.
            - Answer in the language of the question.
            """;

    /** The control: the same model, the same date, no tools and no data. */
    static final String BASELINE_PROMPT = """
            You answer questions about Brazil's economy.
            Today is %s (America/Sao_Paulo).
            Answer in the language of the question.
            """;

    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    /** One tool call made by the model: arguments as sent, and the result or error it got back. */
    public record ToolCall(String tool, String arguments, String result, boolean error) {
    }

    /** The answer of one turn with the tool calls behind it. */
    public record Turn(String answer, List<ToolCall> toolCalls, long millis) {
    }

    private final ChatClient chat;
    private final List<ToolCallback> tools;
    private final Map<String, ToolCallback> byName;
    private final Clock clock;
    private volatile List<ToolCall> current = new CopyOnWriteArrayList<>();

    @Autowired
    public MacroAgent(ChatClient.Builder builder, ToolCallbackProvider mcpTools) {
        this(builder, mcpTools, Clock.systemUTC());
    }

    MacroAgent(ChatClient.Builder builder, ToolCallbackProvider mcpTools, Clock clock) {
        this.chat = builder.build();
        this.tools = Arrays.stream(mcpTools.getToolCallbacks()).map(t -> (ToolCallback) new Recording(t)).toList();
        this.byName = Arrays.stream(mcpTools.getToolCallbacks())
                .collect(Collectors.toMap(t -> t.getToolDefinition().name(), Function.identity(), (a, b) -> a));
        this.clock = clock;
    }

    public String ask(String question) {
        return turn(question, null).answer();
    }

    /** Runs one question, optionally on a model other than the configured one. */
    public Turn turn(String question, String model) {
        return turn(question, model, true);
    }

    /**
     * Runs one question. Without tools the model gets no data at all: the control that shows what
     * the MCP server adds.
     */
    public synchronized Turn turn(String question, String model, boolean withTools) {
        current = new CopyOnWriteArrayList<>();
        long started = System.nanoTime();
        LocalDate today = LocalDate.now(clock.withZone(BRAZIL));
        ChatClient.ChatClientRequestSpec request = chat.prompt()
                .system((withTools ? SYSTEM_PROMPT : BASELINE_PROMPT).formatted(today))
                .user(question);
        if (withTools) {
            request = request.toolCallbacks(tools);
        }
        if (model != null) {
            request = request.options(OllamaChatOptions.builder().model(model));
        }
        String answer = request.call().content();
        return new Turn(answer, List.copyOf(current), (System.nanoTime() - started) / 1_000_000);
    }

    /** Calls an MCP tool directly, outside any conversation: how the eval fetches official answers. */
    public String callTool(String name, String argumentsJson) {
        ToolCallback tool = byName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool.call(argumentsJson);
    }

    /** Passes calls through to the MCP tool and keeps a copy of what went in and out. */
    private final class Recording implements ToolCallback {

        private final ToolCallback delegate;

        Recording(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return call(input, null);
        }

        @Override
        public String call(String input, ToolContext context) {
            String name = delegate.getToolDefinition().name();
            try {
                String output = context == null ? delegate.call(input) : delegate.call(input, context);
                current.add(new ToolCall(name, input, output, false));
                return output;
            } catch (RuntimeException e) {
                current.add(new ToolCall(name, input, String.valueOf(e.getMessage()), true));
                throw e;
            }
        }
    }
}

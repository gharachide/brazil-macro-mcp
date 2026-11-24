package io.github.gharachide.brazilmacro.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * In-process stand-in for the BCB APIs, on the JDK HTTP server. Tests decide the response to
 * each request and can inspect every request received.
 */
public final class FakeBcbServer implements AutoCloseable {

    public record Request(String path, Map<String, String> query, String rawQuery, String userAgent) {
    }

    public record Response(int status, String body, long delayMillis) {

        public static Response json(String body) {
            return new Response(200, body, 0);
        }

        public static Response status(int status) {
            return new Response(status, "{\"error\":\"status " + status + "\"}", 0);
        }

        public Response delayed(long millis) {
            return new Response(status, body, millis);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Response> handler = request -> Response.status(404);

    private FakeBcbServer(HttpServer server) {
        this.server = server;
    }

    public static FakeBcbServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeBcbServer fake = new FakeBcbServer(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public FakeBcbServer respond(Function<Request, Response> handler) {
        this.handler = handler;
        return this;
    }

    public URI sgsBaseUrl() {
        return URI.create(baseUrl() + "/dados/serie");
    }

    public URI focusBaseUrl() {
        return URI.create(baseUrl() + "/olinda/servico/Expectativas/versao/v1/odata");
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            Request request = new Request(uri.getPath(), parseQuery(uri.getRawQuery()), uri.getRawQuery(),
                    exchange.getRequestHeaders().getFirst("User-Agent"));
            requests.add(request);
            Response response = handler.apply(request);
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            if (body.length == 0) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            // Client went away (for example after a timeout).
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }
}

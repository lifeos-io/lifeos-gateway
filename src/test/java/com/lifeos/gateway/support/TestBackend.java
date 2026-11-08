package com.lifeos.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fake service behind the gateway (JDK HTTP server):
 * <ul>
 *   <li>{@code .../slow} answers after 3 s</li>
 *   <li>{@code .../messages} streams 4 SSE events, one every 500 ms</li>
 *   <li>{@code .../stalled/messages} sends one SSE event, then nothing for 5 s</li>
 *   <li>anything else echoes the request path and headers as JSON (header names lower-case)</li>
 * </ul>
 */
public final class TestBackend {

    private final HttpServer server;

    private TestBackend(HttpServer server) {
        this.server = server;
    }

    public static TestBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", TestBackend::handle);
            server.start();
            return new TestBackend(server);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    /** A URI on which nothing listens (connection refused). */
    public static String deadUri() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.endsWith("/slow")) {
                Thread.sleep(3000);
                send(exchange, 200, "application/json", "{\"slow\":true}");
            } else if (path.endsWith("/stalled/messages")) {
                exchange.getResponseHeaders().put("Content-Type", List.of("text/event-stream"));
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("event: started\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(5000);
            } else if (path.endsWith("/messages")) {
                exchange.getResponseHeaders().put("Content-Type", List.of("text/event-stream"));
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 1; i <= 4; i++) {
                        out.write(("event: delta\ndata: part-" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(500);
                    }
                }
            } else {
                Map<String, Object> body = new TreeMap<>();
                body.put("path", path);
                exchange.getRequestHeaders().forEach((name, values) ->
                        body.put(name.toLowerCase(), String.join(",", values)));
                send(exchange, 200, "application/json", JsonMapper.builder().build().writeValueAsString(body));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of(contentType));
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

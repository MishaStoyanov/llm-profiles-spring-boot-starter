package io.github.mishastoyanov.llmprofiles.vendor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for a vendor's HTTP API: answers every request with one canned body and remembers
 * what was sent, so a test sees the request as the vendor would.
 */
final class StubServer implements AutoCloseable {

    record Request(String method, String path, String body) {

        JsonNode json() {
            return JsonMapper.shared().readTree(body);
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private volatile String contentType = "application/json";
    private volatile String body = "{}";
    /** Bodies served one per request before falling back to {@link #body}. */
    private final Queue<String> queued = new ConcurrentLinkedQueue<>();
    private volatile long delayMillis;
    private volatile int status = 200;

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String next = queued.poll();
            byte[] bytes = (next != null ? next : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException e) {
                // the client gave up first, which is what a timeout test wants
            }
        });
        server.start();
    }

    StubServer answer(String contentType, String body) {
        this.contentType = contentType;
        this.body = body;
        return this;
    }

    StubServer json(String body) {
        return answer("application/json", body);
    }

    /** JSON answers in turn, one per request; the last keeps being served after that. */
    StubServer jsonInTurn(String... bodies) {
        for (int i = 0; i < bodies.length - 1; i++) {
            queued.add(bodies[i]);
        }
        return json(bodies[bodies.length - 1]);
    }

    /** Server-sent events, one {@code data:} line per element, with an {@code event:} name if given as "name|json". */
    StubServer events(String... events) {
        StringBuilder out = new StringBuilder();
        for (String event : events) {
            int bar = event.indexOf('|');
            if (bar > 0 && !event.startsWith("{")) {
                out.append("event: ").append(event, 0, bar).append('\n');
                event = event.substring(bar + 1);
            }
            out.append("data: ").append(event).append("\n\n");
        }
        return answer("text/event-stream", out.toString());
    }

    StubServer status(int status) {
        this.status = status;
        return this;
    }

    StubServer delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    Request last() {
        return requests.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

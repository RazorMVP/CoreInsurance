package com.nubeero.cia.portal.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real, minimal {@code /partner/v1/**} stand-in for {@link PortalProxyIT} — built on the JDK's
 * built-in {@code com.sun.net.httpserver.HttpServer} (zero new test dependencies) rather than a
 * mocking library, so every request {@link PartnerApiProxyClient} sends genuinely travels over a
 * loopback TCP socket. That is exactly what the task's PROXY FIDELITY requirement calls for: the
 * production {@link PartnerApiProxyClient} code makes a real HTTP call regardless of what's on the
 * other end — pointing it at this stub (instead of the real, self-loopback {@code /partner/v1/**})
 * isolates the assertion to "did the BFF make the right real HTTP call, with the right Bearer
 * token, and relay the response back verbatim" without needing a live Keycloak + a real tenant
 * realm's JWKS to also validate a signed JWT (that plumbing is exercised separately by {@code
 * cia-partner-api}'s own test suite — {@code PartnerScopeFilterTest} et al.).
 *
 * <p>Records every request it receives ({@link #recordedRequests()}) so tests can assert exactly
 * what {@code PartnerApiProxyClient} sent — method, path, query, the {@code Authorization} header
 * value (proving the minted Bearer token was attached server-side), and the raw body.
 *
 * <p>{@code /webhooks} is handled statefully (an in-memory list, create/list/delete) so the
 * webhook-CRUD IT reads as a genuine round trip rather than three independently-canned responses.
 * Every other path is served from {@link #stub}bed canned responses.
 */
final class StubPartnerApiUpstream {

    private final HttpServer server;
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    private final Map<String, StubResponse> stubs = new HashMap<>();
    private final List<Map<String, Object>> webhooks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger webhookSeq = new AtomicInteger();

    record RecordedRequest(String method, String path, String query, String authorizationHeader, String body) {
    }

    private record StubResponse(int status, String contentType, String body) {
    }

    StubPartnerApiUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Registers a canned response for one exact {@code METHOD path} pair (path under {@code /partner/v1}). */
    void stub(String method, String path, int status, String contentType, String body) {
        stubs.put(method + " /partner/v1" + path, new StubResponse(status, contentType, body));
    }

    List<RecordedRequest> recordedRequests() {
        return List.copyOf(recorded);
    }

    void clearRecordedRequests() {
        recorded.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String authorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
        recorded.add(new RecordedRequest(method, path, query, authorizationHeader,
                new String(requestBody, StandardCharsets.UTF_8)));

        try {
            if (path.equals("/partner/v1/webhooks") || path.startsWith("/partner/v1/webhooks/")) {
                handleWebhooks(exchange, method, path, requestBody);
                return;
            }
            StubResponse stub = stubs.get(method + " " + path);
            if (stub == null) {
                respond(exchange, 404, "application/json",
                        "{\"errors\":[{\"code\":\"NOT_STUBBED\",\"message\":\"" + method + " " + path + "\"}]}");
                return;
            }
            respond(exchange, stub.status(), stub.contentType(), stub.body());
        } finally {
            exchange.close();
        }
    }

    private void handleWebhooks(HttpExchange exchange, String method, String path, byte[] requestBody)
            throws IOException {
        if ("POST".equals(method) && path.equals("/partner/v1/webhooks")) {
            String id = "wh-" + webhookSeq.incrementAndGet();
            Map<String, Object> created = new HashMap<>();
            created.put("id", id);
            created.put("raw", new String(requestBody, StandardCharsets.UTF_8));
            webhooks.add(created);
            respond(exchange, 201, "application/json",
                    "{\"data\":{\"id\":\"" + id + "\"}}");
            return;
        }
        if ("GET".equals(method) && path.equals("/partner/v1/webhooks")) {
            StringBuilder body = new StringBuilder("{\"data\":[");
            synchronized (webhooks) {
                for (int i = 0; i < webhooks.size(); i++) {
                    if (i > 0) {
                        body.append(',');
                    }
                    body.append("{\"id\":\"").append(webhooks.get(i).get("id")).append("\"}");
                }
            }
            body.append("]}");
            respond(exchange, 200, "application/json", body.toString());
            return;
        }
        if ("DELETE".equals(method) && path.startsWith("/partner/v1/webhooks/")) {
            String id = path.substring("/partner/v1/webhooks/".length());
            boolean removed = webhooks.removeIf(w -> id.equals(w.get("id")));
            respond(exchange, removed ? 204 : 404, null, null);
            return;
        }
        respond(exchange, 404, "application/json", "{\"errors\":[{\"code\":\"NOT_STUBBED\"}]}");
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}

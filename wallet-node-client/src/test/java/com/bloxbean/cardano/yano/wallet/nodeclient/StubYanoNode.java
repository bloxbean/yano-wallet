package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Minimal in-process stand-in for a Yano node's REST API. Handlers are
 * registered per exact path; requests are recorded for assertions.
 */
class StubYanoNode implements AutoCloseable {
    record RecordedRequest(String method, String path, String contentType, byte[] body) {
    }

    private final HttpServer server;
    private final Map<String, Function<RecordedRequest, Response>> handlers = new LinkedHashMap<>();
    private final List<RecordedRequest> requests = new ArrayList<>();

    record Response(int status, String contentType, String body) {
        static Response json(String body) {
            return new Response(200, "application/json", body);
        }
    }

    StubYanoNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
    }

    void on(String path, String body) {
        on(path, req -> Response.json(body));
    }

    void on(String path, Function<RecordedRequest, Response> handler) {
        handlers.put(path, handler);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/";
    }

    synchronized List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        RecordedRequest request = new RecordedRequest(
                exchange.getRequestMethod(),
                path + (exchange.getRequestURI().getQuery() != null ? "?" + exchange.getRequestURI().getQuery() : ""),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body);
        synchronized (this) {
            requests.add(request);
        }

        Function<RecordedRequest, Response> handler = handlers.get(path);
        Response response = handler != null
                ? handler.apply(request)
                : new Response(404, "application/json", "{\"error\":\"not found\",\"status_code\":404}");

        byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

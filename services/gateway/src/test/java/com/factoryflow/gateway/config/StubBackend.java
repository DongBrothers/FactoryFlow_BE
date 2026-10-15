package com.factoryflow.gateway.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * gateway 뒤 서비스 대신 요청을 받는 HTTP 서버 (JDK HttpServer). 마지막으로 받은 요청의 경로와 X-User-* 헤더를 기록하고 200 을 돌려준다.
 * gateway 가 라우트로 실제로 넘긴 것을 확인하려고 쓴다. 경로가 /error 로 끝나면 서비스 오류(409)를 돌려준다.
 */
final class StubBackend {

    static final String SERVICE_ERROR_BODY = "{\"code\":\"ORDER-001\",\"message\":\"서비스가 낸 오류\"}";

    private final HttpServer server;
    private volatile String lastPath;
    private volatile Map<String, String> lastUserHeaders;
    private volatile String lastTraceId;

    private StubBackend(HttpServer server) {
        this.server = server;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            StubBackend backend = new StubBackend(server);
            server.createContext("/", backend::handle);
            server.start();
            return backend;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void reset() {
        lastPath = null;
        lastUserHeaders = null;
        lastTraceId = null;
    }

    /** 마지막 요청 경로. 요청을 받지 않았으면 null. */
    String lastPath() {
        return lastPath;
    }

    /** 마지막 요청의 X-User-* 헤더 (이름 소문자). 요청을 받지 않았으면 null. */
    Map<String, String> lastUserHeaders() {
        return lastUserHeaders;
    }

    /** 마지막 요청의 X-Trace-Id. 요청을 받지 않았거나 헤더가 없으면 null. */
    String lastTraceId() {
        return lastTraceId;
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> userHeaders = new TreeMap<>();
        exchange.getRequestHeaders()
                .forEach(
                        (name, values) -> {
                            if (name.toLowerCase().startsWith("x-user-")) {
                                userHeaders.put(name.toLowerCase(), String.join(",", values));
                            }
                        });
        lastUserHeaders = userHeaders;
        lastTraceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
        lastPath = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (lastPath.endsWith("/error")) {
            sendServiceError(exchange);
            return;
        }
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    /** 경로가 /error 로 끝나면 서비스가 낸 오류처럼 409 와 서비스 오류 본문을 돌려준다. */
    private static void sendServiceError(HttpExchange exchange) throws IOException {
        byte[] body = SERVICE_ERROR_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(409, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}

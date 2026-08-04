package com.jujin.freeway.http.undertow;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestPipeline;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndertowHttpContractTest {

    private static RequestPipeline pipeline() {
        var routes = new RouteIndex(
            List.of(
                Route.get("/ping", ctx -> ctx.send(200, "pong")),
                Route.post("/echo", ctx -> {
                    ctx.status(200);
                    ctx.output(ctx.body());
                })),
            List.of());
        return new RequestPipeline(
            routes, new WebSocketIndex(List.of(), List.of()),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(), List.of(), List.of());
    }

    @Test
    void servesGetAndHead() throws Exception {
        var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
        var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
        var client = httpClient();

        try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
            server.start();
            var get = client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/ping"))
                    .GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, get.statusCode());
            assertEquals("pong", get.body());
            assertEquals(4, get.headers().firstValueAsLong("Content-Length").orElse(-1));

            var head = client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/ping"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.discarding());
            assertEquals(200, head.statusCode());
            // RFC 7231 §4.3.2: HEAD must report the same Content-Length as GET.
            assertEquals(4, head.headers().firstValueAsLong("Content-Length").orElse(-1));
        }
    }

    @Test
    void echoBodyWorksWhenDispatchedToWorker() throws Exception {
        var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
        var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
        var client = httpClient();

        try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
            server.start();
            var response = client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/echo"))
                    .POST(HttpRequest.BodyPublishers.ofString("hello-worker"))
                    .timeout(Duration.ofSeconds(10))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals("hello-worker",
                new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void sanitizesCorrelationIdForResponseHeaders() {
        assertEquals("abc-123", UndertowWebEngine.safeCorrelationId("abc-123"));
        assertTrue(UndertowWebEngine.safeCorrelationId("a\r\nInjected: yes")
            .matches("[0-9a-f]{32}"));
        assertTrue(UndertowWebEngine.safeCorrelationId("a\nb")
            .matches("[0-9a-f]{32}"));
        assertTrue(UndertowWebEngine.safeCorrelationId(null)
            .matches("[0-9a-f]{32}"));
        assertFalse(UndertowWebEngine.safeCorrelationId("a\r\nb")
            .contains("\r"));
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }
}

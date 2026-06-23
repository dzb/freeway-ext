package com.jujin.freeway.benchmarks;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.undertow.UndertowWebEngine;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pluggable HTTP server harness for black-box benchmarking.
 *
 * <p>Supports four engines ({@link Engine}) and four scenarios ({@link Scenario}).
 * Each scenario defines a logical request/response contract; the harness translates
 * it to the native API of the selected engine.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var h = ServerHarness.start(Engine.FREEWAY, Scenario.PING)) {
 *     // ... benchmark client hitting h.port() ...
 * }
 * }</pre>
 *
 * <p>Adding a new scenario: add a case to {@link #freewayRoutes(Scenario)},
 * {@link #bareHandler(Scenario)}, and {@link #undertowHandler(Scenario)}.
 */
public final class ServerHarness implements AutoCloseable {

    /** Supported HTTP server engines. */
    public enum Engine {
        FREEWAY("freeway"),
        JDK_NATIVE("jdk-native"),
        ROBAHO_NATIVE("robaho-native"),
        UNDERTOW_NATIVE("undertow-native"),
        UNDERTOW_ADAPTER("undertow-adapter");

        private final String label;
        Engine(String label) { this.label = label; }

        public String label() { return label; }

        /** Resolves a case-insensitive label to an Engine. */
        public static Engine fromString(String s) {
            for (var e : values()) {
                if (e.label.equalsIgnoreCase(s)) return e;
            }
            throw new IllegalArgumentException("Unknown engine: " + s
                + ". Supported: freeway, jdk-native, robaho-native, undertow-native, undertow-adapter");
        }
    }

    /** Predefined benchmark scenarios. Add new scenarios here. */
    public enum Scenario {
        /** GET /ping → 200 "pong" (text/plain). Raw throughput baseline. */
        PING,
        /** GET /api/resource → 200 JSON body. Tests JSON serialization. */
        JSON,
        /** POST /echo → 200 + request body echo. Tests body read + write. */
        ECHO_BODY,
        /** WebSocket /ws/echo → echo back each text frame. FREEWAY and UNDERTOW only. */
        WS_ECHO
    }

    private final AutoCloseable server;
    private final int port;

    private static final byte[] PONG_BYTES = "pong".getBytes(StandardCharsets.UTF_8);
    private static final String JSON_BODY = "{\"id\":1,\"name\":\"test\"}";

    private ServerHarness(AutoCloseable server, int port) {
        this.server = server;
        this.port = port;
    }

    /** The port the server is listening on. */
    public int port() { return port; }

    @Override
    public void close() throws Exception { server.close(); }

    // ---------------------------------------------------------------
    // Public entry point
    // ---------------------------------------------------------------

    /**
     * Starts a server for the given engine and scenario on an auto-assigned port.
     *
     * @param engine   the server engine
     * @param scenario the request/response scenario
     * @return a started ServerHarness (must be closed by caller)
     */
    public static ServerHarness start(Engine engine, Scenario scenario) throws Exception {
        if (scenario == Scenario.WS_ECHO) {
            if (engine != Engine.FREEWAY && engine != Engine.UNDERTOW_NATIVE) {
                throw new UnsupportedOperationException(
                    "WS_ECHO scenario not supported for " + engine.label());
            }
        }
        return switch (engine) {
            case FREEWAY -> freeway(scenario);
            case JDK_NATIVE -> bare("sun.net.httpserver.DefaultHttpServerProvider", scenario);
            case ROBAHO_NATIVE -> bare("robaho.net.httpserver.DefaultHttpServerProvider", scenario);
            case UNDERTOW_NATIVE -> undertow(scenario);
            case UNDERTOW_ADAPTER -> undertowAdapter(scenario);
        };
    }

    // ---------------------------------------------------------------
    // Engine: Freeway
    // ---------------------------------------------------------------

    private static ServerHarness freeway(Scenario scenario) throws Exception {
        var engine = new FreewayHttpEngine(new JsonCodecDefault(), new CoercerDefault());
        var config = new HttpServerConfig("127.0.0.1", 0, 128, Duration.ofSeconds(5));
        WebSocketIndex wsIndex;
        RouteIndex routeIndex;
        if (scenario == Scenario.WS_ECHO) {
            routeIndex = new RouteIndex(List.of(), List.of());
            wsIndex = freewayWebSocketRoutes();
        } else {
            routeIndex = freewayRoutes(scenario);
            wsIndex = new WebSocketIndex(List.of(), List.of());
        }
        var pipeline = new RequestPipeline(
            routeIndex, wsIndex,
            noopCors(),
            noopHealth(),
            List.<StaticResourceMount>of(),
            List.<HttpFilter>of(),
            List.<ExceptionMapper>of()
        );
        var srv = new WebServer(engine, config, event -> {}, pipeline);
        srv.start();
        return new ServerHarness(srv, srv.port());
    }

    /** Freeway + Undertow adapter — measures the adapter path vs built-in engine. */
    private static ServerHarness undertowAdapter(Scenario scenario) throws Exception {
        var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
        var config = new HttpServerConfig("127.0.0.1", 0, 128, Duration.ofSeconds(5));
        WebSocketIndex wsIndex;
        RouteIndex routeIndex;
        if (scenario == Scenario.WS_ECHO) {
            routeIndex = new RouteIndex(List.of(), List.of());
            wsIndex = freewayWebSocketRoutes();
        } else {
            routeIndex = freewayRoutes(scenario);
            wsIndex = new WebSocketIndex(List.of(), List.of());
        }
        var pipeline = new RequestPipeline(
            routeIndex, wsIndex,
            noopCors(),
            noopHealth(),
            List.<StaticResourceMount>of(),
            List.<HttpFilter>of(),
            List.<ExceptionMapper>of()
        );
        var srv = new WebServer(engine, config, event -> {}, pipeline);
        srv.start();
        return new ServerHarness(srv, srv.port());
    }

    /** Creates routes for a Freeway scenario. All filters are noop — no overhead. */
    private static RouteIndex freewayRoutes(Scenario scenario) {
        return switch (scenario) {
            case PING -> new RouteIndex(
                List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))), List.of());
            case JSON -> new RouteIndex(
                List.of(Route.get("/api/resource",
                    ctx -> ctx.sendJson(200, new JsonResponse(1, "test")))), List.of());
            case ECHO_BODY -> new RouteIndex(
                List.of(Route.post("/echo", ctx -> {
                    ctx.status(200);
                    ctx.output(ctx.body());
                })), List.of());
            case WS_ECHO -> new RouteIndex(List.of(), List.of());
        };
    }

    /** Creates WebSocket echo route for {@link Scenario#WS_ECHO}. */
    private static WebSocketIndex freewayWebSocketRoutes() {
        var group = WebSocketGroup.of("/ws",
            WebSocketRoute.of("/echo", session -> new WebSocketListener() {
                @Override
                public void onText(String text) throws Exception {
                    session.sendText(text);
                }
            })
        );
        return new WebSocketIndex(List.of(), List.of(group));
    }

    /** No-op CorsFilter: enabled=false, skips all CORS processing. */
    private static CorsFilter noopCors() {
        return new CorsFilter(false, null, null, null, null, null, false);
    }

    /** No-op HealthFilter: enabled=false, just delegates to next handler. */
    private static HealthFilter noopHealth() {
        return new HealthFilter(false, "/no-health", null);
    }

    /** Record used for JSON scenario responses. */
    private record JsonResponse(int id, String name) {}

    // ---------------------------------------------------------------
    // Engine: JDK HttpServer / Robaho (both share the bare HttpServer API)
    // ---------------------------------------------------------------

    private static ServerHarness bare(String providerClass, Scenario scenario) throws Exception {
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", providerClass);
        var server = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 128);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", bareHandler(scenario));
        server.start();
        return new ServerHarness(() -> server.stop(0), server.getAddress().getPort());
    }

    /**
     * Creates a bare HttpServer handler for the given scenario.
     *
     * <p>Keep-alive: relies on {@code Content-Length} being set via
     * {@code sendResponseHeaders(200, bodyLength)} and the response body
     * being fully written. The output stream {@code close()} signals exchange
     * completion. No explicit {@code exchange.close()} is called — the
     * underlying JDK {@code HttpServer} reuses the connection for HTTP/1.1
     * when the response length is known and fully delivered.
     */
    private static com.sun.net.httpserver.HttpHandler bareHandler(Scenario scenario) {
        return switch (scenario) {
            case PING -> exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 4);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(PONG_BYTES);
                }
            };
            case JSON -> exchange -> {
                byte[] body = JSON_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            };
            case ECHO_BODY -> exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            };
            case WS_ECHO -> throw new UnsupportedOperationException(
                "WS_ECHO not supported for bare/JDK engines. Use freeway or undertow-native.");
        };
    }

    // ---------------------------------------------------------------
    // Engine: Undertow native
    // ---------------------------------------------------------------

    private static ServerHarness undertow(Scenario scenario) throws Exception {
        var server = Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setHandler(undertowHandler(scenario))
            .build();
        server.start();
        int port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
        return new ServerHarness(server::stop, port);
    }

    /**
     * Creates an Undertow HttpHandler for the given scenario.
     *
     * <p>Keep-alive: Undertow's default connection policy is persistent for
     * HTTP/1.1. Setting {@code Content-Length} and delivering the full body
     * via {@code getResponseSender().send()} signals completion and allows
     * the server to reuse the connection. The PING/JSON handlers use the
     * non-blocking sender API; ECHO_BODY uses {@code startBlocking()} +
     * stream I/O for straightforward body echo.
     */
    private static HttpHandler undertowHandler(Scenario scenario) {
        if (scenario == Scenario.WS_ECHO) {
            return undertowWsEchoHandler();
        }
        return switch (scenario) {
            case PING -> exchange -> {
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "4");
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send("pong");
            };
            case JSON -> exchange -> {
                String body = JSON_BODY;
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(body.length()));
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(body);
            };
            case ECHO_BODY -> exchange -> {
                exchange.startBlocking();
                byte[] body = exchange.getInputStream().readAllBytes();
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(body.length));
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                exchange.getOutputStream().write(body);
            };
            default -> throw new UnsupportedOperationException("Not implemented: " + scenario);
        };
    }

    /** Undertow-native WebSocket echo handler for WS_ECHO. */
    private static HttpHandler undertowWsEchoHandler() {
        WebSocketConnectionCallback callback = (exchange, channel) -> {
            channel.getReceiveSetter().set(new io.undertow.websockets.core.AbstractReceiveListener() {
                @Override
                protected void onFullTextMessage(io.undertow.websockets.core.WebSocketChannel ch,
                                                  io.undertow.websockets.core.BufferedTextMessage msg) {
                    io.undertow.websockets.core.WebSockets.sendText(msg.getData(), ch, null);
                }
            });
            channel.resumeReceives();
        };
        return new WebSocketProtocolHandshakeHandler(callback);
    }
}

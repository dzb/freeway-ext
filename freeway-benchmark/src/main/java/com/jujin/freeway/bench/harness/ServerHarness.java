/*
 * Copyright 2026 dzb
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jujin.freeway.bench.harness;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.jetty.JettyWebEngine;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.undertow.UndertowWebEngine;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.sun.net.httpserver.HttpServer;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;

/**
 * Pluggable HTTP server harness for black-box benchmarking.
 *
 * <p>Supports four engines ({@link Engine}) and four scenarios ({@link Scenario}). Each scenario
 * defines a logical request/response contract; the harness translates it to the native API of the
 * selected engine.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (var h = ServerHarness.start(Engine.FREEWAY, Scenario.PING)) {
 *     // ... benchmark client hitting h.port() ...
 * }
 * }</pre>
 *
 * <p>Adding a new scenario: add the enum constant and its {@link ScenarioSpec} entry — the four
 * server implementations and the client's request pattern all read that one table.
 */
public final class ServerHarness implements AutoCloseable {

  /** Supported HTTP server engines. */
  public enum Engine {
    FREEWAY("freeway"),
    JDK_NATIVE("jdk-native"),
    ROBAHO_NATIVE("robaho-native"),
    UNDERTOW_NATIVE("undertow-native"),
    UNDERTOW_ADAPTER("undertow-adapter"),
    JETTY_ADAPTER("jetty-adapter"),
    JETTY_NATIVE("jetty-native");

    private final String label;

    Engine(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** Resolves a case-insensitive label to an Engine. */
    public static Engine fromString(String s) {
      for (var e : values()) {
        if (e.label.equalsIgnoreCase(s)) return e;
      }
      throw new IllegalArgumentException(
          "Unknown engine: "
              + s
              + ". Supported: freeway, jdk-native, robaho-native, undertow-native,"
              + " undertow-adapter, jetty-adapter, jetty-native");
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

  private ServerHarness(AutoCloseable server, int port) {
    this.server = server;
    this.port = port;
  }

  /** The port the server is listening on. */
  public int port() {
    return port;
  }

  @Override
  public void close() throws Exception {
    server.close();
  }

  // ---------------------------------------------------------------
  // Public entry point
  // ---------------------------------------------------------------

  /**
   * Starts a server for the given engine and scenario on an auto-assigned port.
   *
   * @param engine the server engine
   * @param scenario the request/response scenario
   * @return a started ServerHarness (must be closed by caller)
   */
  public static ServerHarness start(Engine engine, Scenario scenario) throws Exception {
    if (scenario == Scenario.WS_ECHO) {
      if (engine != Engine.FREEWAY
          && engine != Engine.UNDERTOW_NATIVE
          && engine != Engine.JETTY_NATIVE) {
        throw new UnsupportedOperationException(
            "WS_ECHO scenario not supported for " + engine.label());
      }
    }
    return switch (engine) {
      case FREEWAY -> freeway(scenario);
      case JDK_NATIVE, ROBAHO_NATIVE -> bare(engine, scenario);
      case UNDERTOW_NATIVE -> undertow(scenario);
      case UNDERTOW_ADAPTER -> undertowAdapter(scenario);
      case JETTY_ADAPTER -> jettyAdapter(scenario);
      case JETTY_NATIVE -> jetty(scenario);
    };
  }

  // ---------------------------------------------------------------
  // Engine: Freeway
  // ---------------------------------------------------------------

  private static ServerHarness freeway(Scenario scenario) throws Exception {
    return freewayWith(
        new FreewayHttpEngine(
                FreewayHttpEngine.Wiring.defaults(
                    new JsonCodecDefault(), new CoercerDefault())),
            scenario);
  }

  /** Freeway + Jetty adapter — measures the adapter path vs built-in engine. */
  private static ServerHarness jettyAdapter(Scenario scenario) throws Exception {
    return freewayWith(new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault()), scenario);
  }

  /** Freeway + Undertow adapter — measures the adapter path vs built-in engine. */
  private static ServerHarness undertowAdapter(Scenario scenario) throws Exception {
    return freewayWith(
        new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault()), scenario);
  }

  /**
   * The one assembly path behind the three Freeway-based engines: same config, same pipeline, same
   * routes — only the engine differs. Keeping it in one place is what makes the engines comparable
   * and stops a new scenario from having to be added three times.
   */
  private static ServerHarness freewayWith(HttpEngine engine, Scenario scenario) throws Exception {
    // WebServerBuilder — not the raw WebServer constructor — because it is the
    // standalone assembly production also uses: it installs the noop event sink
    // sentinel (so no per-request event is built for a server nobody observes)
    // and appends core's default error handler, which is what makes a 413/400
    // scenario the response a real application returns instead of an unmapped
    // failure. CORS and health stay disabled on purpose: they are per-request
    // work and a route the scenarios never touch, and every engine must measure
    // the same pipeline.
    var builder =
        WebServerBuilder.builder()
            .engine(engine)
            .config(
                HttpServerConfig.defaults()
                    .withPort(0)
                    .withBacklog(128)
                    .withShutdownGrace(Duration.ofSeconds(5)))
            .cors(disabledCors())
            .health(disabledHealth());
    for (var route : freewayRoutes(scenario)) {
      builder.route(route);
    }
    for (var group : freewayWebSocketGroups()) {
      builder.webSocketGroup(group);
    }
    var srv = builder.build();
    srv.start();
    return new ServerHarness(srv, srv.port());
  }

  /** Creates the routes for a Freeway scenario — the builder assembles them into an index. */
  /** Builds the Freeway routes for a scenario straight from its {@link ScenarioSpec}. */
  private static List<Route> freewayRoutes(Scenario scenario) {
    ScenarioSpec spec = ScenarioSpec.of(scenario);
    if (spec.webSocket()) return List.of();
    if (spec.echoBody()) {
      return List.of(
          Route.post(
              spec.path(),
              ctx -> {
                ctx.setStatus(200);
                ctx.output(ctx.body());
              }));
    }
    if (spec.json()) {
      // The JSON scenario measures the codec on the Freeway path; the bytes the
      // client expects are the spec's response body, pinned by ServerHarnessTest.
      return List.of(Route.get(spec.path(), ctx -> ctx.sendJson(200, new JsonResponse(1, "test"))));
    }
    String body = new String(spec.responseBody(), StandardCharsets.ISO_8859_1);
    return List.of(Route.get(spec.path(), ctx -> ctx.send(200, body)));
  }

  /** Creates the WebSocket echo route for {@link Scenario#WS_ECHO} (empty for HTTP scenarios). */
  private static List<WebSocketGroup> freewayWebSocketGroups() {
    return List.of(
        WebSocketGroup.of(
            "/ws",
            WebSocketRoute.of(
                "/echo",
                session ->
                    new WebSocketListener() {
                      @Override
                      public void onText(String text) throws Exception {
                        session.sendText(text);
                      }
                    })));
  }

  /**
   * CORS disabled on purpose: the scenarios send no {@code Origin} header, so a default-enabled
   * filter would be per-request work whose cost would land in every engine's numbers.
   */
  private static CorsFilter disabledCors() {
    return new CorsFilter(false, null, null, null, null, null, false);
  }

  /** Health disabled on purpose: no scenario probes it, and every engine must run one pipeline. */
  private static HealthFilter disabledHealth() {
    return new HealthFilter(false, "/no-health", null);
  }

  /** Record used for JSON scenario responses. */
  private record JsonResponse(int id, String name) {}

  // ---------------------------------------------------------------
  // Engine: JDK HttpServer / Robaho (both share the bare HttpServer API)
  // ---------------------------------------------------------------

  /**
   * The provider the JVM has already resolved. {@code com.sun.net.httpserver.HttpServer} caches it
   * in a static field after the first lookup, so a later request for a different provider is
   * silently ignored — the second engine would be measured with the first engine's code and
   * mislabeled. One provider per JVM, enforced loudly.
   */
  private static String installedProvider;

  private static synchronized ServerHarness bare(Engine engine, Scenario scenario)
      throws Exception {
    String providerClass =
        engine == Engine.JDK_NATIVE
            ? "sun.net.httpserver.DefaultHttpServerProvider"
            : "robaho.net.httpserver.DefaultHttpServerProvider";
    if (installedProvider == null) {
      System.setProperty("com.sun.net.httpserver.HttpServerProvider", providerClass);
      installedProvider = engine.label();
    } else if (!installedProvider.equals(engine.label())) {
      throw new IllegalStateException(
          "The JDK HttpServer provider is fixed once per JVM (already using '"
              + installedProvider
              + "'), so '"
              + engine.label()
              + "' would be measured with the wrong server. Run each bare engine in its own"
              + " invocation (one --engines value).");
    }
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", bareHandler(ScenarioSpec.of(scenario)));
    server.start();
    return new ServerHarness(() -> server.stop(0), server.getAddress().getPort());
  }

  /**
   * Creates a bare HttpServer handler for the given scenario.
   *
   * <p>Keep-alive: relies on {@code Content-Length} being set via {@code sendResponseHeaders(200,
   * bodyLength)} and the response body being fully written. The output stream {@code close()}
   * signals exchange completion. No explicit {@code exchange.close()} is called — the underlying
   * JDK {@code HttpServer} reuses the connection for HTTP/1.1 when the response length is known and
   * fully delivered.
   */
  private static com.sun.net.httpserver.HttpHandler bareHandler(ScenarioSpec spec) {
    return exchange -> {
      if (!spec.method().equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      byte[] body =
          spec.echoBody() ? exchange.getRequestBody().readAllBytes() : spec.responseBody();
      if (spec.contentType() != null) {
        exchange.getResponseHeaders().add("Content-Type", spec.contentType());
      }
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    };
  }

  // ---------------------------------------------------------------
  // Engine: Undertow native
  // ---------------------------------------------------------------

  private static ServerHarness undertow(Scenario scenario) throws Exception {
    var server =
        Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setHandler(undertowHandler(ScenarioSpec.of(scenario)))
            .build();
    server.start();
    int port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
    return new ServerHarness(server::stop, port);
  }

  /**
   * Creates an Undertow HttpHandler for the given scenario.
   *
   * <p>Keep-alive: Undertow's default connection policy is persistent for HTTP/1.1. Setting {@code
   * Content-Length} and delivering the full body via {@code getResponseSender().send()} signals
   * completion and allows the server to reuse the connection. The PING/JSON handlers use the
   * non-blocking sender API; ECHO_BODY uses {@code startBlocking()} + stream I/O for
   * straightforward body echo.
   */
  private static HttpHandler undertowHandler(ScenarioSpec spec) {
    if (spec.webSocket()) {
      return undertowWsEchoHandler();
    }
    HttpHandler handler =
        exchange -> {
          if (!spec.method().equals(exchange.getRequestMethod().toString())) {
            exchange.setStatusCode(405);
            exchange.endExchange();
            return;
          }
          if (spec.contentType() != null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, spec.contentType());
          }
          // Only the echo scenario reads a request body: an unconditional
          // startBlocking() + readAllBytes() on a GET waits for a body that never
          // arrives, which failed every request.
          if (spec.echoBody()) {
            exchange.startBlocking();
            byte[] body = exchange.getInputStream().readAllBytes();
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(body.length));
            exchange.getOutputStream().write(body);
          } else {
            byte[] body = spec.responseBody();
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(body.length));
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
          }
        };
    // The echo scenario reads the request body with blocking I/O, which Undertow
    // forbids on an I/O thread (UT000126) — it has to run on a worker. The
    // fixed-body scenarios keep the non-blocking sender, so they still measure
    // the raw I/O-thread path.
    return spec.echoBody() ? new BlockingHandler(handler) : handler;
  }

  /** Undertow-native WebSocket echo handler for WS_ECHO. */
  private static HttpHandler undertowWsEchoHandler() {
    WebSocketConnectionCallback callback =
        (exchange, channel) -> {
          channel
              .getReceiveSetter()
              .set(
                  new AbstractReceiveListener() {
                    @Override
                    protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                      WebSockets.sendText(msg.getData(), ch, null);
                    }
                  });
          channel.resumeReceives();
        };
    return new WebSocketProtocolHandshakeHandler(callback);
  }

  // ---------------------------------------------------------------
  // Engine: Jetty native
  // ---------------------------------------------------------------

  private static ServerHarness jetty(Scenario scenario) throws Exception {
    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    server.addConnector(connector);

    if (scenario == Scenario.WS_ECHO) {
      ServerWebSocketContainer wsContainer = ServerWebSocketContainer.ensure(server);
      wsContainer.addMapping(
          "/ws/echo",
          (WebSocketCreator)
              (upgradeRequest, upgradeResponse, wsCallback) -> {
                var listener = new JettyEchoListener();
                listener.setSessionCallback(wsCallback);
                return listener;
              });
    }
    server.setHandler(jettyHandler(ScenarioSpec.of(scenario)));
    server.start();
    int port = connector.getLocalPort();
    return new ServerHarness(() -> server.stop(), port);
  }

  private static Handler jettyHandler(ScenarioSpec spec) {
    if (spec.webSocket()) {
      return null; // handled by WebSocket container
    }
    return new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        if (!spec.method().equals(request.getMethod())) {
          response.setStatus(405);
          callback.succeeded();
          return true;
        }
        try {
          byte[] body =
              spec.echoBody() ? Request.asInputStream(request).readAllBytes() : spec.responseBody();
          response.setStatus(200);
          if (spec.contentType() != null) {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, spec.contentType());
          }
          response.getHeaders().put(HttpHeader.CONTENT_LENGTH, String.valueOf(body.length));
          response.write(true, ByteBuffer.wrap(body), callback);
        } catch (Exception e) {
          response.setStatus(500);
          callback.succeeded();
        }
        return true;
      }
    };
  }

  /** Minimal Jetty 12 WebSocket echo listener. */
  private static final class JettyEchoListener implements Session.Listener.AutoDemanding {

    private Session session;
    private Callback setSessionCallback;

    void setSessionCallback(Callback callback) {
      this.setSessionCallback = callback;
    }

    @Override
    public void onWebSocketOpen(Session session) {
      this.session = session;
      if (setSessionCallback != null) {
        setSessionCallback.succeeded();
      }
      session.demand();
    }

    @Override
    public void onWebSocketText(String message) {
      try {
        session.sendText(message, org.eclipse.jetty.websocket.api.Callback.NOOP);
        session.demand();
      } catch (Exception ignored) {
        // ignore send errors in benchmark
      }
    }

    @Override
    public void onWebSocketClose(
        int statusCode, String reason, org.eclipse.jetty.websocket.api.Callback callback) {
      callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
      // ignore errors in benchmark
    }
  }
}

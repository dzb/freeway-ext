package com.jujin.freeway.fleet.server;

import com.jujin.freeway.fleet.FleetKeys;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry surface describes itself: protocol version, the routes it serves and whether it
 * expects a token. {@code GET /fleet}, deliberately open — a client must be able to learn what it
 * is talking to <em>before</em> it has credentials, and a mismatch answered here is a readable
 * failure instead of a 404 on whichever route happened to move.
 *
 * <p>The protocol is the compatibility contract: this node accepts {@link FleetProtocol#PROTOCOL}
 * and every older value; a client that announces a newer one is refused with {@code 426}, never
 * guessed at. Adding a route or a header is therefore a protocol bump, not a silent change.
 */
final class FleetIndexHandler implements RouteHandler {

  private final SymbolSource symbols;

  FleetIndexHandler(SymbolSource symbols) {
    this.symbols = java.util.Objects.requireNonNull(symbols, "symbols");
  }

  @Override
  public void handle(HttpExchange ctx) throws IOException {
    Map<String, Object> routes = new LinkedHashMap<>();
    // Derived from the served table, so a new route cannot be missing from
    // its own self-description.
    for (FleetServerModule.Surface surface : FleetServerModule.SURFACE) {
      routes.put(surface.label(), surface.path());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("proto", FleetProtocol.PROTOCOL);
    body.put("routes", routes);
    body.put("tokenHeader", FleetProtocol.TOKEN_HEADER);
    body.put("protoHeader", FleetProtocol.PROTOCOL_HEADER);
    String current = symbols.resolve(FleetKeys.TOKEN, "");
    String previous = symbols.resolve(FleetKeys.TOKEN_PREVIOUS, "");
    body.put("tokenRequired", !current.isBlank() || !previous.isBlank());
    // Report the actual rotation posture, not a hardcoded one: a node with
    // no previous token is not accepting two secrets.
    List<String> rotation = new ArrayList<>(2);
    if (!current.isBlank()) rotation.add("current");
    if (!previous.isBlank()) rotation.add("previous");
    if (!rotation.isEmpty()) body.put("tokenRotation", rotation);
    ctx.setHeader(FleetProtocol.PROTOCOL_HEADER, String.valueOf(FleetProtocol.PROTOCOL));
    ctx.sendJson(HttpStatus.OK, body);
  }
}

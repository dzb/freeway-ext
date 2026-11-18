package com.jujin.freeway.fleet.server;

import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Protected read-only JSON surface used by the embedded dashboard. */
final class FleetAdminHandler implements RouteHandler {

  private final LeaseStore store;
  private final SymbolSource symbols;
  private final FleetServerOptions options;

  FleetAdminHandler(LeaseStore store, SymbolSource symbols, FleetServerOptions options) {
    this.store = Objects.requireNonNull(store, "store");
    this.symbols = Objects.requireNonNull(symbols, "symbols");
    this.options = Objects.requireNonNull(options, "options");
  }

  @Override
  public void handle(HttpExchange ctx) throws IOException {
    if (!FleetAuth.authorized(ctx, symbols)) {
      ctx.sendText(HttpStatus.UNAUTHORIZED, "");
      return;
    }
    if (!"GET".equalsIgnoreCase(ctx.method())) {
      ctx.sendText(HttpStatus.METHOD_NOT_ALLOWED, "");
      return;
    }
    if (ctx.path().endsWith("/overview")) {
      overview(ctx);
    } else {
      instances(ctx);
    }
  }

  private void overview(HttpExchange ctx) throws IOException {
    List<LeaseStore.Lease> leases = store.leases();
    long ready =
        leases.stream().filter(lease -> lease.health().live() && lease.health().ready()).count();
    boolean storeOk;
    try {
      store.ping();
      storeOk = true;
    } catch (RuntimeException failed) {
      storeOk = false;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("protocol", FleetProtocol.PROTOCOL);
    body.put("store", storeOk ? "ok" : "unavailable");
    body.put("leaseTtl", options.leaseTtl().toString());
    body.put("evictionInterval", options.evictionInterval().toString());
    body.put("instances", leases.size());
    body.put("ready", ready);
    ctx.sendJson(HttpStatus.OK, body);
  }

  private void instances(HttpExchange ctx) throws IOException {
    ctx.sendJson(HttpStatus.OK, store.leases());
  }
}

package com.jujin.freeway.fleet.server;

import com.jujin.freeway.commons.util.ConstantTime;
import com.jujin.freeway.fleet.FleetKeys;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

/** Shared token check for the wire API and the protected operations API. */
final class FleetAuth {

  private static final SymbolSpec<String> TOKEN = SymbolSpec.of(FleetKeys.TOKEN, String.class, "");
  private static final SymbolSpec<String> TOKEN_PREVIOUS =
      SymbolSpec.of(FleetKeys.TOKEN_PREVIOUS, String.class, "");

  private FleetAuth() {}

  static boolean authorized(HttpExchange ctx, SymbolSource symbols) {
    String current = symbols.resolve(TOKEN);
    String previous = symbols.resolve(TOKEN_PREVIOUS);
    if (current.isBlank() && previous.isBlank()) {
      return true;
    }
    String presented = ctx.header(FleetProtocol.TOKEN_HEADER).orElse("");
    return (!current.isBlank() && ConstantTime.isEqual(current, presented))
        || (!previous.isBlank() && ConstantTime.isEqual(previous, presented));
  }
}

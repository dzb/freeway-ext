package com.jujin.freeway.cloud.fleet;

import com.jujin.freeway.fleet.FleetKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The registry fleet this backend talks to, as one value with named knobs — the same
 * shape-for-a-seven-position-constructor reasoning as every adapter wiring record.
 *
 * <p>The node list is the backend: writes fan out to every node, reads union their answers. That is
 * this backend's availability model, chosen instead of a replication protocol — a registry holds
 * rebuildable state (every instance re-registers), so N independent nodes with a client that talks
 * to all of them survive any one node dying without the nodes knowing about each other.
 */
record FleetNodes(List<URI> addresses, String token, Duration timeout, int minSuccesses) {

  private static final SymbolSpec<String> NODES =
      SymbolSpec.of(FleetModule.ConfigKeys.NODES, String.class, "");
  private static final SymbolSpec<String> TOKEN = SymbolSpec.of(FleetKeys.TOKEN, String.class, "");
  private static final SymbolSpec<Long> TIMEOUT_MS =
      SymbolSpec.of(
          FleetModule.ConfigKeys.TIMEOUT_MS, Long.class, FleetModule.ConfigKeys.TIMEOUT_MS_DEFAULT);
  private static final SymbolSpec<Integer> MIN_SUCCESSES =
      SymbolSpec.of(FleetModule.ConfigKeys.FANOUT_MIN_SUCCESSES, Integer.class, 1);

  private static final Duration DEFAULT_TIMEOUT =
      Duration.ofMillis(FleetModule.ConfigKeys.TIMEOUT_MS_DEFAULT);

  FleetNodes {
    addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
    if (addresses.isEmpty()) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.NODES + " is empty — the backend has no registry to talk to");
    }
    for (URI address : addresses) {
      validateNode(address);
    }
    if (minSuccesses < 1 || minSuccesses > addresses.size()) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.FANOUT_MIN_SUCCESSES
              + " must be between 1 and the number of nodes ("
              + addresses.size()
              + "): "
              + minSuccesses);
    }
    token = token == null ? "" : token;
    timeout =
        (timeout == null || timeout.isZero() || timeout.isNegative()) ? DEFAULT_TIMEOUT : timeout;
  }

  FleetNodes(List<URI> addresses, String token, Duration timeout) {
    this(addresses, token, timeout, 1);
  }

  private static void validateNode(URI node) {
    if (node == null) {
      throw new IllegalArgumentException(FleetModule.ConfigKeys.NODES + " contains a null node");
    }
    if (!"http".equals(node.getScheme()) && !"https".equals(node.getScheme())) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.NODES + " entry must be http(s): " + node);
    }
    if (node.getHost() == null || node.getHost().isBlank()) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.NODES + " entry must name a host: " + node);
    }
    if (node.getPort() < -1 || node.getPort() > 65535) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.NODES + " entry has an invalid port: " + node);
    }
    if (node.getUserInfo() != null || node.getRawQuery() != null || node.getRawFragment() != null) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.NODES
              + " entry must not contain userinfo, query or fragment: "
              + node);
    }
    String path = node.getRawPath();
    if (path != null && !path.isEmpty() && !"/".equals(path)) {
      throw new IllegalArgumentException(
          FleetModule.ConfigKeys.NODES + " entry must not contain a path: " + node);
    }
  }

  /** Resolves and parses the configured fleet; blank or unparseable fails here. */
  static FleetNodes from(SymbolSource symbols) {
    String raw = symbols.resolve(NODES).trim();
    if (raw.isEmpty()) {
      throw new IllegalStateException(
          FleetModule.ConfigKeys.NODES
              + " is blank — the backend is bound but has no registry node to talk to;"
              + " list at least one node (host:port or http(s)://host:port)");
    }
    List<URI> addresses = new ArrayList<>();
    for (String entry : raw.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      URI uri;
      try {
        uri = URI.create(trimmed.contains("://") ? trimmed : "http://" + trimmed);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalStateException(
            FleetModule.ConfigKeys.NODES + " entry is not a valid URI: " + trimmed, invalid);
      }
      addresses.add(uri);
    }
    if (addresses.isEmpty()) {
      throw new IllegalStateException(
          FleetModule.ConfigKeys.NODES + " is blank — no node was parsed");
    }
    return new FleetNodes(
        addresses,
        symbols.resolve(TOKEN),
        Duration.ofMillis(symbols.resolve(TIMEOUT_MS)),
        symbols.resolve(MIN_SUCCESSES));
  }
}

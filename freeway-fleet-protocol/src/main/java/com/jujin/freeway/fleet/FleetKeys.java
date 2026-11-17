package com.jujin.freeway.fleet;

import java.time.Duration;

/**
 * Shared Fleet configuration keys. The protocol module owns them because both the client and the
 * server read the same token and namespace; the client-only nodes/timeout/cache keys live here too
 * so the server does not depend on the client artifact.
 */
public final class FleetKeys {
  private FleetKeys() {}

  public static final String PREFIX = "freeway.fleet";

  public static final String NODES = "freeway.fleet.nodes";
  public static final String TOKEN = "freeway.fleet.token";
  public static final String TOKEN_PREVIOUS = "freeway.fleet.token-previous";
  public static final String TIMEOUT_MS = "freeway.fleet.timeout-ms";
  public static final long TIMEOUT_MS_DEFAULT = 3000;
  public static final String CACHE_TTL = "freeway.fleet.cache-ttl";
  public static final Duration CACHE_TTL_DEFAULT = Duration.ofSeconds(5);
  public static final String CACHE_TTL_PREFIX = "freeway.fleet.cache-ttl.";
}

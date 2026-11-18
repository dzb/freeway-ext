package com.jujin.freeway.fleet;

/**
 * Fleet keys shared by the client and server wire surface.
 *
 * <p>Client-only settings such as node addresses, timeout and discovery cache TTL live with the
 * client module in {@code FleetModule.ConfigKeys}. Server-only storage keys live with the server in
 * {@code FleetServerModule.ConfigKeys}. The protocol module owns only the namespace and the shared
 * credential keys both sides read.
 */
public final class FleetKeys {
  private FleetKeys() {}

  public static final String PREFIX = "freeway.fleet";

  public static final String TOKEN = "freeway.fleet.token";
  public static final String TOKEN_PREVIOUS = "freeway.fleet.token-previous";
}

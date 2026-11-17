package com.jujin.freeway.cloud.consul;

import java.time.Duration;
import java.util.Objects;

/**
 * The adapter's inputs as one value with named knobs, with the core optional-input shape: {@link
 * #defaults()} plus per-field {@code withX} withers, so a call site states only what it changes and
 * no second copy of the defaults exists.
 *
 * @param agentHost Consul agent host
 * @param agentPort Consul agent HTTP port
 * @param scheme {@code http} or {@code https}; private CAs are handled by the JVM truststore, not
 *     by an adapter key
 * @param token ACL token sent as {@code X-Consul-Token}; blank omits the header (a Consul without
 *     ACLs needs none)
 * @param ttl health-check TTL; must exceed the core's renew interval (10s) or the instance flaps
 * @param drainWindow how long Consul needs after a deregister for consumers to stop routing here —
 *     answered to {@code freeway.cloud.member.shutdown-drain=auto}
 */
record ConsulOptions(
    String agentHost,
    int agentPort,
    String scheme,
    String token,
    Duration ttl,
    Duration drainWindow) {

  public ConsulOptions {
    if (agentHost == null || agentHost.isBlank()) {
      throw new IllegalArgumentException("agentHost must not be blank");
    }
    if (agentPort < 1 || agentPort > 65535) {
      throw new IllegalArgumentException("agentPort out of range: " + agentPort);
    }
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("scheme must be http or https: " + scheme);
    }
    token = token == null ? "" : token;
    Objects.requireNonNull(ttl, "ttl");
    Objects.requireNonNull(drainWindow, "drainWindow");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive: " + ttl);
    }
    if (drainWindow.isNegative()) {
      throw new IllegalArgumentException("drainWindow must not be negative: " + drainWindow);
    }
  }

  /** The values every {@code freeway.cloud.consul.*} key defaults to. */
  public static ConsulOptions defaults() {
    return new ConsulOptions(
        "127.0.0.1", 8500, "http", "", Duration.ofSeconds(15), Duration.ofSeconds(5));
  }

  public ConsulOptions withAgentHost(String value) {
    return new ConsulOptions(value, agentPort, scheme, token, ttl, drainWindow);
  }

  public ConsulOptions withAgentPort(int value) {
    return new ConsulOptions(agentHost, value, scheme, token, ttl, drainWindow);
  }

  public ConsulOptions withScheme(String value) {
    return new ConsulOptions(agentHost, agentPort, value, token, ttl, drainWindow);
  }

  public ConsulOptions withToken(String value) {
    return new ConsulOptions(agentHost, agentPort, scheme, value, ttl, drainWindow);
  }

  public ConsulOptions withTtl(Duration value) {
    return new ConsulOptions(agentHost, agentPort, scheme, token, value, drainWindow);
  }

  public ConsulOptions withDrainWindow(Duration value) {
    return new ConsulOptions(agentHost, agentPort, scheme, token, ttl, value);
  }

  /** True when an ACL token is configured (the header is then sent). */
  public boolean hasToken() {
    return !token.isBlank();
  }

  /** The agent's base URI, no trailing slash. */
  public String baseUri() {
    return scheme + "://" + agentHost + ":" + agentPort;
  }
}

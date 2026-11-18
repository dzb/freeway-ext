package com.jujin.freeway.cloud.fleet;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.discovery.CachingDiscovery;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.LoadBalancerDefault;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.fleet.FleetKeys;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.OptionalBinding;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The freeway-native registry backend: binds a fleet of registry nodes as the process's {@code
 * ServiceRegistry}/{@code ServiceDiscovery}. The nodes themselves are freeway applications — {@code
 * HttpModule + FleetServerModule} (roughly ten lines of assembly; see that module's javadoc) —
 * which makes the backend deployable without any third-party system and keeps client and wire
 * format on the same release train as the server.
 *
 * <p>Writes fan out to every configured node and reads union the answers, so the nodes are
 * independent peers: no replication protocol, and no node is special. One node dying degrades the
 * fleet; the surviving nodes keep serving, and the dead one is repopulated by the next heartbeat of
 * every instance (a renew that answers 404 re-asserts the instance on the spot).
 *
 * <p>Place this module alongside {@code CloudModule} and {@code MemberModule}, and name the fleet
 * with {@code freeway.fleet.nodes}; a token shared with the nodes goes in {@code
 * freeway.fleet.token}. It is one of the alternatives for the same seam — there is no built-in
 * in-process production backend; static discovery is the zero-infrastructure choice, and Consul
 * ships as an ext adapter. Switching is swapping the module and its keys, never application code.
 */
public final class FleetModule implements ModuleEx {

  private static final Logger LOG = LoggerFactory.getLogger(FleetModule.class);

  private static final SymbolSpec<Duration> CACHE_TTL =
      SymbolSpec.of(ConfigKeys.CACHE_TTL, Duration.class, ConfigKeys.CACHE_TTL_DEFAULT);

  @Override
  public void bind(Binder b) {
    b.bind(FleetNodes.class).to(FleetModule::nodes);
    b.bind(FleetClient.class)
        .to(
            container ->
                new FleetClient(container.get(FleetNodes.class), container.get(Metrics.class)));
    b.bind(ServiceRegistry.class)
        .to(container -> new FleetRegistry(container.get(FleetClient.class)))
        .primary();
    b.bind(ServiceDiscovery.class)
        .to(
            container ->
                CachingDiscovery.around(
                    new FleetDiscovery(container.get(FleetClient.class)),
                    cacheTtl(container),
                    OptionalBinding.get(
                        container, com.jujin.freeway.commons.metrics.Metrics.class)))
        .primary();
    // A caller-supplied policy must replace this fallback without an
    // ambiguity error; the Local marker identifies the built-in default.
    b.bind(LoadBalancer.class)
        .to(
            (Container container) ->
                new LoadBalancerDefault(
                    container
                        .get(SymbolSource.class)
                        .resolve(
                            SymbolSpec.of(LoadBalancer.ConfigKeys.LOCAL_ZONE, String.class, ""))))
        .marker(Local.class);

    // Declared vocabulary: a key outside this backend's namespace fails the
    // bind instead of sitting outside the unknown-key check. The per-service
    // TTL family is dynamic by design, so it is admitted as a prefix — the
    // same shape boot uses for its per-file log keys.
    b.contribute(KnownKeys.class)
        // Static keys plus the dynamic per-service TTL family.
        .add(KnownKeys.of(FleetKeys.class, FleetKeys.PREFIX))
        .add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX))
        .add(KnownKeys.of(LoadBalancer.ConfigKeys.class, CloudKeys.PREFIX))
        .add(KnownKeys.admit(ConfigKeys.CACHE_TTL_PREFIX));
  }

  /**
   * Freshness policy: the global {@code cache-ttl} with optional per-service overrides. Resolution
   * is lazy and memoized — a service id is looked up the first time it is discovered, not on every
   * refresh, and an absent override inherits the global value (configuring one is never required).
   */
  private static CachingDiscovery.TtlPolicy cacheTtl(Container container) {
    SymbolSource symbols = container.get(SymbolSource.class);
    Duration fallback = symbols.resolve(CACHE_TTL);
    ConcurrentHashMap<String, Duration> resolved = new ConcurrentHashMap<>();
    return serviceId -> {
      Duration own =
          resolved.computeIfAbsent(serviceId, id -> resolveOverride(symbols, id, fallback));
      return own;
    };
  }

  /**
   * One service's override; a malformed value warns once and inherits the global default — a typo
   * must not take remote calls down.
   */
  private static Duration resolveOverride(
      SymbolSource symbols, String serviceId, Duration fallback) {
    String key = ConfigKeys.CACHE_TTL_PREFIX + serviceId;
    try {
      Duration own = symbols.resolve(SymbolSpec.of(key, Duration.class, null));
      return own == null ? fallback : own;
    } catch (RuntimeException invalid) {
      LOG.warn("Ignoring {} — using the global cache TTL: {}", key, invalid.getMessage());
      return fallback;
    }
  }

  private static FleetNodes nodes(Container container) {
    return FleetNodes.from(container.get(SymbolSource.class));
  }

  /** Client-only Fleet settings. Shared token keys remain in {@link FleetKeys}. */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    public static final String PREFIX = FleetKeys.PREFIX;

    /** Comma-separated Fleet node addresses. */
    public static final String NODES = "freeway.fleet.nodes";

    /** Per-node connect/request timeout in milliseconds. */
    public static final String TIMEOUT_MS = "freeway.fleet.timeout-ms";

    public static final long TIMEOUT_MS_DEFAULT = 3000;

    /** Minimum number of successful nodes for a write. */
    public static final String FANOUT_MIN_SUCCESSES = "freeway.fleet.fanout-min-successes";

    /** Discovery cache TTL. */
    public static final String CACHE_TTL = "freeway.fleet.cache-ttl";

    public static final Duration CACHE_TTL_DEFAULT = Duration.ofSeconds(5);

    /** Dynamic per-service cache TTL family prefix. */
    public static final String CACHE_TTL_PREFIX = "freeway.fleet.cache-ttl.";
  }
}

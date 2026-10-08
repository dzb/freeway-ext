package com.jujin.freeway.cloud.consul;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.time.Duration;

/**
 * The Consul adapter module: binds the two discovery interfaces <em>primary</em>, replacing the
 * core's {@code @Local} in-process defaults.
 *
 * <p>Nothing else is re-implemented. The core's {@code RegistryLifecycleHook} registers on start,
 * renews, re-registers on a lost lease and unregisters on stop; its {@code
 * RegistryHealthContributor} deactivates itself because the active binding is no longer
 * {@code @Local}; {@code drainWindow()} here feeds {@code
 * freeway.cloud.registry.shutdown-drain=auto}. All of that follows from the interface, so it is
 * driven by the core, not copied.
 *
 * <p>Enable by placing the module; the {@code freeway.cloud.registry.type} / {@code
 * freeway.cloud.discovery.type} keys are what {@code BackendTypeGuard} reads to warn when an
 * external type is configured without an adapter — once this module is placed the warning is
 * suppressed because the setting is honored.
 */
public final class ConsulModule implements ModuleEx {

  @Override
  public void bind(Binder b) {
    b.bind(ConsulWiring.class).to(ConsulModule::wiring);
    b.bind(ConsulClient.class).to(container -> new ConsulClient(container.get(ConsulWiring.class)));
    b.bind(ServiceRegistry.class)
        .to(
            container ->
                new ConsulServiceRegistry(
                    container.get(ConsulClient.class), container.get(ConsulWiring.class)))
        .primary();
    b.bind(ServiceDiscovery.class)
        .to(container -> new ConsulServiceDiscovery(container.get(ConsulClient.class)))
        .primary();

    // Declared vocabulary: a Consul key outside the declared prefix fails the
    // bind instead of sitting outside the unknown-key check.
    b.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
  }

  private static ConsulWiring wiring(Container container) {
    SymbolSource symbols = container.get(SymbolSource.class);
    return new ConsulWiring(
        symbols.resolve(AGENT_HOST),
        symbols.resolve(AGENT_PORT),
        symbols.resolve(SCHEME),
        symbols.resolve(TOKEN),
        symbols.resolve(TTL),
        symbols.resolve(DRAIN_WINDOW));
  }

  private static final SymbolSpec<String> AGENT_HOST =
      SymbolSpec.of(ConfigKeys.AGENT_HOST, String.class, "127.0.0.1");
  private static final SymbolSpec<Integer> AGENT_PORT =
      SymbolSpec.of(ConfigKeys.AGENT_PORT, Integer.class, 8500);
  private static final SymbolSpec<String> SCHEME =
      SymbolSpec.of(ConfigKeys.SCHEME, String.class, "http");
  private static final SymbolSpec<String> TOKEN = SymbolSpec.of(ConfigKeys.TOKEN, String.class, "");
  private static final SymbolSpec<Duration> TTL =
      SymbolSpec.of(ConfigKeys.TTL, Duration.class, Duration.ofSeconds(15));
  private static final SymbolSpec<Duration> DRAIN_WINDOW =
      SymbolSpec.of(ConfigKeys.DRAIN_WINDOW, Duration.class, Duration.ofSeconds(5));

  /**
   * Every key this module reads, as full literals — {@code PREFIX} fences the namespace, the
   * entries are the keys. A {@code freeway.cloud.consul.*} key outside this table fails the bind.
   */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    public static final String PREFIX = "freeway.cloud.consul";

    /** Consul agent host. */
    public static final String AGENT_HOST = "freeway.cloud.consul.agent-host";

    /** Consul agent HTTP port. */
    public static final String AGENT_PORT = "freeway.cloud.consul.agent-port";

    /** {@code http} or {@code https}. */
    public static final String SCHEME = "freeway.cloud.consul.scheme";

    /** ACL token; blank sends no {@code X-Consul-Token} header. */
    public static final String TOKEN = "freeway.cloud.consul.token";

    /** Health-check TTL (ISO-8601); must exceed the core's 10s renew interval. */
    public static final String TTL = "freeway.cloud.consul.ttl";

    /** Deregistration propagation window (ISO-8601) for the shutdown drain. */
    public static final String DRAIN_WINDOW = "freeway.cloud.consul.drain-window";
  }
}

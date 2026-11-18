package com.jujin.freeway.cloud.k8s;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.time.Duration;

/**
 * Kubernetes discovery adapter: reads ready pod endpoints from EndpointSlice v1 and binds {@link
 * ServiceDiscovery} primary.
 *
 * <p>Kubernetes owns service membership. This module deliberately does not bind {@code
 * ServiceRegistry}: there is no application-side register, renew, unregister or drain on this
 * plane. A no-op registry would make the member lifecycle claim a fact Kubernetes owns.
 *
 * <p>The module can be placed beside {@code CloudModule} without {@code MemberModule}. In-cluster
 * service-account configuration is automatic; {@code freeway.cloud.k8s.kubeconfig} is for local
 * development. The Kubernetes client types stay package-private in this adapter.
 */
public final class K8sModule implements ModuleEx {

  @Override
  public void bind(Binder b) {
    b.bind(K8sOptions.class).to(K8sModule::options);
    b.bind(ServiceDiscovery.class)
        .to(container -> new K8sDiscovery(container.get(K8sOptions.class)))
        .primary();
    b.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
  }

  private static K8sOptions options(Container container) {
    SymbolSource symbols = container.get(SymbolSource.class);
    return new K8sOptions(
        symbols.resolve(NAMESPACE),
        symbols.resolve(PORT_NAME),
        symbols.resolve(SCHEME),
        symbols.resolve(BASE_PATH),
        symbols.resolve(KUBECONFIG),
        symbols.resolve(RESYNC_PERIOD));
  }

  private static final SymbolSpec<String> NAMESPACE =
      SymbolSpec.of(ConfigKeys.NAMESPACE, String.class, "");
  private static final SymbolSpec<String> PORT_NAME =
      SymbolSpec.of(ConfigKeys.PORT_NAME, String.class, "");
  private static final SymbolSpec<String> SCHEME =
      SymbolSpec.of(ConfigKeys.SCHEME, String.class, "http");
  private static final SymbolSpec<String> BASE_PATH =
      SymbolSpec.of(ConfigKeys.BASE_PATH, String.class, "");
  private static final SymbolSpec<String> KUBECONFIG =
      SymbolSpec.of(ConfigKeys.KUBECONFIG, String.class, "");
  private static final SymbolSpec<Duration> RESYNC_PERIOD =
      SymbolSpec.of(ConfigKeys.RESYNC_PERIOD, Duration.class, Duration.ofSeconds(30));

  /**
   * Every key this module reads, as full literals. The prefix fences the namespace; the entries are
   * the exact keys.
   */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    public static final String PREFIX = "freeway.cloud.k8s";

    /** Namespace to watch; blank uses the client's in-cluster/kubeconfig namespace. */
    public static final String NAMESPACE = "freeway.cloud.k8s.namespace";

    /** EndpointSlice port name; blank accepts a single TCP port and rejects ambiguity. */
    public static final String PORT_NAME = "freeway.cloud.k8s.port-name";

    /** Scheme published on every ServiceEndpoint. */
    public static final String SCHEME = "freeway.cloud.k8s.scheme";

    /** Optional base path published on every ServiceEndpoint. */
    public static final String BASE_PATH = "freeway.cloud.k8s.base-path";

    /** Optional kubeconfig path for local development; blank uses SDK auto-configuration. */
    public static final String KUBECONFIG = "freeway.cloud.k8s.kubeconfig";

    /** Informer resync period; zero disables resync. */
    public static final String RESYNC_PERIOD = "freeway.cloud.k8s.resync-period";
  }
}

package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.fleet.FleetCodec;
import com.jujin.freeway.fleet.FleetKeys;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.time.Duration;
import java.util.List;

/**
 * The registry server as a Freeway module. The executable distribution wraps this module with
 * {@code FleetServerMain}, {@code HttpModule}, health, metrics and an embedded dashboard.
 *
 * <p>This is the control plane, not an application runtime: it owns the registry surface and its
 * store, and it never registers itself into the fleet it serves. The module binds a {@link
 * LeaseStore}; the application chooses the deployment's node discovery separately (static seed
 * list, an external load balancer, or the database the store will grow into).
 *
 * <p>The executable application has an optional shared token and a read-only dashboard protected by
 * it, but no built-in TLS, per-user RBAC or HA coordination. Shared durable leases remain a
 * separate deployment concern; the embedded H2 store is intentionally single-node.
 */
public final class FleetServerModule implements ModuleEx {

  /** Server-only storage keys. The client never reads these. */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    public static final String PREFIX = "freeway.fleet.db";

    public static final String URL = "freeway.fleet.db.url";
    public static final String URL_DEFAULT = "jdbc:h2:file:./fleet;DB_CLOSE_ON_EXIT=FALSE";
    public static final String USER = "freeway.fleet.db.user";
    public static final String USER_DEFAULT = "sa";
    public static final String PASSWORD = "freeway.fleet.db.password";
  }

  /** Runtime keys owned by the Fleet Server, outside the database namespace. */
  public static final class RuntimeKeys {
    private RuntimeKeys() {}

    public static final String PREFIX = FleetKeys.PREFIX;

    /** Lease eviction horizon; must exceed the member heartbeat interval. */
    public static final String LEASE_TTL = "freeway.fleet.lease-ttl";

    public static final Duration LEASE_TTL_DEFAULT = Duration.ofSeconds(30);

    /** Periodic cleanup cadence for expired leases. */
    public static final String EVICTION_INTERVAL = "freeway.fleet.eviction-interval";

    public static final Duration EVICTION_INTERVAL_DEFAULT = Duration.ofSeconds(30);
  }

  private final LeaseStore suppliedStore;

  /** Production shape: embedded H2 file store, configured by {@code freeway.fleet.db.*}. */
  public FleetServerModule() {
    this(null);
  }

  /** Test/embedding shape: bind a caller-owned store instead of H2. */
  public FleetServerModule(LeaseStore store) {
    this.suppliedStore = store;
  }

  /** The surface as data: bind contributes routes, the index describes them. */
  static final List<Surface> SURFACE =
      List.of(
          new Surface(
              "register", "freeway.fleet.register", "POST", FleetProtocol.REGISTER, FleetApi.class),
          new Surface("renew", "freeway.fleet.renew", "POST", FleetProtocol.RENEW, FleetApi.class),
          new Surface(
              "unregister",
              "freeway.fleet.unregister",
              "DELETE",
              FleetProtocol.UNREGISTER,
              FleetApi.class),
          new Surface(
              "discover", "freeway.fleet.discover", "GET", FleetProtocol.DISCOVER, FleetApi.class),
          new Surface("ready", "freeway.fleet.ready", "POST", FleetProtocol.READY, FleetApi.class),
          new Surface(
              "index",
              "freeway.fleet.index",
              "GET",
              FleetProtocol.SELF_DESCRIPTION,
              FleetIndexHandler.class),
          new Surface(
              "dashboard",
              "freeway.fleet.dashboard",
              "GET",
              FleetProtocol.DASHBOARD,
              FleetDashboardHandler.class),
          new Surface(
              "admin-overview",
              "freeway.fleet.admin.overview",
              "GET",
              FleetProtocol.ADMIN_OVERVIEW,
              FleetAdminHandler.class),
          new Surface(
              "admin-instances",
              "freeway.fleet.admin.instances",
              "GET",
              FleetProtocol.ADMIN_INSTANCES,
              FleetAdminHandler.class));

  /** One served route: the index label, the contribution id, and how to route it. */
  record Surface(
      String label, String id, String method, String path, Class<? extends RouteHandler> handler) {}

  @Override
  public void bind(Binder b) {
    b.contribute(KnownKeys.class)
        .add(KnownKeys.of(FleetKeys.class, FleetKeys.PREFIX))
        .add(KnownKeys.of(RuntimeKeys.class, RuntimeKeys.PREFIX))
        .add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
    b.bind(LeaseStore.class)
        .to(
            container -> {
              if (suppliedStore != null) {
                return suppliedStore;
              }
              SymbolSource symbols = container.get(SymbolSource.class);
              return new H2LeaseStore(
                  symbols.resolve(
                      SymbolSpec.of(ConfigKeys.URL, String.class, ConfigKeys.URL_DEFAULT)),
                  symbols.resolve(
                      SymbolSpec.of(ConfigKeys.USER, String.class, ConfigKeys.USER_DEFAULT)),
                  symbols.resolve(SymbolSpec.of(ConfigKeys.PASSWORD, String.class, "")),
                  FleetCodec.JSON);
            });
    b.bind(FleetServerOptions.class)
        .to(
            container -> {
              SymbolSource symbols = container.get(SymbolSource.class);
              return new FleetServerOptions(
                  symbols.resolve(
                      SymbolSpec.of(
                          RuntimeKeys.LEASE_TTL, Duration.class, RuntimeKeys.LEASE_TTL_DEFAULT)),
                  symbols.resolve(
                      SymbolSpec.of(
                          RuntimeKeys.EVICTION_INTERVAL,
                          Duration.class,
                          RuntimeKeys.EVICTION_INTERVAL_DEFAULT)));
            });
    b.contribute(CloudHealthContributor.class)
        .addDeferred(
            "freeway.fleet.server.health",
            container -> new FleetServerHealth(container.get(LeaseStore.class)));
    b.contribute(RuntimeHook.class)
        .addDeferred(
            "freeway.fleet.server.maintenance",
            container ->
                new FleetMaintenanceHook(
                    container.get(LeaseStore.class),
                    container.get(FleetServerOptions.class),
                    container.get(Metrics.class)));
    for (Surface surface : SURFACE) {
      b.contribute(Route.class).add(surface.id(), route(surface));
    }
  }

  private static Route route(Surface surface) {
    return switch (surface.method()) {
      case "POST" -> Route.post(surface.path(), surface.handler());
      case "DELETE" -> Route.delete(surface.path(), surface.handler());
      default -> Route.get(surface.path(), surface.handler());
    };
  }
}

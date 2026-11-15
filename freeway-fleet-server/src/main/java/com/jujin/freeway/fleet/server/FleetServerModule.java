package com.jujin.freeway.fleet.server;

import com.jujin.freeway.fleet.FleetKeys;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import java.util.List;

/**
 * The registry server as a Freeway module: compose it with
 * {@code HttpModule} (and boot) to run a standalone Fleet Server.
 *
 * <p>This is the control plane, not an application runtime: it owns the
 * registry surface and its store, and it never registers itself into the fleet
 * it serves. The module binds a {@link LeaseStore}; the app composes the HTTP
 * listener and chooses the deployment's node discovery separately (static
 * seed list, an external load balancer, or the database the store will grow
 * into).</p>
 *
 * <p><b>Current maturity.</b> The surface has an optional shared token but no
 * TLS and an in-memory store; it is a deployable starting point, not yet a
 * production control plane. Production hardening (durable leases, admin UI,
 * mTLS/RBAC, HA) belongs to this server module, not to
 * {@code freeway-cloud}.</p>
 */
public final class FleetServerModule implements ModuleEx {

    /** The surface as data: bind contributes routes, the index describes them. */
    static final List<Surface> SURFACE = List.of(
        new Surface("register", "freeway.fleet.register", "POST",
            FleetProtocol.REGISTER, FleetApi.class),
        new Surface("renew", "freeway.fleet.renew", "POST",
            FleetProtocol.RENEW, FleetApi.class),
        new Surface("unregister", "freeway.fleet.unregister", "DELETE",
            FleetProtocol.UNREGISTER, FleetApi.class),
        new Surface("discover", "freeway.fleet.discover", "GET",
            FleetProtocol.DISCOVER, FleetApi.class),
        new Surface("ready", "freeway.fleet.ready", "POST",
            FleetProtocol.READY, FleetApi.class),
        new Surface("index", "freeway.fleet.index", "GET",
            FleetProtocol.SELF_DESCRIPTION, FleetIndexHandler.class)
    );

    /** One served route: the index label, the contribution id, and how to route it. */
    record Surface(String label, String id, String method, String path,
                   Class<? extends RouteHandler> handler) {}

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(FleetKeys.class, FleetKeys.PREFIX));
        b.bind(LeaseStore.class).to(MemLeaseStore.class);
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

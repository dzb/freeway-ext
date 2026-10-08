package com.jujin.freeway.cloud.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The adapter against a <em>live</em> Consul agent.
 *
 * <p>Gated by {@code FREEWAY_TEST_CONSUL} (host:port, defaulting to
 * {@code 127.0.0.1:8500}) per this repo's convention: skipped without it, and
 * red against a dead address — every case performs a real call, so a wrong or
 * unreachable agent fails rather than passing vacuously.
 *
 * <p>Two shapes. The first drives the adapter directly (register → discover →
 * renew → unregister). The second is the one a stub cannot prove: a real
 * {@code FreewayApp} whose core {@code RegistryLifecycleHook} registers this
 * process into Consul on start and deregisters it on close — the adapter under
 * the real lifecycle, not under a hand-rolled call sequence.
 */
@EnabledIfEnvironmentVariable(named = "FREEWAY_TEST_CONSUL", matches = ".*")
class ConsulIntegrationTest {

    private static ConsulWiring wiring() {
        String address = System.getenv().getOrDefault("FREEWAY_TEST_CONSUL", "127.0.0.1:8500");
        String[] hostPort = address.split(":", 2);
        return ConsulWiring.defaults()
            .withAgentHost(hostPort[0])
            .withAgentPort(hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 8500)
            .withTtl(Duration.ofSeconds(30));
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(ConfigKeys.REGISTRY_SERVICE_ID);
        System.clearProperty(ConfigKeys.REGISTRY_SHUTDOWN_DRAIN);
    }

    @Test
    void adapterRoundTripAgainstALiveAgent() {
        ConsulClient client = new ConsulClient(wiring());
        String serviceId = "freeway-it-" + UUID.randomUUID();
        ServiceInstance instance = ServiceInstance.of(
            serviceId, "i1",
            Endpoint.of("https", "10.1.2.3", 8443, "/api"),
            Map.of("zone", "test"));
        try {
            client.register(instance);

            List<ServiceInstance> found = client.instances(serviceId);
            assertEquals(1, found.size(), "the just-registered instance must be discoverable");
            ServiceInstance read = found.get(0);
            assertEquals("i1", read.instanceId());
            assertEquals("https", read.endpoint().scheme(), "scheme round-trips through Meta");
            assertEquals("/api", read.endpoint().basePath(), "basePath round-trips through Meta");
            assertEquals("10.1.2.3", read.endpoint().host());
            assertEquals(8443, read.endpoint().port());
            assertEquals("test", read.metadata().get("zone"), "application metadata round-trips");

            assertTrue(client.renew(serviceId, "i1"), "a registered instance renews true");
        } finally {
            client.unregister(instance);
        }
        assertTrue(client.instances(serviceId).isEmpty(),
            "after unregister the service is no longer discoverable");
    }

    @Test
    void theCoreLifecycleRegistersAndDeregistersThroughConsul() throws Exception {
        String serviceId = "freeway-it-" + UUID.randomUUID();
        System.setProperty(ConfigKeys.REGISTRY_SERVICE_ID, serviceId);
        System.setProperty(ConfigKeys.REGISTRY_SHUTDOWN_DRAIN, "PT0S");

        try (AppRuntime app = FreewayApp.create(
                new HttpModule(), new CloudDiscoveryModule(), new ConsulModule()).start()) {

            int port = app.get(HttpServer.class).port();
            ServiceDiscovery discovery = app.get(ServiceDiscovery.class);

            List<ServiceInstance> registered = awaitInstances(discovery, serviceId, 1);
            assertEquals(1, registered.size(),
                "the core hook must have registered this process into Consul");
            assertEquals(port, registered.get(0).endpoint().port(),
                "the registered endpoint is the process's own HTTP port");
        }
        // close() ran the hook's unregister; the service is gone from Consul.
        ServiceDiscovery afterClose = new ConsulServiceDiscovery(new ConsulClient(wiring()));
        assertTrue(awaitInstances(afterClose, serviceId, 0).isEmpty(),
            "close must deregister the instance");
    }

    /** Polls until the service has exactly {@code expected} instances, or times out. */
    private static List<ServiceInstance> awaitInstances(
            ServiceDiscovery discovery, String serviceId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<ServiceInstance> last = List.of();
        while (System.currentTimeMillis() < deadline) {
            last = discovery.instances(serviceId);
            if (last.size() == expected) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }

}

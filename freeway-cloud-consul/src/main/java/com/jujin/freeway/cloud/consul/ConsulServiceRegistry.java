package com.jujin.freeway.cloud.consul;

import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;

import java.time.Duration;
import java.util.Objects;

/**
 * Consul-backed {@link ServiceRegistry}. Lifecycle, re-registration on a lost
 * lease and shutdown ordering stay in the core's {@code RegistryLifecycleHook}
 * — this type only answers the four operations.
 */
public final class ConsulServiceRegistry implements ServiceRegistry {

    private final ConsulClient client;
    private final Duration drainWindow;

    public ConsulServiceRegistry(ConsulClient client, ConsulWiring wiring) {
        this.client = Objects.requireNonNull(client, "client");
        this.drainWindow = Objects.requireNonNull(wiring, "wiring").drainWindow();
    }

    @Override
    public void register(ServiceInstance instance) {
        client.register(instance);
    }

    @Override
    public boolean renew(String serviceId, String instanceId) {
        return client.renew(serviceId, instanceId);
    }

    @Override
    public void unregister(ServiceInstance instance) {
        client.unregister(instance);
    }

    /**
     * Deregistration reaches consumers over the gossip/anti-entropy path, not
     * instantly as the in-process registry does, so the shutdown drain waits
     * this window before the socket closes.
     */
    @Override
    public Duration drainWindow() {
        return drainWindow;
    }
}

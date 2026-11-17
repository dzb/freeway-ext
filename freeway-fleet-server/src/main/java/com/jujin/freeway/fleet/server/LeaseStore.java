package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.time.Duration;
import java.util.List;

/**
 * The lease table behind a registry backend: who is registered, when they were last seen, and
 * whether they are ready.
 *
 * <p>One owner for the server's storage semantics. {@link H2LeaseStore} is the embedded default;
 * {@link MemLeaseStore} serves tests, and a PostgreSQL store can replace either without changing
 * the HTTP surface.
 */
public interface LeaseStore {

  /** Default staleness threshold for lease stores. */
  Duration DEFAULT_EVICTION = Duration.ofSeconds(30);

  void register(ServiceInstance instance);

  void register(ServiceInstance instance, ServiceHealth health);

  boolean renew(String serviceId, String instanceId, boolean ready);

  boolean markReady(String serviceId, String instanceId, boolean ready);

  boolean renew(String serviceId, String instanceId);

  void unregister(ServiceInstance instance);

  boolean unregister(String serviceId, String instanceId);

  int instanceCount();

  List<ServiceInstance> liveReadyInstances(String serviceId, Duration maxAge);
}

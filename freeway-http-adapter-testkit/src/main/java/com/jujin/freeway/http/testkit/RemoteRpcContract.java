/*
 * Copyright 2026 dzb
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jujin.freeway.http.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.rpc.CloudException;
import com.jujin.freeway.cloud.rpc.CloudHttpClientDefault;
import com.jujin.freeway.cloud.rpc.RemoteCaller;
import com.jujin.freeway.cloud.rpc.RemoteInvocationException;
import com.jujin.freeway.cloud.rpc.RpcEndpoint;
import com.jujin.freeway.cloud.rpc.RpcExport;
import com.jujin.freeway.cloud.rpc.TransportSecurity;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.http.WebServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Remote invocation over the engine under test: the server exports a handler through {@link
 * RpcEndpoint}, and the client drives {@link RemoteCaller} against a stub registry, so the whole
 * exchange is engine-real but self-contained.
 */
public abstract class RemoteRpcContract extends EngineFixture {

  /** A business failure thrown by the remote handler; the client must see it rebuilt. */
  public static class BizFailure extends RuntimeException {
    public BizFailure(String message) {
      super(message);
    }
  }

  /** Handlers must be public: reflective dispatch honors access rules. */
  public static class Handlers {
    public String greet(String name) {
      return "hi " + name;
    }

    public int add(int a, int b) {
      return a + b;
    }

    public String boom() {
      throw new BizFailure("overdrawn");
    }
  }

  private WebServer server;
  private RemoteCaller caller;

  @AfterEach
  void stopServer() {
    if (server != null) server.close();
  }

  @Test
  void roundTripOverTheEngine() {
    startServer();
    assertEquals("hi bob", caller.invoke("target", "user", "greet", List.of("bob"), String.class));
  }

  @Test
  void primitiveArgsSurviveTheWire() {
    startServer();
    assertEquals(42, caller.invoke("target", "user", "add", List.of(2, 40), Integer.class));
  }

  @Test
  void businessFailureMapsToNonRetryableWithRemoteClass() {
    startServer();
    CloudException ex =
        assertThrows(
            CloudException.class,
            () -> caller.invoke("target", "user", "boom", List.of(), String.class));
    assertFalse(ex.retryable());
    Object cause = ex.getCause();
    assertTrue(
        cause instanceof RemoteInvocationException,
        "cause must be the rebuilt remote exception, got: " + cause);
    assertEquals(BizFailure.class.getName(), ((RemoteInvocationException) cause).remoteClass());
  }

  @Test
  void unknownTopicIs404() {
    startServer();
    CloudException ex =
        assertThrows(
            CloudException.class,
            () -> caller.invoke("target", "user", "missing", List.of(), String.class));
    assertEquals(404, ex.status());
  }

  private void startServer() {
    server =
        start(
            Pipelines.of(
                List.of(
                    RpcEndpoint.route(
                        RpcExport.of("user", Handlers.class), new Handlers(), jsonCodec()))));

    // RemoteCaller needs CloudHttpClient; build a minimal standalone stack
    // that always resolves the service to the server just started.
    ServiceDiscovery discovery =
        serviceId ->
            List.of(
                ServiceInstance.of(
                    serviceId, "i1", Endpoint.of("http", "127.0.0.1", server.port()), Map.of()));
    LoadBalancer loadBalancer =
        instances ->
            instances.isEmpty() ? Optional.empty() : Optional.ofNullable(instances.getFirst());
    var cloudClient =
        new CloudHttpClientDefault(
            discovery,
            loadBalancer,
            new CloudHttpClientDefault.Wiring(
                List.of(), // no propagators
                null, // retryer -> built-in default fallback
                null, // no breaker
                null, // no rate limiter
                TransportSecurity.NONE,
                null, // no tracer
                new NoopMetrics(),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                null)); // shutdown grace -> built-in default
    caller = new RemoteCaller(cloudClient, jsonCodec());
  }
}

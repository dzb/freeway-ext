package com.jujin.freeway.cloud.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class K8sClientTest {

  private static final String LIST_PATH =
      "/apis/discovery.k8s.io/v1/namespaces/default/endpointslices";

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(LIST_PATH, this::handleEndpointSlices);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void informsEndpointSlicesAndIndexesThemByServiceName() {
    ConfigBuilder config =
        new ConfigBuilder()
            .withMasterUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .withNamespace("default")
            .withTrustCerts(true)
            .withHttp2Disable(true);
    KubernetesClient client = new KubernetesClientBuilder().withConfig(config.build()).build();
    K8sOptions options = new K8sOptions("default", "http", "http", "", "", Duration.ofSeconds(30));

    try (K8sClient k8s = new K8sClient(client, options)) {
      assertEquals(1, k8s.slices("orders").size());
      assertTrue(k8s.slices("payments").isEmpty());
    }
  }

  private void handleEndpointSlices(HttpExchange exchange) throws IOException {
    if (exchange.getRequestURI().getQuery() != null
        && exchange.getRequestURI().getQuery().contains("watch=true")) {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
      return;
    }
    byte[] body =
        """
        {"apiVersion":"discovery.k8s.io/v1","kind":"EndpointSliceList",
         "metadata":{"resourceVersion":"1"},
         "items":[{"apiVersion":"discovery.k8s.io/v1","kind":"EndpointSlice",
           "metadata":{"name":"orders-a","namespace":"default","resourceVersion":"1",
             "labels":{"kubernetes.io/service-name":"orders"}},
           "addressType":"IPv4",
           "endpoints":[{"addresses":["10.0.0.1"],"conditions":{"ready":true}}],
           "ports":[{"name":"http","protocol":"TCP","port":8080}]}]}
        """
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}

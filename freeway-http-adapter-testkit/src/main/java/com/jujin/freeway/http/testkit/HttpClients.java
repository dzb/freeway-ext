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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** HTTP client helpers shared by the engine contracts. */
public final class HttpClients {

  /** The password of every test keystore fixture in this repository. */
  public static final String KEYSTORE_PASSWORD = "changeit";

  private HttpClients() {}

  public static HttpClient plain() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** A client that trusts the given test keystore, for TLS contract checks. */
  public static HttpClient trusting(Path keyStore) throws Exception {
    return HttpClient.newBuilder()
        .sslContext(trustingSslContext(keyStore))
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  public static HttpRequest request(String uri, String correlationId) {
    var builder = HttpRequest.newBuilder(URI.create(uri)).GET().timeout(Duration.ofSeconds(10));
    if (correlationId != null) {
      builder.header("X-Request-Id", correlationId);
    }
    return builder.build();
  }

  public static byte[] gunzip(byte[] data) throws Exception {
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return gzip.readAllBytes();
    }
  }

  /** Every message on the cause chain — the assertion target is not always the root. */
  public static String causes(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append(" | ");
    }
    return messages.toString();
  }

  private static SSLContext trustingSslContext(Path keyStorePath) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keyStorePath)) {
      keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
    }
    var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keyStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), null);
    return context;
  }
}

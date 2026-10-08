package com.jujin.freeway.cloud.consul;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Consul agent's HTTP API, wrapped in the four operations the discovery
 * seam needs. JDK {@link HttpClient} only — no third-party Consul client,
 * matching the core's dependency discipline.
 *
 * <p>The wire format is Consul's, so it is built and read with the static
 * {@link JsonUtils} rather than the application's {@code JsonCodec}: an
 * injected codec exists to serve the application's data model, and this is not
 * that.
 *
 * <p><b>Bookkeeping keys.</b> Consul's service model is {@code Address} +
 * {@code Port} + a flat string {@code Meta} map; freeway's {@link Endpoint}
 * also carries {@code scheme} and {@code basePath}, and
 * {@code CloudHttpClientDefault} renders the outbound URL from all four
 * ({@code endpoint().uri() + request.path()}). Dropping the two extra fields
 * would silently mis-call an https service or a path-prefixed one, so they
 * travel in {@code Meta} under a reserved {@code freeway.} prefix — which also
 * keeps them from colliding with the application's own metadata, copied
 * verbatim.
 */
final class ConsulClient {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulClient.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String TOKEN_HEADER = "X-Consul-Token";
    private static final String JSON = "application/json";
    /** Consul removes a critical service this long after its TTL check stops. */
    private static final String DEREGISTER_CRITICAL_AFTER = "1m";
    /** RFC 3986 {@code pchar} beyond the unreserved set: kept literal in a path segment. */
    private static final String PCHAR_EXTRA = "-._~:@!$&'()*+,;=";

    private static final String META_INSTANCE_ID = "freeway.instance-id";
    private static final String META_SCHEME = "freeway.scheme";
    private static final String META_BASE_PATH = "freeway.base-path";

    private final ConsulWiring wiring;
    private final HttpClient http;

    public ConsulClient(ConsulWiring wiring) {
        this.wiring = Objects.requireNonNull(wiring, "wiring");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** Registers (or re-registers) the instance, arming its TTL check. */
    public void register(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        JsonObject check = JsonUtils.object()
            .put("TTL", wiring.ttl().toSeconds() + "s")
            .put("DeregisterCriticalServiceAfter", DEREGISTER_CRITICAL_AFTER);
        JsonObject body = JsonUtils.object()
            .put("ID", consulId(instance))
            .put("Name", instance.serviceId())
            .put("Address", instance.endpoint().host())
            .put("Port", instance.endpoint().port())
            .put("Meta", meta(instance))
            .put("Check", check);
        send("PUT", "/v1/agent/service/register", JsonUtils.stringify(body));
    }

    /**
     * Heartbeat. {@code false} means the agent no longer holds the instance
     * (evicted, TTL expired, agent restarted) — the contract the core reads as
     * "re-register me".
     */
    public boolean renew(String serviceId, String instanceId) {
        HttpResponse<String> response = exchange("PUT",
            "/v1/agent/check/pass/" + segment(consulId(serviceId, instanceId)), null);
        int status = response.statusCode();
        if (status / 100 == 2) {
            return true;
        }
        if (status == 404) {
            return false; // the agent does not know this check — re-register
        }
        // Anything else (403 on a bad token, 5xx) is "could not determine", not
        // "not held": throwing lets the core's heartbeat mark the node unhealthy
        // and log it, instead of looping a re-register that will fail the same way.
        throw new IllegalStateException(
            "Consul renew for '" + consulId(serviceId, instanceId) + "' failed: HTTP " + status);
    }

    /** Removes the instance from the agent. */
    public void unregister(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        send("PUT", "/v1/agent/service/deregister/" + segment(consulId(instance)), null);
    }

    /** Live (passing) instances for the service. */
    public List<ServiceInstance> instances(String serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        HttpResponse<String> response = exchange("GET",
            "/v1/health/service/" + segment(serviceId) + "?passing=true", null);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                "Consul health query for '" + serviceId + "' failed: HTTP " + response.statusCode());
        }
        JsonArray entries = JsonUtils.parseArray(response.body());
        List<ServiceInstance> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.getObject(i);
            JsonObject service = entry == null ? null : entry.getObject("Service");
            if (service == null) {
                continue;
            }
            String address = service.getString("Address");
            Integer port = service.getInt("Port");
            if (address == null || address.isBlank() || port == null || port <= 0) {
                // One instance Consul holds without a routable address must not
                // abort discovery for the whole service.
                LOG.warn("Skipping Consul instance with no routable address:port (ID={})",
                    service.getString("ID"));
                continue;
            }
            result.add(toInstance(serviceId, service, address, port));
        }
        return List.copyOf(result);
    }

    // ==================== mapping ====================

    private static ServiceInstance toInstance(
            String serviceId, JsonObject service, String address, int port) {
        String consulId = service.getString("ID");
        JsonObject meta = service.getObject("Meta");
        Map<String, String> metadata = new LinkedHashMap<>();
        String instanceId = null;
        String scheme = null;
        String basePath = null;
        if (meta != null) {
            for (String key : meta.keySet()) {
                switch (key) {
                    case META_INSTANCE_ID -> instanceId = meta.getString(key);
                    case META_SCHEME -> scheme = meta.getString(key);
                    case META_BASE_PATH -> basePath = meta.getString(key);
                    default -> metadata.put(key, meta.getString(key));
                }
            }
        }
        if (instanceId == null) {
            // Older/foreign registrations carry no bookkeeping key: recover the
            // instance id from the ID we write ({serviceId}:{instanceId}).
            String prefix = serviceId + ":";
            instanceId = consulId != null && consulId.startsWith(prefix)
                ? consulId.substring(prefix.length())
                : consulId;
        }
        Endpoint endpoint = Endpoint.of(
            scheme == null || scheme.isBlank() ? "http" : scheme,
            address, port,
            basePath == null ? "" : basePath);
        return ServiceInstance.of(serviceId, instanceId, endpoint, metadata);
    }

    private Map<String, String> meta(ServiceInstance instance) {
        Map<String, String> meta = new LinkedHashMap<>(instance.metadata());
        meta.put(META_INSTANCE_ID, instance.instanceId());
        meta.put(META_SCHEME, instance.endpoint().scheme());
        meta.put(META_BASE_PATH, instance.endpoint().basePath());
        return meta;
    }

    private static String consulId(ServiceInstance instance) {
        return consulId(instance.serviceId(), instance.instanceId());
    }

    private static String consulId(String serviceId, String instanceId) {
        return serviceId + ":" + instanceId;
    }

    /**
     * Percent-encodes one path segment, keeping RFC 3986 {@code pchar} as-is —
     * notably {@code :}, which separates the service id from the instance id in
     * the Consul check id and is a legal path character (a generic form encoder
     * turns it into {@code %3A} and the agent then misses the check).
     */
    private static String segment(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean pchar = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || PCHAR_EXTRA.indexOf(c) >= 0;
            if (pchar) {
                encoded.append((char) c);
            } else {
                encoded.append('%')
                    .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    // ==================== transport ====================

    private void send(String method, String path, String body) {
        HttpResponse<String> response = exchange(method, path, body);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                "Consul " + method + " " + path + " failed: HTTP " + response.statusCode());
        }
    }

    private HttpResponse<String> exchange(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(wiring.baseUri() + path))
            .timeout(TIMEOUT);
        if (wiring.hasToken()) {
            builder.header(TOKEN_HEADER, wiring.token());
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", JSON)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Consul " + method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consul " + method + " " + path + " interrupted", e);
        }
    }
}

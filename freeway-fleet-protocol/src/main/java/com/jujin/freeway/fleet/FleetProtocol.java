package com.jujin.freeway.fleet;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The Fleet wire contract: routes, headers and version shared by client and
 * server. The resource is a fleet of registry nodes, so the wire prefix is
 * {@code /fleet}.
 */
public final class FleetProtocol {
    private FleetProtocol() {}

    public static final String REGISTER = "/fleet/services/{serviceId}/instances";
    public static final String RENEW =
        "/fleet/services/{serviceId}/instances/{instanceId}/renew";
    public static final String UNREGISTER =
        "/fleet/services/{serviceId}/instances/{instanceId}";
    public static final String DISCOVER = "/fleet/services/{serviceId}/instances";
    public static final String READY =
        "/fleet/services/{serviceId}/instances/{instanceId}/ready";
    public static final String SELF_DESCRIPTION = "/fleet";

    public static final String TOKEN_HEADER = "X-Fleet-Token";
    public static final String PROTOCOL_HEADER = "X-Fleet-Proto";
    public static final int PROTOCOL = 1;

    public static String register(String serviceId) {
        return path(REGISTER, serviceId, null);
    }

    public static String renew(String serviceId, String instanceId) {
        return path(RENEW, serviceId, instanceId);
    }

    public static String unregister(String serviceId, String instanceId) {
        return path(UNREGISTER, serviceId, instanceId);
    }

    public static String discover(String serviceId) {
        return path(DISCOVER, serviceId, null);
    }

    public static String ready(String serviceId, String instanceId) {
        return path(READY, serviceId, instanceId);
    }

    private static String path(String template, String serviceId, String instanceId) {
        String path = template.replace("{serviceId}", segment(serviceId));
        return instanceId == null ? path : path.replace("{instanceId}", segment(instanceId));
    }

    private static String segment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

package com.jujin.freeway.fleet;

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
}

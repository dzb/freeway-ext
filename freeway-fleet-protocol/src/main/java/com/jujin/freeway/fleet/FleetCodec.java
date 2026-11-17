package com.jujin.freeway.fleet;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;

/**
 * The Fleet wire codec owned by the protocol, not by the application.
 *
 * <p>A custom application {@code JsonCodec} must not change what a Fleet node accepts or how H2
 * stores instances; client, server and store therefore share this one instance. It is stateless
 * after construction.
 */
public final class FleetCodec {
  private FleetCodec() {}

  public static final JsonCodec JSON = new JsonCodecDefault();
}

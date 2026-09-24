package com.jujin.freeway.mq.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;

/**
 * The wire contract between {@link KafkaEvents} (writer) and the poll machinery (reader): one
 * home for every header name. Both sides used to carry their own copies, so a rename on one side
 * would have silently misrouted records instead of failing.
 *
 * <p>Headers follow the CloudEvents Kafka binding: every CloudEvents attribute rides as a
 * {@code ce-} header, so a record carries the same logical envelope as the WS mesh's JSON
 * frames ({@code CloudEventEnvelope} in freeway-cloud) — same attributes, JSON content mode
 * there, header mode here. Attribute mapping:
 *
 * <ul>
 *   <li>{@code ce-specversion} = {@code "1.0"}, always;</li>
 *   <li>{@code ce-id} = a fresh frame identity per send (informational for legacy consumers;
 *       this plane correlates nothing — cross-transport identity died with the bus bridge);</li>
 *   <li>{@code ce-source} = {@code freeway://{origin}} — the sending node (Kafka has no
 *       service-registry concept, so unlike the mesh's service-based source this names the
 *       node, mirroring {@code ce-fworigin});</li>
 *   <li>{@code ce-type} = the payload class name, informational only — routing never resolves
 *       classes off the wire; the payload type is whatever the matching subscription declared;</li>
 *   <li>{@code ce-subject} = the partition key given to {@code send}, when one was given;</li>
 *   <li>{@code ce-time} = send time; {@code ce-datacontenttype} = {@code application/json}
 *       (the record value is the JSON-encoded event);</li>
 *   <li>{@code ce-fwchannel} = always {@code topic} on this plane (the class-channel
 *       vocabulary died with the bus bridge; legacy records may still carry {@code class});
 *       {@code ce-fworigin} = the sending node (own-origin records are skipped);</li>
 *   <li>{@code ce-traceparent}/{@code ce-tracestate} = the sender's trace, stamped only
 *       when the sending thread holds one — same extensions the mesh carries, restored
 *       around dispatch on receipt (never principal or baggage: nothing on the event
 *       path authenticates the producer).</li>
 * </ul>
 *
 * <p>Records produced before the CE rename carry {@code X-Event-*} headers. The log is
 * durable — a rolling upgrade meets those records — so every read prefers the {@code ce-}
 * name and falls back to the legacy one. Writes never emit legacy names.
 */
final class KafkaHeaders {

  static final String CE_SPECVERSION = "ce-specversion";
  static final String CE_ID = "ce-id";
  static final String CE_SOURCE = "ce-source";
  static final String CE_TYPE = "ce-type";
  static final String CE_SUBJECT = "ce-subject";
  static final String CE_TIME = "ce-time";
  static final String CE_DATA_TYPE = "ce-datacontenttype";
  static final String CE_CHANNEL = "ce-fwchannel";
  static final String CE_ORIGIN = "ce-fworigin";
  /** Trace extensions ride the same binding (no legacy names — trace is new). */
  static final String CE_TRACEPARENT = "ce-traceparent";
  static final String CE_TRACESTATE = "ce-tracestate";

  static final String SPEC_VERSION = "1.0";
  static final String DATA_CONTENT_TYPE = "application/json";

  /** Pre-CE names — read fallback only, never written. */
  static final String LEGACY_ORIGIN = "X-Event-Origin";

  static final String DLQ_ORIGINAL_TOPIC = "X-DLQ-Original-Topic";
  static final String DLQ_ORIGINAL_OFFSET = "X-DLQ-Original-Offset";
  static final String DLQ_REASON = "X-DLQ-Reason";

  private KafkaHeaders() {}

  /** Writes a UTF-8 header — the encoding both sides assume. */
  static void put(Headers headers, String name, String value) {
    headers.add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Writes a CloudEvents extension attribute as a header — the Kafka binding
   * rule ({@code traceparent} → {@code ce-traceparent}). The single place
   * the {@code ce-} prefix is minted, so no call site concatenates it.
   */
  static void putExtension(Headers headers, String name, String value) {
    put(headers, "ce-" + name, value);
  }

  /** Reads {@code name}, falling back to {@code legacyName} — null when both are absent. */
  static String read(Headers headers, String name, String legacyName) {
    String value = raw(headers, name);
    if (value == null && legacyName != null) {
      value = raw(headers, legacyName);
    }
    return value;
  }

  private static String raw(Headers headers, String name) {
    var header = headers.lastHeader(name);
    // Kafka allows null header values; treat them as absent.
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

}

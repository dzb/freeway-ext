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

package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.ioc.EventSink;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;

/**
 * The wire contract between {@link KafkaEventSink} (writer) and {@link KafkaSubscriber} (reader):
 * one home for every header name and for the channel token. Both sides used to carry their own
 * copies, so a rename on one side would have silently misrouted records instead of failing.
 */
final class KafkaHeaders {

  static final String EVENT_TYPE = "X-Event-Type";
  static final String EVENT_ORIGIN = "X-Event-Origin";
  static final String EVENT_CHANNEL = "X-Event-Channel";
  static final String EVENT_ID = "X-Event-Id";
  static final String DLQ_ORIGINAL_TOPIC = "X-DLQ-Original-Topic";
  static final String DLQ_ORIGINAL_OFFSET = "X-DLQ-Original-Offset";
  static final String DLQ_REASON = "X-DLQ-Reason";

  private KafkaHeaders() {}

  /** Writes a UTF-8 header — the encoding both sides assume. */
  static void put(Headers headers, String name, String value) {
    headers.add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  /** The channel token on the wire: the enum name, never a literal. */
  static String channelToken(EventSink.Channel channel) {
    return channel.name();
  }

  /**
   * Whether the record travelled on the class dispatch channel. An absent header means an older
   * producer and falls back to topic dispatch, the pre-channel behavior.
   */
  static boolean classChannel(Headers headers) {
    var header = headers.lastHeader(EVENT_CHANNEL);
    if (header == null || header.value() == null) {
      return false;
    }
    String channel = new String(header.value(), StandardCharsets.UTF_8).trim();
    return EventSink.Channel.CLASS.name().equalsIgnoreCase(channel);
  }
}

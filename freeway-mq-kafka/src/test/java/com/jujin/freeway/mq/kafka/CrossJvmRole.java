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

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Child-JVM entry point for {@link CrossJvmEventTest}: one role per process,
 * so the two sides of the stream share nothing but the broker — each enters
 * the plane by name ({@link KafkaEvents#send} / {@link KafkaEvents#subscribe}).
 *
 * <pre>
 * java CrossJvmRole subscriber &lt;broker&gt; &lt;topic&gt;
 * java CrossJvmRole publisher  &lt;broker&gt; &lt;topic&gt;
 * </pre>
 *
 * <p>The subscriber prints {@code SUBSCRIBER-READY} once its consumer is
 * polling, then one {@code RECEIVED ...} line per delivered event, and
 * finally {@code SUBSCRIBER-OK} (exit 0) or {@code SUBSCRIBER-TIMEOUT}
 * (exit 1). The publisher prints {@code PUBLISHED ...} after the records are
 * written.
 */
public final class CrossJvmRole {

  private static final String TOPIC = "freeway.xjvm.greet";

  private CrossJvmRole() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("usage: CrossJvmRole <subscriber|publisher> <broker> <topic>");
      System.exit(2);
    }
    String role = args[0];
    String broker = args[1];
    String topic = args[2];
    if ("subscriber".equalsIgnoreCase(role)) {
      subscriber(broker, topic);
    } else if ("publisher".equalsIgnoreCase(role)) {
      publisher(broker, topic);
    } else {
      System.err.println("unknown role: " + role);
      System.exit(2);
    }
  }

  /**
   * Polls the topic and reports what its declared subscriptions received.
   * The two subscriptions are the allowlist: a typed record and a String
   * record under the same topic decode into the declared type each.
   */
  private static void subscriber(String broker, String topic) throws Exception {
    var config =
        KafkaConfig.of(
            broker, "freeway-xjvm-sub", "xjvm-subscriber", topic, "skip", "", "", 1, 500, 1, true);
    var classEvent = new AtomicReference<CrossJvmOrder>();
    var textEvent = new AtomicReference<String>();
    var both = new CountDownLatch(2);
    KafkaEvents plane = new KafkaEvents(config, new JsonCodecDefault());
    plane.subscribe(topic, CrossJvmOrder.class, event -> {
      classEvent.set(event);
      System.out.println("RECEIVED typed " + event);
      both.countDown();
    });
    plane.subscribe(topic, String.class, text -> {
      textEvent.set(text);
      System.out.println("RECEIVED string " + text);
      both.countDown();
    });
    plane.start();
    try {
      // The consumer group has to join before the publisher writes, otherwise a fresh group
      // starting at "latest" would skip the records entirely.
      Thread.sleep(3000);
      System.out.println("SUBSCRIBER-READY");
      System.out.flush();
      boolean ok = both.await(45, TimeUnit.SECONDS);
      System.out.println(
          ok
              ? "SUBSCRIBER-OK " + classEvent.get() + " / " + textEvent.get()
              : "SUBSCRIBER-TIMEOUT typed=" + classEvent.get() + " string=" + textEvent.get());
      System.out.flush();
      System.exit(ok ? 0 : 1);
    } finally {
      plane.close();
    }
  }

  /** Sends one typed record and one String record; both must reach the other JVM. */
  private static void publisher(String broker, String topic) throws Exception {
    var config =
        KafkaConfig.of(
            broker, "freeway-xjvm-pub", "xjvm-publisher", topic, "skip", "", "", 1, 500, 1, true);
    try (KafkaEvents plane = new KafkaEvents(config, new JsonCodecDefault())) {
      plane.send(topic, new CrossJvmOrder("order-42", 7), "order-42");
      plane.send(topic, "hello-from-the-publisher");
      // The producer is asynchronous: give it time to flush before the JVM exits.
      Thread.sleep(5000);
      System.out.println("PUBLISHED typed+string to " + topic);
    }
  }
}

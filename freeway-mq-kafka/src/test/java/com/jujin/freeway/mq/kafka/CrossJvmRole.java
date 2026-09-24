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
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.Freeway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Child-JVM entry point for {@link CrossJvmEventTest}: one role per process, so the two sides of
 * the bridge share nothing but the broker.
 *
 * <pre>
 * java CrossJvmRole subscriber &lt;broker&gt; &lt;bridge-topic&gt;
 * java CrossJvmRole publisher  &lt;broker&gt; &lt;bridge-topic&gt;
 * </pre>
 *
 * <p>The subscriber prints {@code SUBSCRIBER-READY} once its consumer is polling, then one {@code
 * RECEIVED class ...} / {@code RECEIVED topic ...} line per event that reached this JVM's local
 * bus, and finally {@code SUBSCRIBER-OK} (exit 0) or {@code SUBSCRIBER-TIMEOUT} (exit 1). The
 * publisher prints {@code PUBLISHED ...} after the events are written.
 */
public final class CrossJvmRole {

  private static final String LOCAL_TOPIC = "freeway.xjvm.greet";

  private CrossJvmRole() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("usage: CrossJvmRole <subscriber|publisher> <broker> <bridge-topic>");
      System.exit(2);
    }
    String role = args[0];
    String broker = args[1];
    String bridgeTopic = args[2];
    if ("subscriber".equalsIgnoreCase(role)) {
      subscriber(broker, bridgeTopic);
    } else if ("publisher".equalsIgnoreCase(role)) {
      publisher(broker, bridgeTopic);
    } else {
      System.err.println("unknown role: " + role);
      System.exit(2);
    }
  }

  /**
   * Consumes from the bridge topic and reports what reached the local bus. The allowlist names both
   * bridged types, because a typed record outside it is rejected as poison — and the string-topic
   * event's type header is {@code java.lang.String}.
   */
  private static void subscriber(String broker, String bridgeTopic) throws Exception {
    var config =
        KafkaConfig.of(
            broker,
            "freeway-xjvm-sub",
            "xjvm-subscriber",
            bridgeTopic,
            String.join(",", CrossJvmOrder.class.getName(), "java.lang.String"),
            "skip",
            "",
            "",
            1,
            500,
            1,
            true);
    var classEvent = new AtomicReference<CrossJvmOrder>();
    var topicEvent = new AtomicReference<String>();
    var both = new CountDownLatch(2);
    try (Container container = Freeway.create()) {
      EventBus bus = container.get(EventBus.class);
      bus.subscribe(
          CrossJvmOrder.class,
          event -> {
            classEvent.set(event);
            System.out.println("RECEIVED class " + event);
            both.countDown();
          });
      bus.subscribe(
          LOCAL_TOPIC,
          payload -> {
            topicEvent.set(String.valueOf(payload));
            System.out.println("RECEIVED topic " + payload);
            both.countDown();
          });
      try (KafkaSubscriber subscriber = new KafkaSubscriber(config, bus, new JsonCodecDefault())) {
        subscriber.start();
        // The consumer group has to join before the publisher writes, otherwise a fresh group
        // starting at "latest" would skip the records entirely.
        Thread.sleep(3000);
        System.out.println("SUBSCRIBER-READY");
        System.out.flush();
        boolean ok = both.await(45, TimeUnit.SECONDS);
        System.out.println(
            ok
                ? "SUBSCRIBER-OK " + classEvent.get() + " / " + topicEvent.get()
                : "SUBSCRIBER-TIMEOUT class=" + classEvent.get() + " topic=" + topicEvent.get());
        System.out.flush();
        System.exit(ok ? 0 : 1);
      }
    }
  }

  /** Publishes one class event and one string-topic event; both must reach the other JVM. */
  private static void publisher(String broker, String bridgeTopic) throws Exception {
    var config =
        KafkaConfig.of(
            broker,
            "freeway-xjvm-pub",
            "xjvm-publisher",
            bridgeTopic,
            "",
            "skip",
            "",
            "",
            1,
            500,
            1,
            true);
    KafkaEventSink sink = new KafkaEventSink(config, new JsonCodecDefault());
    try (sink;
        Container container =
            Freeway.create(binder -> binder.contribute(EventSink.class).add(sink))) {
      EventBus bus = container.get(EventBus.class);
      bus.publish(new CrossJvmOrder("order-42", 7));
      bus.publish(LOCAL_TOPIC, "hello-from-the-publisher");
      // The producer is asynchronous: give it time to flush before the JVM exits.
      Thread.sleep(5000);
      System.out.println(
          "PUBLISHED class=" + CrossJvmOrder.class.getSimpleName() + " topic=" + LOCAL_TOPIC);
    }
  }
}

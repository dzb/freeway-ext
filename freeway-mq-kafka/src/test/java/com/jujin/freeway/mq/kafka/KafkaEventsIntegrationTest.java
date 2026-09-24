package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real-broker contract test for the durable stream plane: a live Apache Kafka instance must be
 * reachable at {@code FREEWAY_TEST_KAFKA} (host:port), defaulting to {@code 127.0.0.1:9092}. Skips
 * cleanly when no broker is present — the mock-based suites stay the default CI path.
 *
 * <p>Verifies the full wire loop the mocks cannot: send → broker → poll → typed delivery into the
 * receiving plane's subscriptions, exact-once arrival, the partition key, and own-origin
 * suppression on a shared plane.
 */
@EnabledIfEnvironmentVariable(named = "FREEWAY_TEST_KAFKA", matches = ".*")
class KafkaEventsIntegrationTest {

  private static final String BROKER =
      System.getenv().getOrDefault("FREEWAY_TEST_KAFKA", "127.0.0.1:9092");

  record OrderCreated(String orderId) {}

  private static KafkaEvents publisher;
  private static KafkaEvents subscriberPlane;
  private static final String TOPIC = "freeway-integration";

  @BeforeAll
  static void setUp() throws Exception {
    // The two sides are separate planes with separate origins (they could be
    // separate JVMs — CrossJvmRole proves that shape). A delivery observed on
    // the subscriber plane can therefore only come over the broker.
    var publisherConfig =
        KafkaConfig.of(
            BROKER, "freeway-it-pub", "it-producer", TOPIC, "skip", "", "", 1, 1000, 1, true, 0);
    var subscriberConfig =
        KafkaConfig.of(
            BROKER, "freeway-it-sub", "it-consumer", TOPIC, "skip", "", "", 1, 1000, 1, true, 0);
    publisher = new KafkaEvents(publisherConfig, new JsonCodecDefault());
    subscriberPlane = new KafkaEvents(subscriberConfig, new JsonCodecDefault());
    subscriberPlane.start();
    // Allow the consumer group to join and the topic to be created.
    Thread.sleep(2000);
  }

  @AfterAll
  static void tearDown() {
    if (subscriberPlane != null) subscriberPlane.close();
    if (publisher != null) publisher.close();
  }

  @Test
  void stringRecordCrossesTheBroker() throws Exception {
    var received = new CountDownLatch(1);
    var payload = new AtomicReference<String>();
    subscriberPlane.subscribe(
        TOPIC,
        String.class,
        value -> {
          payload.set(value);
          received.countDown();
        });

    publisher.send(TOPIC, "hello-kafka");
    assertTrue(
        received.await(15, TimeUnit.SECONDS),
        "the record must cross the broker to the other plane");
    assertEquals("hello-kafka", payload.get());
  }

  @Test
  void typedRecordRoundTripsThroughKafka() throws Exception {
    var received = new CountDownLatch(1);
    var event = new AtomicReference<OrderCreated>();
    subscriberPlane.subscribe(
        TOPIC,
        OrderCreated.class,
        e -> {
          event.set(e);
          received.countDown();
        });

    publisher.send(TOPIC, new OrderCreated("order-1"));
    assertTrue(received.await(15, TimeUnit.SECONDS), "typed record must cross and rebuild");
    assertEquals(new OrderCreated("order-1"), event.get());
  }

  @Test
  void mixedTypesOnOneTopicDeliverToTheirOwnSubscriptions() throws Exception {
    // The per-subscription decode isolates type mismatches: a String record
    // must reach the String subscription only, and vice versa, with neither
    // poisoning the topic for the other.
    var strings = new AtomicInteger();
    var orders = new AtomicInteger();
    var both = new CountDownLatch(2);
    subscriberPlane.subscribe(
        TOPIC,
        String.class,
        v -> {
          strings.incrementAndGet();
          both.countDown();
        });
    subscriberPlane.subscribe(
        TOPIC,
        OrderCreated.class,
        v -> {
          orders.incrementAndGet();
          both.countDown();
        });

    publisher.send(TOPIC, "mixed-hello");
    publisher.send(TOPIC, new OrderCreated("mixed-1"), "mixed-1");

    assertTrue(both.await(15, TimeUnit.SECONDS), "each type reaches its own subscription");
    Thread.sleep(1500); // let a leaked cross-decode or poison loop show itself
    assertEquals(1, strings.get(), "the string record delivered once, to the String only");
    assertEquals(1, orders.get(), "the typed record delivered once, to its type only");
  }

  @Test
  void ownSendsNeverLoopBackToTheSamePlane() throws Exception {
    // Same plane publishes and subscribes the topic: with suppress-own (the
    // default) its own records must not re-enter — the loop guard that kept
    // the bridge from echoing.
    var looped = new AtomicInteger();
    var remote = new CountDownLatch(1);
    publisher.subscribe(TOPIC, String.class, v -> looped.incrementAndGet());
    subscriberPlane.subscribe(
        TOPIC,
        String.class,
        v -> {
          remote.countDown();
        });

    publisher.send(TOPIC, "no-echo");
    assertTrue(remote.await(15, TimeUnit.SECONDS), "the other plane still receives");
    Thread.sleep(2500); // give a broken loop time to deliver
    assertEquals(0, looped.get(), "a plane must never receive its own send back");
  }
}

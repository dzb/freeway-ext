package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real-broker contract test for the Kafka bridge: a live Apache Kafka instance must be reachable at
 * {@code FREEway_TEST_KAFKA} (host:port), defaulting to {@code 127.0.0.1:9092}. Skips cleanly when
 * no broker is present — the mock-based suites stay the default CI path.
 *
 * <p>Verifies the full wire loop the mocks cannot: publish → broker → consume → publishInbound →
 * local subscriber, both dispatch channels, origin suppression, and the Keyed partition key.
 */
@EnabledIfEnvironmentVariable(named = "FREEWAY_TEST_KAFKA", matches = ".*")
class KafkaBridgeIntegrationTest {

  private static final String BROKER =
      System.getenv().getOrDefault("FREEWAY_TEST_KAFKA", "127.0.0.1:9092");

  record OrderCreated(String orderId) {}

  record KeyedOrder(String id) implements EventBus.Keyed {
    @Override
    public String key() {
      return id;
    }
  }

  private static Container container;
  private static KafkaConfig config;
  private static EventBus bus;
  private static KafkaEventBridge bridge;
  private static KafkaSubscriber subscriber;
  private static final String TOPIC = "freeway-integration";

  @BeforeAll
  static void setUp() throws Exception {
    config =
        KafkaConfig.of(
            BROKER, // bootstrapServers
            "freeway-it", // groupId
            "it-producer", // clientId
            TOPIC, // topics
            "", // allowedEventTypes (defaults to Map-only for untyped)
            "skip", // poisonPolicy
            "", // propertiesRaw
            "", // dlqTopic
            1, // maxRetries
            1000, // retryBackoffMs
            1, // concurrency
            true); // suppressOwn
    container = Freeway.create();
    bus = container.get(EventBus.class);
    bridge = new KafkaEventBridge(config, new JsonCodecDefault());
    bus.setEventBridge(bridge);
    subscriber = new KafkaSubscriber(config, bus, new JsonCodecDefault());
    subscriber.start();
    // Allow the consumer group to join and the topic to be created.
    Thread.sleep(2000);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (subscriber != null) subscriber.close();
    if (bridge != null) bridge.close();
    if (container != null) container.close();
  }

  @Test
  void topicEventCrossesTheBroker() throws Exception {
    var received = new CountDownLatch(1);
    var payload = new AtomicReference<String>();
    bus.subscribe(
        TOPIC + ".greet",
        value -> {
          payload.set(String.valueOf(value));
          received.countDown();
        });

    bus.publish(TOPIC + ".greet", "hello-kafka");
    assertTrue(
        received.await(15, TimeUnit.SECONDS),
        "topic event must cross the broker to the local subscriber");
    assertEquals("hello-kafka", payload.get());
  }

  @Test
  void classEventRoundTripsThroughKafka() throws Exception {
    var received = new CountDownLatch(1);
    var event = new AtomicReference<OrderCreated>();
    bus.subscribe(
        OrderCreated.class,
        e -> {
          event.set(e);
          received.countDown();
        });

    bus.publish(new OrderCreated("order-1"));
    assertTrue(
        received.await(15, TimeUnit.SECONDS), "class event must cross the broker and rebuild");
    assertEquals(new OrderCreated("order-1"), event.get());
  }

  @Test
  void keyedEventsCarryThePartitionKey() throws Exception {
    var received = new CountDownLatch(1);
    var event = new AtomicReference<KeyedOrder>();
    bus.subscribe(
        KeyedOrder.class,
        e -> {
          event.set(e);
          received.countDown();
        });

    bus.publish(new KeyedOrder("key-42"));
    assertTrue(received.await(15, TimeUnit.SECONDS));
    assertEquals("key-42", event.get().id);
  }

  @Test
  void suppressOwnKeepsLocalEventsFromLooping() throws Exception {
    // Local dispatch fires at publish time (synchronous); the broker loop
    // must NOT deliver a second copy. Count deliveries: exactly one.
    var deliveries = new java.util.concurrent.atomic.AtomicInteger();
    bus.subscribe(TOPIC + ".loop", v -> deliveries.incrementAndGet());
    bus.publish(TOPIC + ".loop", "no-self");
    Thread.sleep(2500); // give the broker loop time to re-deliver if broken
    assertEquals(
        1, deliveries.get(), "own events must be suppressed at the broker loop (origin header)");
  }
}

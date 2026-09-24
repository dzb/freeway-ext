package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.Freeway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real-broker contract test for the Kafka event sink: a live Apache Kafka instance must be
 * reachable at {@code FREEway_TEST_KAFKA} (host:port), defaulting to {@code 127.0.0.1:9092}. Skips
 * cleanly when no broker is present — the mock-based suites stay the default CI path.
 *
 * <p>Verifies the full wire loop the mocks cannot: publish → broker → consume → publishInbound →
 * local subscriber, both dispatch channels, origin suppression, and the Keyed partition key.
 */
@EnabledIfEnvironmentVariable(named = "FREEWAY_TEST_KAFKA", matches = ".*")
class KafkaEventSinkIntegrationTest {

  private static final String BROKER =
      System.getenv().getOrDefault("FREEWAY_TEST_KAFKA", "127.0.0.1:9092");

  record OrderCreated(String orderId) {}

  record KeyedOrder(String id) implements EventBus.Keyed {
    @Override
    public String key() {
      return id;
    }
  }

  private static Container pubContainer;
  private static Container subContainer;
  private static EventBus bus;
  private static EventBus subBus;
  private static KafkaEventSink sink;
  private static KafkaSubscriber subscriber;
  private static final String TOPIC = "freeway-integration";

  @BeforeAll
  static void setUp() throws Exception {
    // The two sides live in separate containers (and could be separate JVMs): the
    // publisher bus has no subscriber, the subscriber bus never publishes. An
    // assertion on the subscriber bus can therefore only pass if the record
    // crossed the broker — the earlier single-bus version passed on the
    // synchronous local dispatch alone and proved nothing about the wire.
    var publisherConfig =
        KafkaConfig.of(
            BROKER, "freeway-it-pub", "it-producer", TOPIC, "", "skip", "", "", 1, 1000, 1, true);
    // A subscriber origin distinct from the producer's: with suppress-own on, a
    // shared origin would drop every bridged record as "own".
    var subscriberConfig =
        KafkaConfig.of(
            BROKER,
            "freeway-it-sub",
            "it-consumer",
            TOPIC,
            String.join(
                ",", OrderCreated.class.getName(), KeyedOrder.class.getName(), "java.lang.String"),
            "skip",
            "",
            "",
            1,
            1000,
            1,
            true);
    sink = new KafkaEventSink(publisherConfig, new JsonCodecDefault());
    pubContainer =
        Freeway.create(binder -> binder.contribute(EventSink.class).add(sink));
    subContainer = Freeway.create();
    bus = pubContainer.get(EventBus.class);
    subBus = subContainer.get(EventBus.class);
    subscriber = new KafkaSubscriber(subscriberConfig, subBus, new JsonCodecDefault());
    subscriber.start();
    // Allow the consumer group to join and the topic to be created.
    Thread.sleep(2000);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (subscriber != null) subscriber.close();
    if (sink != null) sink.close();
    if (pubContainer != null) pubContainer.close();
    if (subContainer != null) subContainer.close();
  }

  @Test
  void topicEventCrossesTheBroker() throws Exception {
    var received = new CountDownLatch(1);
    var payload = new AtomicReference<String>();
    subBus.subscribe(
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
    subBus.subscribe(
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
    subBus.subscribe(
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
    // Local dispatch fires at publish time (synchronous) — one delivery on the
    // publisher bus. With a distinct subscriber origin, the record must cross
    // to the other bus exactly once, and must not come back as a second copy.
    var localDeliveries = new java.util.concurrent.atomic.AtomicInteger();
    var remoteDeliveries = new java.util.concurrent.atomic.AtomicInteger();
    var crossed = new CountDownLatch(1);
    bus.subscribe(TOPIC + ".loop", v -> localDeliveries.incrementAndGet());
    subBus.subscribe(
        TOPIC + ".loop",
        v -> {
          remoteDeliveries.incrementAndGet();
          crossed.countDown();
        });
    bus.publish(TOPIC + ".loop", "no-self");
    assertTrue(crossed.await(15, TimeUnit.SECONDS), "the bridge must deliver to the other bus");
    Thread.sleep(2500); // give a broken loop time to deliver extra copies
    assertEquals(1, localDeliveries.get(), "local dispatch happens exactly once");
    assertEquals(1, remoteDeliveries.get(), "the bridged copy arrives exactly once");
  }
}

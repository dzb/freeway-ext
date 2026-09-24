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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The bridge across two JVMs: one process publishes, another consumes, and nothing but the broker
 * connects them. This is the case the in-JVM test cannot cover — there the local synchronous
 * dispatch satisfies a {@code bus.subscribe(...)} assertion even when not a single record reached
 * the broker.
 *
 * <p>Requires a live broker at {@code FREEWAY_TEST_KAFKA} (host:port) and is skipped without it,
 * like {@link KafkaEventsIntegrationTest}. The bridge topic is created per run, so concurrent runs
 * and stale offsets cannot interfere.
 */
@EnabledIfEnvironmentVariable(named = "FREEWAY_TEST_KAFKA", matches = ".*")
class CrossJvmEventTest {

  private static final String BROKER =
      System.getenv().getOrDefault("FREEWAY_TEST_KAFKA", "127.0.0.1:9092");

  @Test
  void aClassEventAndAStringTopicEventCrossBetweenProcesses() throws Exception {
    String topic = "freeway-xjvm-" + UUID.randomUUID().toString().replace("-", "");
    createTopic(topic);

    Process subscriber = start("subscriber", topic);
    // Read line by line: the subscriber stays alive while we wait for its
    // handshake, so a read-until-EOF would not observe anything until it exits.
    List<String> subscriberLog = Collections.synchronizedList(new ArrayList<>());
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var in =
                      new BufferedReader(
                          new InputStreamReader(
                              subscriber.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                      subscriberLog.add(line);
                    }
                  } catch (Exception e) {
                    subscriberLog.add("(unreadable: " + e + ")");
                  }
                });
    try {
      assertTrue(
          awaitLine(subscriberLog, "SUBSCRIBER-READY", Duration.ofSeconds(30)),
          "subscriber must be polling before the publisher writes: " + subscriberLog);

      Process publisher = start("publisher", topic);
      String publisherLog = readAll(publisher);
      assertTrue(
          publisher.waitFor(60, TimeUnit.SECONDS) && publisher.exitValue() == 0,
          "publisher failed: " + publisherLog);
      assertTrue(
          publisherLog.contains("PUBLISHED"),
          "publisher must report what it wrote: " + publisherLog);

      assertTrue(
          awaitLine(subscriberLog, "SUBSCRIBER-OK", Duration.ofSeconds(60)),
          "the other JVM must receive both events over the broker: " + subscriberLog);
      String received = String.join("\n", subscriberLog);
      assertTrue(
          received.contains("RECEIVED class CrossJvmOrder[orderId=order-42, amount=7]"),
          "the class event must be rebuilt in the consumer JVM: " + received);
      assertTrue(
          received.contains("RECEIVED topic hello-from-the-publisher"),
          "the string-topic event must arrive under its local topic: " + received);
    } finally {
      subscriber.destroyForcibly();
      subscriber.waitFor(10, TimeUnit.SECONDS);
      reader.join(5000);
    }
  }

  /** Runs {@link CrossJvmRole} in its own JVM with this test's classpath. */
  private static Process start(String role, String topic) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("-cp");
    // surefire's forked JVM exposes the real test classpath here; the plain
    // java.class.path may be a booter jar.
    command.add(
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    command.add(CrossJvmRole.class.getName());
    command.add(role);
    command.add(BROKER);
    command.add(topic);
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }

  private static String readAll(Process process) {
    var out = new StringBuilder();
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        out.append(line).append('\n');
      }
    } catch (Exception e) {
      out.append("(unreadable: ").append(e).append(')');
    }
    return out.toString();
  }

  /** Waits for a line to appear in the child's captured output. */
  private static boolean awaitLine(List<String> log, String needle, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (contains(log, needle)) return true;
      Thread.sleep(200);
    }
    return contains(log, needle);
  }

  private static boolean contains(List<String> log, String needle) {
    synchronized (log) {
      return log.stream().anyMatch(line -> line.contains(needle));
    }
  }

  private static void createTopic(String topic) throws Exception {
    var props = new java.util.Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
    try (Admin admin = Admin.create(props)) {
      admin
          .createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }
  }
}

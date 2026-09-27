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

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/**
 * IoC module wiring the durable stream plane ({@link KafkaEvents}) into the container.
 *
 * <p>Its bindings carry no {@code .id(...)}/{@code .primary()}: the plane has no framework-provided
 * default to step aside for, so a plain binding is the honest one — an application that binds its
 * own gets the duplicate-binding error instead of being silently outranked. Adapters that
 * substitute a core role do the opposite: {@code HikariPool} binds {@code Pool} with {@code
 * .id("hikari").primary()} so the built-in default steps aside.
 *
 * <p>Lifecycle (start polling, then close the poller and the producer) is contributed as the
 * {@value #LIFECYCLE_HOOK} runtime hook. Publishing and subscribing are calls on the plane itself —
 * nothing is installed onto any other component at runtime.
 */
public final class KafkaModule implements ModuleEx {

  /**
   * Runtime-hook id for the Kafka plane lifecycle. Not a config key — an identity string that
   * merely looks like one, so it stays out of {@link ConfigKeys}.
   */
  public static final String LIFECYCLE_HOOK = "freeway.kafka.lifecycle";

  @Override
  public void bind(Binder binder) {
    binder
        .bind(KafkaConfig.class)
        .to(container -> KafkaConfig.from(container.get(SymbolSource.class)));

    // Declared vocabulary for the unknown-key check.
    binder.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));

    // One plane instance owns the producer, the subscription table and the
    // poller — bound through the container so the hook and every injectee
    // share it. The producer arrives at composition (broker connection is
    // lazy); a misconfigured bootstrap fails at first use, not at startup.
    binder
        .bind(KafkaEvents.class)
        .to(c -> new KafkaEvents(c.get(KafkaConfig.class), c.get(JsonCodec.class)));

    binder
        .contribute(RuntimeHook.class)
        .add(
            LIFECYCLE_HOOK,
            new RuntimeHook() {
              @Override
              public void start(Container container) {
                container.get(KafkaEvents.class).start();
              }

              @Override
              public void stop(Container container) {
                container.get(KafkaEvents.class).close();
              }
            });
  }

  /**
   * The {@code freeway.kafka.*} vocabulary, spelled as full literals — every key {@link
   * KafkaConfig} resolves, mirrored here so the unknown-key check recognizes (and can suggest a fix
   * for) this namespace. {@link KafkaConfig} stays the single declaration of each key's type and
   * default; this table spells names only.
   */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    /** The namespace this table declares — new to the framework, owned by this module. */
    public static final String PREFIX = "freeway.kafka";

    // ── Broker / node ────────────────────────────────────────────

    /** Bootstrap server list (default {@code localhost:9092}). */
    public static final String BOOTSTRAP_SERVERS = "freeway.kafka.bootstrap-servers";

    /** Consumer group id (default {@code freeway}). */
    public static final String GROUP_ID = "freeway.kafka.group-id";

    /**
     * Node identity behind {@code suppress-own} — unique per node (unset falls back to a per-JVM
     * UUID).
     */
    public static final String CLIENT_ID = "freeway.kafka.client-id";

    /**
     * Comma-separated poll set: {@code send} targets topics by name, and a topic nobody polls
     * receives nothing. One of the three keys that must agree across nodes.
     */
    public static final String TOPICS = "freeway.kafka.topics";

    /** Extra Kafka client properties as {@code key=value} pairs separated by semicolons. */
    public static final String PROPERTIES = "freeway.kafka.properties";

    // ── Delivery semantics ──────────────────────────────────────

    /**
     * Poison-message policy: {@code skip} (default) logs and continues, {@code fail} stops the
     * subscriber so the offset is not committed.
     */
    public static final String POISON_POLICY = "freeway.kafka.poison-policy";

    /** DLQ topic for poison messages; empty (default) disables forwarding. */
    public static final String DLQ_TOPIC = "freeway.kafka.dlq-topic";

    /** Redelivery attempts before giving up (default 1; 0 = no retry). */
    public static final String MAX_RETRIES = "freeway.kafka.max-retries";

    /** Backoff between redelivery attempts in milliseconds (default 1000). */
    public static final String RETRY_BACKOFF_MS = "freeway.kafka.retry-backoff-ms";

    /** Subscriber poll concurrency (default 1). */
    public static final String CONCURRENCY = "freeway.kafka.concurrency";

    /** Dispatch ids remembered for duplicate suppression (default 0 = off). */
    public static final String DEDUP_CAPACITY = "freeway.kafka.dedup-capacity";

    /** Drop events this node produced itself (default {@code true}). */
    public static final String SUPPRESS_OWN = "freeway.kafka.suppress-own";
  }
}

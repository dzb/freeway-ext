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

/**
 * The event both JVMs of {@link CrossJvmEventTest} must share: it is serialized by name on the wire
 * (the {@code ce-type} header carries this class's binary name), so the subscriber JVM
 * rebuilds it only because the same class is on its classpath too.
 */
public record CrossJvmOrder(String orderId, int amount) {}

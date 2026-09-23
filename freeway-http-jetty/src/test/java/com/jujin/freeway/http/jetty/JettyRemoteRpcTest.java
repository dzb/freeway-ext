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

package com.jujin.freeway.http.jetty;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.testkit.RemoteRpcContract;

/** Jetty runs the shared remote-invocation contract. */
class JettyRemoteRpcTest extends RemoteRpcContract {

  @Override
  protected com.jujin.freeway.http.HttpEngine newEngine() {
    return new JettyHttpEngine(new JsonCodecDefault(), new CoercerDefault());
  }

  @Override
  protected String engineName() {
    return "jetty";
  }
}

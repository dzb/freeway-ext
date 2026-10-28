# freeway-ext 适配器契约核对（core 1.5.7-SNAPSHOT）

> 对 `freeway-ext` 的 8 个模块做一次与 core 审计同方法的契约核对：不看文档自述，只看
> (a) 对刚 install 的 core 快照做**干净**构建，和 (b) 适配器代码与 core SPI 契约逐条对照。
> 每条判据都指到文件与行为。

## 0. 方法与基线

- **干净构建**（增量构建不算证据）：
  ```
  cd ../freeway-framework && mvn -o -DskipTests -Dgpg.skip=true install
  cd ../freeway-ext       && mvn -o -Dgpg.skip=true clean test
  ```
  结果：8 模块全绿 —— testkit 0、Jetty 39、Undertow 35、Kafka 51（5 例按 `FREEWAY_TEST_KAFKA`
  跳过）、Consul 13（2 例跳过）、Hikari 21、Benchmark 41。
- **增量构建的反例**：第一次没有 `clean`，Undertow 的测试在**运行时**报
  `IllegalAccessError: failed to access class com.jujin.freeway.http.SslContexts`；`clean` 之后
  同一个缺陷在**编译期**报 `SslContexts 在 com.jujin.freeway.http 中不是公共的`。Maven 看见
  ext 源码没变就跳过了编译，`target/classes` 里留着旧 class——正是 ext AGENTS 警告的
  "an incremental compile keeps the previous class"。
- 核对对象：`HttpEngine`（Jetty、Undertow）、`Pool`（Hikari）、`ServiceRegistry` + `ServiceDiscovery`
  （Consul）、Kafka 事件平面，以及共享的 `freeway-http-adapter-testkit` 契约。

## 0.1 本轮已修（core 侧）

| 项 | 修复 | 证据 |
|---|---|---|
| `SslContexts` 被收窄为包私有 | 恢复 `public`（它的 javadoc 本来就写明"Undertow builds its SSLContext from here"） | ext 干净构建由红转绿 |

这是 core 1.5.7 审计里"公共面按角色收口"的一次过度收窄：判断依据是"仓内只有同包
`HttpModule` 使用"，但**仓内没有调用者不是证据**——`freeway-ext` 的 Undertow 适配器终止 TLS
时必须用同一套 keystore/SNI 逻辑，编译器是两者保持一致的方式。

## 0.2 本轮已修（ext 侧）

| 项 | 修复 | 验证 |
|---|---|---|
| 两引擎的 `maxHeaderBytes` | Undertow：`MAX_HEADER_SIZE` + h2 header-list settings；Jetty：明文/HTTPS/h2c 共用 `HttpConfiguration.setRequestHeaderSize`（明文分支不再用裸 `ServerConnector`） | `HeaderBudgetContract` 两引擎各 2 例 |
| testkit 契约 | 新增 `HeaderBudgetContract`；`ContextContract` 增加"请求头名全小写"与"204 不带 `Content-Length`" | 两引擎通过 |
| Consul readiness | `ConsulClient.register(instance, ready)` → `passing`/`critical`；`markReady` → check pass/fail；`ConsulServiceRegistry` 覆盖两个接缝方法 | `ConsulClientTest` 新增 4 例 |
| 过期注释 | `ExchangeMetaDefault` → `ExchangeMetaImpl` | — |

ext 干净构建：**8 模块全绿，Jetty 43、Undertow 39、Kafka 51（5 跳过）、Consul 17（2 跳过）、
Hikari 21、Benchmark 41、testkit 0**；`spotless:check` 通过。

## 1. 结论

| 级别 | 问题 | 位置 | 状态 |
|---|---|---|---|
| 阻断 | `SslContexts` 可见性导致 ext 无法编译 | core `http/SslContexts` | ✅ 已修（core） |
| 高 | 新配置字段 `maxHeaderBytes` 两个引擎都忽略且不报告 | `UndertowHttpEngine`、`JettyHttpEngine` | ✅ 已修 |
| 高 | Consul 没有实现 readiness 新接缝，注册即 `passing` | `ConsulServiceRegistry`/`ConsulClient` | ✅ 已修 |
| 中 | testkit 没有覆盖新契约（头预算、headers 小写、无体状态 framing） | `freeway-http-adapter-testkit` | ✅ 已修 |
| 低 | 注释仍写旧名 `ExchangeMetaDefault` | `JettyWebSocketBridge` | ✅ 已修（一行） |

**总体判断**：适配器本身质量不低于 core——共享规则真的在共享（`ResponseFraming`、`Compression`、
`AbstractHttpContext.readBody`），句柄归属有守卫（Hikari 的 foreign-release / invalidate→release），
testkit 是"契约即 API"的正确形态。失分集中在**新契约向 ext 的传播**：一个新配置字段、一个新
readiness 方法，都是"core 加了、适配器没跟上，且没有任何东西会响"。

## 2. 逐项核对

### 2.1 `HttpServerConfig` 12 个字段（AGENTS：每个字段被采纳或被报告）

| 字段 | Undertow | Jetty |
|---|---|---|
| host / port | ✅ listener | ✅ connector |
| backlog | ✅ `Options.BACKLOG`（>0 时） | ✅ `acceptQueueSize` |
| shutdownGrace | ✅ handle | ✅ `setStopTimeout` |
| maxBodySize | ✅ `MAX_ENTITY_SIZE` + context `readBody` | ✅ context `readBody` |
| readTimeout | ✅ `IDLE_TIMEOUT`/`REQUEST_PARSE_TIMEOUT`（0→-1） | ✅ `setIdleTimeout` |
| maxConnections | ⚠️ 无对应，**启动 WARN 报告** | ✅ `NetworkConnectionLimit` |
| writeTimeout | ⚠️ 无对应，**启动 WARN 报告** | ⚠️ 无对应，**启动 WARN 报告** |
| compression | ✅ context `output()`，走 `ResponseFraming.shouldGzip` | ✅ 同 |
| receiveBufferSize / sendBufferSize | ✅ socket options（>0 时） | ✅ accepted buffers（>0 时） |
| **maxHeaderBytes** | ✅ `MAX_HEADER_SIZE` + h2 `HTTP2_SETTINGS_MAX_HEADER_LIST_SIZE`（同值） | ✅ 三个分支共用 `HttpConfiguration.setRequestHeaderSize` |

两条都违反"每个字段被采纳或被报告"，而且 Undertow 那行注释本身已经是过期事实（像 core 审计里
"文档说假话"那一类）。**修复**：

- Undertow：`.setServerOption(UndertowOptions.MAX_HEADER_SIZE, config.maxHeaderBytes())`；
  若 h2 的 header-list 上限有对应 option，一并映射（`javap io.undertow.UndertowOptions | grep -i header`）。
- Jetty：`HttpConfiguration https = new HttpConfiguration(); https.setRequestHeaderSize(config.maxHeaderBytes())`，
  h2c 分支的 `HttpConfiguration` 同样处理；HTTP/2 的 `HTTP2ServerConnectionFactory` 若另有限制，
  显式设置或报告。
- 测试：在 testkit 加 `HeaderBudgetContract`（`defaultConfig().withMaxHeaderBytes(1024)`，
  发 2 KiB 头，断言 4xx 且不是 500；再断言默认值 ≥16 KiB 的正常头被接受）。两个适配器各继承它。

### 2.2 其它共享契约

| 契约 | Undertow | Jetty | 判据 |
|---|---|---|---|
| `headers()` 键小写 | ✅ `name.toString().toLowerCase(ROOT)` | ✅ `toLowerCase(ROOT)` | core `Headers.copyOf` 的同一契约 |
| `maxBodySize` 走共享 limiter（413） | ✅ `readBody` + 把 Undertow 的 `RequestTooBigException` 归一成 `BodyTooLargeException` | ✅ `readBody` | core `AbstractHttpContext`/`LimitedInputStream` |
| gzip 走共享规则 | ✅ `ResponseFraming.shouldGzip` + `Compression` | ✅ 同 | 不自己造第二套 gate |
| 无体状态不带 Content-Length | ✅ `allowsResponseBody()` 为假时移除 | ✅ 同 | 与 core `ResponseFraming.suppressesHeader` 同义 |
| WS 升级交 `ExchangeHandler.websocket` | ✅ 每个 upgrade 候选都问 | ✅ 同 | 无匹配时：core 403、Jetty 404 文本、Undertow 默认页——testkit 只断言"不是 101"，是有意为之 |
| 未处理异常的兜底 | 文本 500 | 文本 500 | 只在内核错误映射之外的逃逸路径；core 已统一 JSON 错误体，适配器此处算"最后一跳"，记录但不要求 |

### 2.3 Hikari（`Pool`）

- 契约点都做了：外来句柄显式拒绝（`release`/`invalidate` 都校验 `HkConn`）、`invalidate` 后
  `release` 变 no-op（避免 HikariCP 已逐出条目再 close 抛错）、`close()` 直接委托
  `HikariDataSource.close()` 并说明不等待借出连接、`stats()` 的 `longLeased` 恒 0 有文档解释
  （改用 HikariCP 自己的 leak-detection）。
- `PoolConfig` 中无 HikariCP 对应物的字段（`cleanInterval`/`queryTimeout`）在构造器注释里点名，
  属于"报告"而非静默。
- 结论：**无发现**。

### 2.4 Consul（`ServiceRegistry` / `ServiceDiscovery`）

- 已实现：`register` / `renew`（404→false，其它非 2xx 抛出让 core 记为 unhealthy）/ `unregister`
  / `drainWindow`；discovery 用 `?passing=true`，与"live && ready"同义。
- **缺口（高）**：core 1.5.7 的 readiness 接缝是两个方法——
  `register(instance, Health)` 与 `markReady(instance, boolean)`。Consul 适配器一个都没覆盖：
  - `RegistryLifecycleHook` 现在以 `Health.starting()` 调 `register`；接口默认实现退回
    `register(instance)`，`ConsulClient.register` 把 TTL check 写成 `"Status": "passing"`
    （注释还解释了"否则注册到首次心跳之间有黑洞"）——于是**未就绪实例在 Consul 里立即
    `passing`，可被发现**，core 这一版专门消除的窗口又回来了。
  - `ReadinessGate` 的 `markReady(false/true)` 是默认 no-op，因此对 Consul **整门失效**；
    core 只会打一行 INFO（"bound registry is not the built-in one"），那只是解释，不是修复。
- **修复**：
  - `ConsulClient.register(instance, boolean ready)`：`Check.Status` 写 `passing`/`critical`；
    核心 hook 给的就是 `Health`，适配器负责翻译。
  - 覆盖 `ServiceRegistry.register(instance, Health)` 与 `markReady(instance, boolean)`：
    markReady → `PUT /v1/agent/check/pass|fail/{checkId}`（失败按 `renew` 的口径处理）。
  - 测试：注册为 starting 时 discovery 不含它，`markReady(true)` 后包含；已有 `ConsulClientTest`
    的 stub server 可扩展。

### 2.5 Kafka

- 本周期 core 没有改 Kafka 平面的 SPI（`KafkaEvents`/桥接契约不变）；适配器的 51 例（5 例
  env-gated）覆盖 producer、订阅表、poller、ce-fwtopic 透传、suppress-own 与 allowed-event-types。
- ext AGENTS 列的三个"必须一致"的键仍成立；无发现。

### 2.6 Testkit

已有契约：`CompressionContract`、`ContextContract`（传输诚实、TLS 激活、超大 body 413、上下文
新鲜度）、`RemoteRpcContract`、`WebSocketUpgradeContract`。

缺口（按价值）：

1. **头预算**：没有任何契约覆盖 `maxHeaderBytes`（这正是两个引擎一起漏掉它的原因）。
2. **`headers()` 小写**：core 把"请求头名统一小写"写成 `RequestView` 契约，两个适配器都做了，
   但没有契约钉住——新引擎可以悄悄不遵守。
3. **无体状态 framing**：`204/205/304` 不带 `Content-Length`、`HEAD` 保留——目前靠共享
   `allowsResponseBody()`，仍应有契约断言线上结果。
4. 可选：`withMaxConnections`/`withWriteTimeout` 的"被报告"行为不易测（日志断言脆），
   建议不纳入 testkit。

## 3. 修复清单（按优先级）

1. **core**：`SslContexts` 恢复 public（✅ 已做，等待与 ext 的干净构建一起回归）。
2. **Undertow/Jetty**：接 `maxHeaderBytes`（h1 必须，h2 能力允许则接，否则报告）+ 新增
   `HeaderBudgetContract`。
3. **Consul**：实现 `register(instance, Health)` 与 `markReady`，TTL check 状态随 readiness 翻转；
   测试 starting→passing 的可见性变化。
4. **testkit**：`ContextContract` 增补 headers 小写与无体 framing 断言。
5. **文档/流程**：ext `AGENTS.md` 的 Build 一节补一句"核 core 变更必须 `clean` 构建，增量会
   留旧 class 掩盖 ABI 破坏"；`JettyWebSocketBridge` 注释里的 `ExchangeMetaDefault` 改成现行名。

## 4. 评分

| 维度 | 分 | 依据 |
|---|---|---|
| 定位 | 4 | 每个适配器只做自己的传输；共享规则确实共享 |
| 机制 | 4 | guards 到位（外来句柄、invalidate→release、413 归一、gzip 单一 gate） |
| 概念 | 4 | 每引擎一个 `HttpEngine`，testkit 是契约面，没有第三套抽象 |
| 边界 | 3 | 新配置字段/新 readiness 接缝的"传播"没有机制，靠人记得 |
| 规则 | 4 | adapter 不改 core 的方向保持；"被报告"用得诚实 |
| 文档 | 3 | 注释过期（8192、`ExchangeMetaDefault`）；readiness 缺口无任何声响 |

**一句话**：适配器实现是健康的；真正的缺陷是 **core 的新契约没有"编译期或启动期会响"的传播
通道**——一个字段被忽略、一个方法没覆盖，都要等下一次人工审计才发现。

# freeway-ext 结构与一致性审计（1.5.2-SNAPSHOT）

- 日期：2026-09-13
- 审计对象：`freeway-ext` 全部 5 个模块（`freeway-http-jetty` / `freeway-http-undertow` /
  `freeway-mq-kafka` / `freeway-db-hikari` / `freeway-benchmark`），主源码 42 文件 ≈7 533 行
- 审计维度：**结构**（职责划分、分层、命名、包组织）、**一致性**（模块内自洽、模块间对齐、
  与 core 约定对齐）、**简洁性**（重复实现、样板、死代码、不必要的间接层）
- 判据来源：`freeway-ext/CLAUDE.md`、core `AGENTS.md`（`XDefault`/`XImpl` 规则、"协作者包私有"、
  配置归属分层、"prefer small explicit APIs"）、core 现有 SPI 实现与已发布 jar 的真实字节码
- 审计基线：`main`@`375248e`

## 0. 结论速览

**先说一条对既有认知的修正**：本仓库**无法从 clean 构建通过**。此前 `mvn test` 报的
BUILD SUCCESS 是**陈旧增量 class 造成的假绿**——`freeway-benchmark/target/classes` 停留在
9 月 6 日（core 1.5.1 时代），core 升到 1.5.2-SNAPSHOT 后 benchmark 源码再没被重新编译过。
`mvn clean test` 的真实结果：4 个适配器模块 92 测试全绿，**benchmark 模块编译失败**
（详见 §2，已独立复现）。

除此之外，功能与代码卫生状况良好：无未使用类型、许可证头与类级 javadoc 100% 一致、无 TODO 遗留
（benchmark 1 处）。结构性问题集中在三处"已经在悄悄分叉"的地方：

| # | 主题 | 位置 | 严重度 |
|---|------|------|--------|
| 0 | **clean 构建失败**：`Binder.install` 被 core 1.5.2 删除、`HttpContextImpl.reset` 由 11 参降为 10 参，benchmark 未跟进 | benchmark ↔ core | **阻塞** |
| 1 | 两个 HTTP 适配器是近克隆：≈200 行生产代码逐字节重复，≈450 行测试代码近乎复制 | jetty ↔ undertow | 高 |
| 2 | 池化 context 未清 exchange 元数据：principal/attributes/correlationId/startTime 跨请求泄漏 | jetty + undertow | 高 |
| 3 | `ssl.enabled` 语义与 core 分叉：只配 keystore 时静默起明文，而框架自称 HTTPS | jetty + undertow | 高 |
| 4 | `isSecure()`/`sslSession()`/`remoteAddress()` 未实现：TLS 下 `isSecure()==false`、访问日志 IP 恒为 `-` | jetty + undertow | 高 |
| 5 | `KafkaConfig` 的 12 个 `@Value` 是死注解，默认值在三个文件里声明三遍 | kafka | 高 |

## 1. 审计方法

1. **逐模块精读**：5 个模块主源码全部读完（benchmark 28/28 文件；JMH 类逐行读而非抽样）。
2. **跨模块归一化对照**：`Jetty*` 与 `Undertow*` 同名类做引擎名归一化后逐行 diff，量化重复度；
   声称"逐字节相同"的块做去缩进后二次比对。
3. **与 core 对照**：同名 SPI 实现逐方法对照；核验 core 是否已有可复用工具，避免把"重复"误判为
   "必须重复"。
4. **追到实现路径才下结论**：涉及"某段代码是否生效"的判断（`@Value` 是否被容器读取、
   `Binder.install` 是否存在、`resetExchangeMeta` 是否被调用、gzip 判定是否漂移）都追到
   core 的装配路径或**已发布 jar 的字节码**确认，不靠推断。
5. **构建验证**：`mvn -o clean test`（全量、非增量）、`mvn -o spotless:check`。

## 2. P0：clean 构建失败（已独立复现）

### 2.1 事实

```
[INFO] Freeway :: HTTP :: Jetty ....... SUCCESS  (22 tests)
[INFO] Freeway :: HTTP :: Undertow .... SUCCESS  (21 tests)
[INFO] Freeway :: MQ :: Kafka ......... SUCCESS  (37 tests, 4 skipped)
[INFO] Freeway :: DB :: HikariCP ...... SUCCESS  (12 tests)
[INFO] Freeway :: Benchmark ........... FAILURE
[ERROR] COMPILATION ERROR :
[ERROR] freeway-benchmark/.../bench/db/BenchDbModule.java:[35,11] 找不到符号
[ERROR] freeway-benchmark/.../http/engine/FilterChainBenchmark.java:[74,14] 无法将 HttpContextImpl 中的方法 reset 应用到给定类型
[ERROR] ... 共 9 处 reset 调用失败（FilterChain 3 处、ContextLookup 1 处、ContextOutput 5 处）
```

### 2.2 两个根因（均对 core 1.5.1 / 1.5.2-SNAPSHOT jar 做了字节码比对）

| 符号 | core 1.5.1 | core 1.5.2-SNAPSHOT | benchmark 现状 |
|---|---|---|---|
| `Binder.install(ModuleEx)` | 存在 | **已删除**（接口只剩 `bind`/`contribute`） | `BenchDbModule.java:35` 仍调用 |
| `HttpContextImpl.reset(...)` | 11 参（末位 `boolean http10, boolean keepAlive`） | **10 参**（`http10` 已删） | 9 处仍传 11 个实参 |

`Binder.install` 的删除在 core 源码里可直接看到（`freeway-ioc/.../Binder.java` 只有两个方法），
`reset` 的签名变化来自 core commit `1cc01a3`。

### 2.3 为什么之前没暴露

`freeway-benchmark/target/classes` 的时间戳停在 9 月 6 日，早于版本升级提交 `346b7c9`。
maven-compiler-plugin 的增量判定认为源码未变、无需重编，于是**用 1.5.1 时代编译出的 class
跑过了构建**。任何一次 `clean`、CI（全新 checkout）都会立刻失败——这也是为什么"本地绿、CI 红"
的差异会非常隐蔽。

### 2.4 修复建议

1. `BenchDbModule.java:35`：改用 1.5.2 的模块安装方式（`ModuleEx` 组合/`Binder` 现有 API），
   或确认 `install` 是否有替代入口。
2. 9 处 `reset(...)`：删掉末尾多出的 `true`（已删除的 `http10`），并把调用收敛到一个本地夹具
   方法（如 `ContextFixture.reset(ctx, method, path, ...)`），core 再改签名时只改一处。
3. 顺带修掉 §4.5 的 2.1/3.9：那 3 个"把 reset 写进测量体"的基准可以一并改成 `@Setup` 预置。
4. **流程建议**：CI 已用 `mvn verify`，但需要确认它跑的是 clean；`mvn test` 不能作为"能构建"的证据。

## 3. 跨模块发现

### 3.1 结构

**3.1.1 benchmark 的包组织三套命名，且 4 个包与 core 重叠（中）**

`freeway-benchmark` 同时使用 `com.jujin.freeway.bench.*`、`com.jujin.freeway.benchmarks.*` 与
`com.jujin.freeway.http.*`。第三套的四个包在 core `freeway-http` jar 中**已存在**
（`http.engine` 85 条目、`http.engine.ws` 9、`http.route` 10、`http.body` 9），构成跨构件
split package：JPMS 下非法，阅读时无法从包名判断类归属。8 个 JMH 类中只有 5 个**必须**留在
core 包（依赖包私有的 `HttpContextImpl.reset`、`Http1xParser`、`WebSocketFrame.read/write`），
`JsonCodecBenchmark`/`RouteIndexBenchmark`/`MultipartFormBenchmark` 只用了 public API，可立即
迁到 `com.jujin.freeway.benchmark.jmh.*`。§2 的编译失败正是白盒耦合的直接代价。

另外 `bench.cli` ↔ `benchmarks` 是**双向依赖**（`BenchFork` → `bench.cli.BenchRunner`，
`BenchRunner` → `benchmarks.ServerHarness/client.*`），说明这不是分层而是同一次迁移留下的两半。

> **（2026-09-13 已落地）** `benchmarks.*` 整个包并入 `bench.*`：`ServerHarness` →
> `bench.harness`、`Http11Client`/`WsClient` → `bench.client`、`Result` → `bench.model`（与
> `BenchmarkRun`/`BenchmarkResult` 同处结果模型）、`BenchFork` → `bench.run`，同时把
> `BenchRunner` 从 `bench.cli` 移到 `bench.run`——依赖因此变成
> `cli → run → harness/client → model` 单向，CLI 不再是"复用测量逻辑"的必经之路。
> 三个只用 public API 的 JMH 类迁到 `bench.jmh`（逐个核对：`RouteIndex` 是 public 类 + public
> 构造器 + public `RouteMatch`、`MultipartForm.parse` public、`JsonCodecDefault` public）；
> 其余 5 个留在 core 包，并在每个类的 javadoc 写明必须留下的包私有依赖
> （`HttpContextImpl.reset`、`Http1xParser`/`ParsedRequest`、`WebSocketFrame.read/write`），
> README 的 JMH 清单同步说明"这个模块不拥有任何 `com.jujin.freeway.http` 包"。
> `BenchFork.MAIN_CLASS` 由硬编码字符串改为 `BenchFork.class.getName()`，改包后再不会失配。
> 迁移顺带暴露一处包外访问：`BenchRunner.stddev` 原是包私有、被两个 CLI 命令使用，现转 public。
> 验证不是只看编译：JMH 两类实跑通过（迁移后的 `bench.jmh.JsonCodecBenchmark` 与白盒的
> `http.engine.Http1xParser`/`HttpContextOutput`），`BenchFork` 真实 fork 跑通一轮
> （500 请求 ok=500 errors=0）。

**3.1.2 超过 500 行的类**

`ServerHarness`（569）、`JettyWebEngine`（553）、`KafkaSubscriber`（520）。三者的共同形态是
"装配 + 每请求路径"同处一类；逐类判断见 §4，其中 `JettyWebEngine` 与 `ServerHarness` 的
职责溢出有明确的低成本改法。

### 3.2 一致性

**3.2.1 两个 HTTP 适配器对同一契约的行为不一致（中）**

| 场景 | Jetty | Undertow |
|---|---|---|
| WS 升级无匹配路由 | 自写 404 + `text/plain` + `"Not Found"`（`JettyWebEngine.java:315-320`） | `ResponseCodeHandler.HANDLE_404`（`UndertowWebEngine.java:342-345`），体/类型由库决定 |
| WS 升级被拒 | 自写 400 + `"WebSocket upgrade rejected"`（`:334-338`） | 交给 `Handlers.websocket(...)`（`:388-389`） |
| WS 端点 `open()` 抛异常 | `LOG.warn` + 500 + 专用文案（`:339-347`） | 上抛 → `LOG.error` + 通用 500 体（`:320-331`、`:379-386`） |
| 错误体常量 | 4 个 `byte[]`（`:61-67`） | 1 个 `String`（`:73`） |
| `X-Request-Id` | 两处字符串字面量（`:163-164`） | 定义了 `X_REQUEST_ID`（`:72`），读请求仍用字面量（`:337`） |
| 请求头上限 | 默认 8 KiB，与 core 一致 ✅ | 硬编码 64 KiB（`:173`），是 core 的 8 倍，注释却称"映射共享配置" |
| WS 消息上限默认 | 64 KiB（`:147`） | 64 KiB（`:144`），而 core 内置引擎硬编码 16 MiB |
| `readTimeout=0` | 不设超时，与 core"0 disables"一致 ✅ | 映射到 `REQUEST_PARSE_TIMEOUT=0`；Undertow 只对 `-1` 短路（`ParseTimeoutUpdater.handleSchedule` 字节码），0 会设置 `expireTime=now`，慢速/分片请求头会被断开 ⚠ |
| 每次启动的 WS 帧上限 | `start()` 内局部变量 | 引擎字段 `private volatile long wsMaxMessageSize`（`:79/143/346`），二次 start 残留 |

README 已文档化部分差异（max-frame-size 拒绝时机、`onError`/`onClose`），但**上表这些没有**。

**3.2.2 模块类命名与注册写法不统一（中低）**

| 模块 | 类名 | 修饰符 | 绑定 |
|---|---|---|---|
| jetty | `JettyWebEngineModule` | `public final` | `.id("jetty").primary()` |
| undertow | `UndertowWebEngineModule` | `public final` | `.id("undertow").primary()` |
| hikari | `HikariPoolModule` | `public final` | `.id("hikari").primary()` |
| kafka | `KafkaModule` | `public`（非 final） | 无 `id()`、无 `primary()` |

`KafkaModule` 的 RuntimeHook id `"kafka-sink"` 也没有 core 惯例的 `freeway.` 前缀
（core 用 `freeway.http.server`、`freeway.db.migration`）。模块 POM 的 `<description>` 有四种风格。

**3.2.3 契约测试粒度分叉（中）**

| 契约 | jetty | undertow |
|---|---|---|
| GET / HEAD | 拆成两个用例 | 合并为一个 |
| 分发到 worker | 无 | `echoBodyWorksWhenDispatchedToWorker`（Undertow 专属，合理） |
| 其余 5 项 | 有 | 有 |

`JettyTlsHttp2Test`（210 行）与 `UndertowTlsTest`（151 行）差异 83 行，覆盖不对称；测试类命名
也分叉（`JettyWebEngineContractTest` ↔ `UndertowHttpContractTest`、
`JettyWebSocketProbeTest` ↔ `UndertowFrameProbeTest`、`JettyTlsHttp2Test` ↔ `UndertowTlsTest`）。

**3.2.4 文档与实现漂移（中，跨模块）**

- `README.md:69-71` 称 `KafkaEventSink` 发出的 key 恒为 null，实际 `Keyed` 事件用 `key()`
  （`KafkaEventSink.java:121`，CHANGELOG 已记录该变更）。
- core `docs/application.properties.sample:395-397` 称 `freeway.kafka.topics` 决定出站 topic，
  实际 `KafkaEventSink` 不持有 config，出站 topic 由总线按事件类型推导。
- benchmark 侧 6 处（引擎能力表、引擎数、扩展清单、JdbcUrl 归属），见 §4.5。

### 3.3 简洁性

**3.3.1 两个 HTTP 适配器之间的重复：生产代码 ≈200 行（高）**

归一化引擎名后**去缩进逐字节相同**的块：

| 重复块 | jetty | undertow | 行数 |
|---|---|---|---|
| `systemProperties()` | `JettyWebEngine.java:95-116` | `UndertowWebEngine.java:102-123` | 22 |
| `splitCommaSeparated()` | `:284-290` | `:309-314` | 7 |
| `parseMaxFrameSize()`（含报错文案） | `:295-303` | `:299-307` | 9 |
| `safeCorrelationId()`（含 javadoc） | `:370-377` | `:409-416` | 8 |
| `snapshotPathVariables()` | `:384-386` | `:423-425` | 3 |
| `qValueIsZero()` | `JettyHttpContext.java:292-304` | `UndertowHttpContext.java:388-400` | 13 |
| `gzip(byte[])` | `:310-316` | `:407-413` | 7 |
| WebSocket 访问器整块 | `JettyWebSocketSession.java:78-149` | `UndertowWebSocketSession.java:190-261` | 72 |

仅"完全逐字节相同"部分即 **141 行 × 2 份**。近似重复还有 `acceptsGzip()`（18 行，仅取 header
表达式不同）、`compressibleContentType()`、`output(byte[])`（各 35 行，只差最后一处写出调用）、
`closeReason()`（10 行中 9 行相同）。子代理用 difflib 统计的整类重复率：
`HttpContext` 215/425、`WebSocketSession` 170/368、`WebEngine` 140/482。

**3.3.2 `systemProperties()` 跨三个模块重复（高）**

同一个 22 行匿名 `SymbolSource` 在 **jetty / undertow / hikari 三处逐字节相同**
（`JettyWebEngine.java:95-116`、`UndertowWebEngine.java:102-123`、`HikariPool.java:95-116`），
连 javadoc 和 `UnknownSymbolException` 分支都一致。`SymbolSource` 由 core 拥有，这本该是
core 提供的一个工厂（`SymbolSource.systemProperties()`）。

**3.3.3 重复块可以下沉 core，且不违反叶子节点约束**

`CLAUDE.md` 规定"四个适配器是叶子节点、互不依赖"，README 强调两个引擎模块互相独立。这与
"源码重复"是两件事：上述重复块全是**纯函数**（gzip、`Accept-Encoding` 解析、关联 ID 消毒、
快照、属性解析），不含引擎耦合；而两个适配器**本来就都依赖 core 的 `freeway-http`**。把它们
下沉 core：

- 不引入 adapter → adapter 依赖，叶子节点规则完好；
- 与 core 已有的 `MediaTypes`、`ResponseFraming`、`HttpUtils` 同层，符合 core `AGENTS.md` 的
  配置归属分层与"小而明确的 API"；
- 代价是一次 core 改动 + 版本推进（core 1.5.2-SNAPSHOT 开发中，时机合适）。
- 反例证据：这些副本**已经分叉**——`X-Request-Id` 常量用法、错误体类型、WS 帧上限持有方式、
  `ssl.enabled` 语义都是"本该一致而实际不一致"的实例。

**3.3.4 三段 gzip 协商逻辑已漂移（中高）**

core `HttpContextImpl.java:401-425`、`JettyHttpContext`、`UndertowHttpContext` 三份
`acceptsGzip/qValueIsZero`，而 core 版**遍历该头的所有值**并把 q 参数**先小写化**，两个适配器
只取第一个值、`"q"` 大小写敏感。于是 `Accept-Encoding: br` 与另一行 `gzip;q=0`、或 `Q=0`
这类输入下，适配器与内置引擎结论不同——而两处注释都写着"same semantics as the built-in engine"。

**3.3.5 测试重复 ≈450 行（中高）**

| 测试对 | 行数 | 归一化后差异行 |
|---|---|---|
| `JettyCompressionTest` ↔ `UndertowCompressionTest` | 169 / 169 | **0（完全复制）** |
| `JettyRemoteRpcTest` ↔ `UndertowRemoteRpcTest` | 156 / 156 | 4 |
| `JettyEngineConfigTest` ↔ `UndertowEngineConfigTest` | 103 / 119 | 20 |
| `JettyWebEngineContractTest` ↔ `UndertowHttpContractTest` | 262 / 274 | 158 |
| `JettyWebSocketProbeTest` ↔ `UndertowFrameProbeTest` | 305 / 299 | 228（引擎差异大，合理） |
| `JettyTlsHttp2Test` ↔ `UndertowTlsTest` | 210 / 151 | 83（覆盖不对称） |

core 已经发布 test-jar（`freeway-http-1.5.2-SNAPSHOT-tests.jar`，内含 `StubHttpContext` 等辅助），
把"引擎无关的契约"写成 core test-jar 里的抽象基类、两个适配器各自继承，是现成可行的路径。

## 4. 各模块详审

> 核验标记：✅ = 我独立复核过（读源码/字节码/跑构建）；⚠ = 源自模块深审、我未独立复核。

### 4.1 freeway-http-jetty

**结构**
- `JettyWebEngine` 553 行承担装配 + TLS/ALPN 栈 + 分发 + WS 桥；其中
  `JettyWebSocketBridge`（`:444-552`，111 行，`public static final`）是完整的独立传输桥，
  却嵌在引擎里且**全仓仅 `:324` 一处使用**，`public` 可见性没有理由（core `AGENTS.md`：
  "a type is public only when another package must assemble or substitute it"）。提到顶层包私有类
  可让引擎回落到 ≈440 行，并与 Undertow 的文件划分对齐。⚠（可见性是否被 Jetty 的反射查找需要，
  未实机验证，改后需跑 WS 用例）
- 死代码：`JettyHttpContext.responded()`（`:205-207`）与 `isResponded()` 是同一状态的重复访问器；
  `JettyWebSocketSession.completionCallback()`（`:229-231`）是"返回常量"的无意义包装。⚠

**一致性**
- ✅【高】`JettyHttpContext.reset()`（`:69-79`）未调用 core 的 `resetExchangeMeta()`，
  与 `UndertowHttpContext.reset()`（`:73-85`）同缺陷。core 的契约写得很明确：
  `AbstractHttpContext.java:68-73` + `ExchangeMetaDefault.reset()` 清 principal/attributes、
  滚动新 correlationId、刷新 startTime；`ExchangeMetaDefault.setCorrelationId` 对 null/blank
  **不生效**（`:56-61`）。后果：`ThreadLocal` 池化的 context 在线程内跨连接复用，
  上一请求的 principal/attributes 会被下一个请求读到，`startTime` 冻结（影响耗时统计），
  无 `X-Request-Id` 的请求会沿用上一请求的 id。
- ✅【高】`JettyWebEngine.java:215` 用 `Boolean.parseBoolean(resolve(SSL_ENABLED, "false"))`，
  丢掉 core 的三态语义（`HttpModuleConfig.activated`：显式 true/false 优先；未设则看 keystore
  是否存在；非法值报错点名键）。只配 `ssl.key-store` 时：core 的 `WebServer.secure()` 判为
  HTTPS（`WebServer.java:184-192` 的 javadoc 明说），适配器却起明文，且无告警。
- ✅【高】未实现 `isSecure()`/`sslSession()`/`remoteAddress()`（两个适配器 grep 均 0 命中）。
  `HttpRequest` 把三者定义为"transport bridges override it"（默认 false/null/""），core 内置
  引擎实现了（`HttpContextImpl.java:100-116`）。后果：TLS 下 `isSecure()==false`、
  `sslSession()==null`（Secure Cookie、重定向、mTLS 判断走错分支），
  `AccessLogFilter` 打的客户端 IP 恒为 `-`（`AccessLogFilter.java:37-38`）。Jetty 侧实现成本很低
  （`Request.isSecure()`、`Request.getRemoteAddr()`、`SslSessionData.ATTRIBUTE`）。
- 【中】`JettyHandle.close()`（`:427-440`）把 `InterruptedException` 一起吞掉且不恢复中断标志，
  而 Undertow 侧正确恢复了（`UndertowWebEngine.java:463-465`）。
- 【中】`:138-143` 注释称 `maxConnections` "rejects excess connections at accept time"，
  实际 `NetworkConnectionLimit` 是 `setAccepting(false)`（暂停接纳、留在 OS backlog），
  core 内置引擎是立即关闭 socket。⚠
- 【低】`setStatus` 缺 core 的 100–599 校验；`sse()` 缺 core 的"已提交即抛"保护；
  `writeTimeout`/`h2.reset-*` 被静默忽略且无注释（Undertow 至少写了注释）。
- 【中】`systemProperties/max-frame-size/http2` 等键以裸字面量出现，未提常量。

**简洁性**
- 【中】`JettyWebEngine.snapshotQueryParameters`（`:388-395`）与
  `JettyHttpContext.parseQueryParams`（`:318-325`）**逐字相同**；`method()/path()` 同样是两份实现。
- 【低】`JettyCompressionTest`/`JettyRemoteRpcTest` 与 Undertow 版本几乎逐字相同（见 §3.3.5）。

### 4.2 freeway-http-undertow

**结构**
- 【中】`UndertowHttpContext` 内嵌 86 行 SSE 写出器（`:186-276`，含队列/背压/`MAX_QUEUED_WRITES`），
  而 Jetty 的对应实现是 35 行匿名类（`JettyHttpContext.java:141-175`）。建议提为
  `UndertowSseOutputStream`。
- 【中】`wsMaxMessageSize` 作为引擎可变字段承载单次 start 的配置（`:79/143/346`），
  Jetty 用局部变量；测试只能反射读取该字段（`UndertowEngineConfigTest.java:90-94`）。
- ✅ 公共 API 面比 Jetty 更收敛（两个 context/session 类均包私有，未泄漏嵌套桥类型）。

**一致性**
- ✅【高】同 jetty 的 `resetExchangeMeta()` 缺失（`:73-85`）。
- ⚠【高】`readTimeout=0`：core 文档语义是"禁用"（`HttpConfigKeys.java:83-84`），适配器把它同时
  写入 `IDLE_TIMEOUT` 与 `REQUEST_PARSE_TIMEOUT`（`:170-172`）。我复核了
  `ParseTimeoutUpdater.handleSchedule(long)` 字节码：只有 `-1` 会短路（`expireTime = -1`），
  `0` 走正常路径设 `expireTime = now`，即被判定为"立刻过期"。触发条件是请求头跨 TCP 段到达
  （大 Cookie、慢链路、分片发送）。
- ✅【中】`MAX_HEADER_SIZE = 64 * 1024`（`:173`）是 core 内置引擎（`Http1xParser.java:22`
  的 8192）的 8 倍，而注释把它描述为"映射共享 Freeway 配置"。`SSL_ENABLED` 语义同 jetty 的 ✅【高】。
- ⚠【中】`UndertowWebSocketSession` 的超限检查（`:112-117`、`:138-140`）与 `rejectOversized`
  （`:354-367`）是死代码：Undertow 的 `BufferedTextMessage.checkMaxSize()` 在缓冲过程中就发
  1009 并抛 `IOException`，`onFullTextMessage` 永远收不到超限消息；`:354-358` 的注释与库行为相反。
- ⚠【中】TLS 自实现（`:232-285`）与 core `SslContextFactory` 分叉：缺 `key-alias`（Jetty 有）、
  SNI/reload（core 有），truststore 有路径无口令时不 fail-fast（core 会抛）。
- ⚠【中】`handle()`/`dispatch()` 的 IOException 重包装（`:334-359`）是无收益间接层。
- ⚠【低】`headers(String)` 返回可变 `ArrayList`（Jetty 返回 `List.copyOf`）；
  `responded` 非 volatile（Jetty 为 volatile）；`close()` 绕过 `sendLock`；
  `sse()` 不检查已提交；`setIoThreads(availableProcessors())` 与库默认 `max(cpu,2)` 不一致，
  且紧邻一条描述"已删除的 worker 池"的过期注释（`:164`、`:185-186`）。

### 4.3 freeway-mq-kafka

**结构**
- ⚠【中】`KafkaSubscriber` 520 行承担 7 块职责（客户端构造 116-144、生命周期 146-152/448-519、
  轮询提交 154-191、按 key 分桶并发 193-251、重试退避 253-306、poison/DLQ 308-363、
  线格式解析 365-446）；`running` 同时表示"循环存活/允许提交/停止请求"三种含义。
  建议拆 `KafkaHeaders`/`KafkaDlq`/`KafkaRecordProcessor`/`KafkaClients`。

**一致性**
- ✅【高】`KafkaConfig.of` 的 12 个 `@Value` 是**死注解**，且默认值声明三遍：
  `KafkaModule.java:44-57` 的 9 个裸 `resolve(键, 默认值)`、`KafkaConfig.java:60-71` 的 12 个
  `@Value("${键:默认值}")`、`KafkaModule.java:31-36` 的 3 个 `SymbolSpec`。
  我追到了 core 的装配路径确认：`ContainerImpl.constructInstance` 只走
  `BeanIntrospector.selectConstructor`（`ContainerImpl.java:485-489`），`BindingImpl.java:99`
  的 `.to(Class)` 也落到同一处——**静态工厂 `of(...)` 永远不在容器装配路径上**，而
  `KafkaModule` 用 provider lambda 显式调用它。因此改 `@Value` 里的默认值**不会有任何效果**。
  类 javadoc（`KafkaConfig.java:29-30`）"resolved from the config cascade via @Value" 与事实相反。
  建议照 core `HttpModuleConfig`（"Key declarations: name, type and default stated exactly once"
  + `from(SymbolSource)`）改造，删 `@Value`。
- ✅【中】`KafkaEventSink.java:131-135` 的 `producer.send(...)` 未包 try/catch，违反 core
  `EventSink` 的显式契约（"**send must not throw**"，`EventSink.java:13-17`）；
  `KafkaProducer.send` 会同步抛 `TimeoutException`/`IllegalStateException`/`SerializationException`。
  同一方法内的序列化失败分支（`:113-118`）却做了处理，态度自相矛盾。
- ✅【中】DLQ 与 poison-policy 的行为/文档矛盾：`handlePoison`（`:309-318`）在 DLQ 成功后
  **无条件 `return true`**，`:332` 的 `failOnPoison()` 分支在有 DLQ 时永远到不了；而
  `README.md:73-75` 明写"With a DLQ topic, they are moved to the DLQ first and the policy then
  decides whether processing continues (`skip`) or stops (`fail`)"。测试只覆盖了 skip+DLQ。
- ⚠【中】`suppress-own` 用 `Boolean.parseBoolean`（`KafkaModule.java:57`）：拼错即静默变 false
  = 静默关闭自身事件抑制；与同模块对 max-retries/poison-policy 的 fail-fast 立场、
  core `SymbolSpec.activated` 的严格判据都不一致。
- ⚠【中】wire header 字面量跨文件重复：写侧 `KafkaEventSink.java:125-130` 与读侧
  `KafkaSubscriber.java:383/394/402/408`（DLQ 3 个头 `:354/357/361`）各自持有同一批字符串，
  且写侧用 `channel.name()`、读侧硬编码 `"CLASS"`（`:397`）。今天取值一致（不是现存 bug），
  但任一侧改名即静默错投。
- ⚠【低】`Defer.within` 是空作用域（`:366-389`，内部只有一次 `publishInbound`），
  每条消息一次 `ScopedValue` 绑定 + `DeferScope` 分配的纯开销。
- 【低】`KafkaSubscriber` 内两处相同的逐条处理循环（`:197-210`、`:224-241`）可抽 `drain(...)`；
  `close()` 收尾重复；`Callable` 只为 `return null`。
- ⚠【低】`KafkaEventSink.java:107` 的 `event.getClass()` 无 null 守卫，而 core 允许 topic payload
  为 null（`EventBus.java:179`）；boot 事件靠包名前缀静默过滤，core 无对应机制。

### 4.4 freeway-db-hikari

模块最小（191 + 40 行），整体质量高：职责划分与 core `PoolDefault` 同构、公共 API 面最小
（厂商类型全部 private）、无需 RuntimeHook（容器统一关闭 `AutoCloseable`）、
`DatabaseStats` 8 个实参无错位、`close()` 幂等、重复 release 安全。
`longLeased` 恒 0（`:175`）是**未被文档化的语义分叉**（core 会真实计算）。

**对深审结论的一处修正**：模块深审把"`HkConn` 暴露的是 Hikari 代理连接，`connection().close()`
是归还而非销毁，导致 core 三处'销毁被污染连接'的安全路径失效（`BatchQueryImpl.java:191`、
`DatabaseImpl.java:220`）"列为高危。我核对了 HikariCP 7.1.0 的字节码后认为**实际影响被高估**：
`ProxyConnection.close()` 在回收前会执行 `if (isCommitStateDirty && !isAutoCommit) delegate.rollback()`
以及 `poolEntry.resetConnectionState(this, dirtyBits)`（重置 autoCommit/isolation/readOnly/catalog），
只有重置本身失败才走驱逐路径；`HikariDataSource.evictConnection(Connection)` 也确实存在。
所以"被污染的连接会回流并被下一个借用者取到"这一因果链不成立。**真正成立的是语义差异**：
core 的"物理销毁"意图在 Hikari 下变成"回滚 + 重置状态 + 回收"。建议不改适配器逻辑，而是：
① 在 core 增加 `Pool.invalidate(PooledConnection)` 让"销毁"可表达（Hikari 侧用 `evictConnection`）；
② 在此之前于类 javadoc 记录该差异。

其余中等项（均 ⚠ 未独立复核）：PoolConfig 时长被 Hikari 静默改写（`maxLifetime<30s→30min` 等，
而 core `PoolDefault` 严格执行）；`close()` 不做排空等待（core 是四阶段等待式关闭）；
测试全走 1 参构造器，`HikariPoolModule` 与级联路径零覆盖，畸形值分支无覆盖，
且存在恒真断言（`idle()>=0`、`maxSize()<=3`）与被吞的异常。
低项：provider lambda 可退化为 `.to(HikariPool.class)`；`hikariConfig` 字段可删
（`HikariDataSource extends HikariConfig`）；两个公开构造器缺 `Objects.requireNonNull`；
测试里通配 import 违反 CHANGELOG 声明的 explicit-imports 约定。

> **（2026-09-13 已落地）** ①、② 与本节中等/低项的处理见 §5 末尾的本轮更新说明；
> 唯一未采纳的是"provider lambda 退化为 `.to(HikariPool.class)`"——`HikariPool` 有两个公开
> 构造器，显式 lambda 才能点明容器路径要走 `SymbolSource` 级联。

### 4.5 freeway-benchmark

**P0 见 §2。**

**结构**
- ⚠【中】包组织为迁移遗留（`bench` 与 `benchmarks` 同源于 core 迁移 commit `c07c84a`，
  且双向依赖）；`BenchRunner` 放在 `cli` 使"复用测量逻辑"必须依赖 CLI 包。
- ⚠【高】`ServerHarness`（569 行）同时是引擎适配器集合、四套原生服务端实现、WS 回显、
  路由/过滤器装配与生命周期；加一个引擎要改 3 处、加一个场景要改 4 处。
- ⚠【中】`BenchFork`（290 行）的 `main` 承担 suite 编排、fork 生命周期、子进程入口、
  classpath 发现四种角色。
- ⚠【中】CLI 分层本身清晰，但 `CliModule` 用**类名字符串**派发（`:100`
  `getSimpleName().equalsIgnoreCase(commandName + "Command")`），重命名/挪包静默失配；
  静态 `container` 字段与 `dispatch(container, args)` 参数永远相同（纯冗余）。
- ⚠【中】JMH 与 CLI 完全不相通：JMH 结果不入 `bench_results`，没有 `jmh` 命令，
  而协议文档把 JMH 称为 decision-grade 输入。
- 【中】模块**没有 `src/test`**（其余 4 个模块都有），而对比算法、格式化、帧编解码、
  百分位等都是纯逻辑，属"本该有测试"。

**一致性**
- ⚠【高】`ServerHarness.bare()`（`:324-331`）用 `System.setProperty` 切换 JDK
  `HttpServerProvider`，而该 provider 在 JDK 里是**静态缓存**（javap：
  `getstatic provider; ifnull → 返回`，只在首次解析属性）。`SuiteCommand` 在同一 JVM 内遍历
  `--engines`，因此 `jdk-native,robaho-native` 同时出现时，第二个引擎会静默测成第一个引擎的数字
  并贴错标签。
- ⚠【中】`CompareCommand` 的回归检测**不影响退出码**（`:202-208` 只打印
  `⚠ REGRESSIONS DETECTED`，仍退出 0），CI 无法做门禁；且
  `(t.score()-f.score())/f.score()` 在基线为 0 时产生 ±Infinity/NaN，会把"全失败"报成 improvement。
- ⚠【中】5 个结果载体（`Result`、`BenchRunner.IterationResult`、`BenchmarkResult`、
  `SuiteCommand.SuiteResult`、`BenchmarkRun`）字段重叠；`scoreError/unit/mode/runs` **只写不读**；
  `BenchmarkResult.of(...)` 10 个位置实参夹魔数 `0, "req/s"`。
- ⚠【中】DB 访问三套风格并存（Orm 实体 API / 手写 SQL + `list(Class)` / 手写 SQL 拼接），
  `ListCommand` 同一条件分支出两个 `db.query` 调用。
- ⚠【中】`--output` 语义不一致（RunCommand 一律 JSON、SuiteCommand 一律 Markdown）；
  四套表格风格；`RunCommand` 是唯一不用 `BenchFormat.rps()` 的命令（`1200000` vs `1.20M`）；
  退出码无契约；用户输入错误与内部错误都打成 stack trace。
- ⚠【中】mode→Mode→Scenario 映射抄了三遍且默认值互不相同；`SuiteCommand` 重复实现了
  `ServerHarness` 的引擎白名单；`ServerHarness` 绕过 `WebServer.builder()`，
  因而缺 core 的默认 `ErrorHandler`（413/400 类场景的数字与真实应用不可比）、
  失去 `NOOP_SINK` 快速路径、并列的 noop 过滤器是多余的。
- ⚠【中】期望响应字面量在两个文件独立维护（`ServerHarness.java:132-133` 与
  `Http11Client.java:38-44`），不匹配时只表现为"引擎 100% 报错"。
- ⚠【中】8 个 JMH 类**没有任何策略注解**，协议文档声称的默认值（forks=2/1s/5+5/thrpt）
  无处落地，README 示例的 `-f 0` 又与协议矛盾。
- ⚠【中】文档漂移 6 处（WS 引擎范围、引擎数 4 vs 7、扩展场景漏 `jettyHandler()`、
  `BenchDbModule` javadoc 声称设置 JDBC URL 而实际由 `BenchApp` 设置等）。
- 【低】`toUpperCase()` 未指定 Locale（与同文件其它 `Locale.ROOT` 用法不一致）；
  `commitSha` 未判空；`toMap` 依赖无 `ORDER BY` 的返回顺序。

**简洁性**
- ⚠【高】`ServerHarness` 的 `freeway()`/`jettyAdapter()`/`undertowAdapter()`（`:185-209`、
  `:212-236`、`:239-263`）25 行 × 3 **完全重复**，只差 `new XxxEngine(...)` 一行。
- ⚠【高】`stddev + SELECT * 回查中位行 + UPDATE score_error` 在 `RunCommand.java:131-146` 与
  `SuiteCommand.java:160-173` 逐字重复；而 `orm.insert(result)` 本就返回
  `ExecuteResult.longKey()`（`RunCommand.java:88` 已在用），回查 + UPDATE 可以完全删掉。
- ⚠【中】3 个基准把 11 行 `reset(...)` 放进测量体内（`HttpContextOutputBenchmark.java:107/127/146`），
  与同类 `@Setup` 预置的写法自相矛盾（这 3 处也是 §2 的编译错误点）。
- 【低】死代码：`ListCommand`/`HistoryCommand` 未使用的 `coercer`/`orm` 局部变量、
  `Http11Client.sendPing()` 与单参构造器、`BenchRunner` 五参重载、
  `bench/event` 的 5 次 publish 无任何订阅者。

## 5. 合并优先修复清单

**P0（阻塞，先修）**
1. `BenchDbModule.java:35` `binder.install(...)` —— core 1.5.2 已删除该 API。✅
2. 9 处 `HttpContextImpl.reset(...)` 多传 `http10`；顺带把调用收敛到一个夹具方法。✅

**P1（高）**
3. 两个适配器的 `reset()` 补 `resetExchangeMeta()`（顺序：先清元数据再 `setCorrelationId`），
   并加"同线程第二个请求看不到上一请求 principal/attribute、startTime 被刷新"的回归测试。✅
4. 两个适配器的 `ssl.enabled` 改用 core 的 `SymbolSpec.activated` 三态语义（含非法值报错）。✅
5. 两个适配器补 `isSecure()`/`sslSession()`/`remoteAddress()`；契约测试加 TLS 断言。✅
6. kafka：删 `@Value`，键/类型/默认值改为单一声明（照 `HttpModuleConfig` 范式）。✅
7. kafka：`KafkaEventSink.send` 包 try/catch，遵守"不得抛出"契约。✅
8. kafka：DLQ 与 `poison-policy=fail` 的语义二选一（改代码或改 README），并补测试。✅
9. 抽掉两个 HTTP 适配器 ≈200 行重复（下沉 core：`SymbolSource.systemProperties()`、
   gzip/Accept-Encoding 判定、关联 ID 消毒、快照工具、`AbstractWebSocketSession`）。✅
10. benchmark：`ServerHarness` 三个装配方法合并；`bare()` 拒绝同 JVM 换 provider。⚠

> **状态更新（2026-09-13，随修复提交）**：上面的 ✅/⚠ 是审计当时的计划标记，不是实现状态。
> **已实现并提交**：1、2（`2eaef6b`、`92fcea5`）；3、4、5（jetty `3d1cffd`、undertow `47f8d09`）；
> 6、7、8（kafka）；10 的 `bare()` 守卫与三个装配方法合并（benchmark）。
> 其中 **9 经确认是有意形态**——两个适配器各自实现、各自自包含，不合并重复；因此 §6
> 「建议保留的有意差异」继续有效。此后又落了 P2 三项：Undertow `readTimeout=0` 的语义与
> `MAX_HEADER_SIZE` 对齐 core、Undertow 的 WS 死超限检查删除、Jetty `close()` 恢复中断标志、
> benchmark 的 `Locale.ROOT`；此后又落 benchmark 四项：场景载荷单点化（服务端响应与客户端期望同一常量）、
> 装配路径带上 core 默认 `ErrorHandler`、三个引擎装配方法合并、`compare` 查询补 `ORDER BY`
> （外加 `sendPing`/单参构造器/未用局部变量的死代码清理）；再一轮收敛结果载体：报告行复用
> `IterationResult`（错误数因此进入总表）、`forHttpIteration` 取代带魔数的 `of(...)`、
> 两个命令改用 insert 返回的主键 + 共享 `medianIndex`（删掉两份"stddev + 回查 + UPDATE"），
> 并补上 CLI 的端到端测试。剩余项见 §5 的 P2/P3。

**P2（中）**：模块类命名/注册统一（`KafkaModule` 的 `final`/`id`/`primary`/hook id）；
契约测试粒度与命名对齐；benchmark 包结构归并与结果模型收敛；DB 访问抽 repository；
JMH 策略注解与协议对齐；`ServerHarness` 改用 `WebServerBuilder`（已落地，见下）；
Undertow `MAX_HEADER_SIZE` 对齐 8192；Undertow 删除死超限检查；TLS 助手统一；
`JettyWebSocketBridge` 提为顶层；`JettyHandle.close()` 恢复中断标志；
hikari 配置保真度告警与 `close()` 语义声明；benchmark 补测试。

**P3（低）**：死代码清理（`responded()`、`completionCallback()`、未用局部变量与重载、
`bench/event` 或补订阅者）、`Locale.ROOT`、空值判空、`ORDER BY`、javadoc 措辞等。

**（2026-09-13 又一轮：hikari 语义差异落地）** §4.4 的三项建议已实现：core 新增
`Pool.invalidate(PooledConnection)`（`PoolDefault` 物理销毁 + 释放配额；`Database.transaction`
的状态复原与 `BatchQuery` 的 autoCommit 复原两条路径改走它），适配器用
`HikariDataSource.evictConnection` 实现销毁语义；`release` 对已 invalidate 的句柄变为空操作
——HikariCP 在条目被驱逐后关闭代理会在其 reset 路径抛 NPE，这一现象在测试里真实复现过，因此
这不是防御性代码而是契约要求。§4.4 的中项：时长改写/`cleanInterval` 不映射/`longLeased` 恒 0/
`close()` 不排空全部写入类 javadoc；测试从 13 例（含恒真断言与被吞异常）补到 19 例，其中
`HikariPoolModule` 的主绑定选择与 leak-detection 级联、非法值命名键、invalidate 的销毁/幂等、
以及 Database 端到端销毁路径此前零覆盖。低项一并清理：删掉冗余 `hikariConfig` 字段
（`HikariDataSource` 本身就是配置）、两个公开构造器补 `requireNonNull`、测试去掉通配 import。
同轮完成 §5 P2 的模块类命名/注册统一：`KafkaModule` 改 `public final`，hook id
`"kafka-sink"` → `KafkaModule.LIFECYCLE_HOOK = "freeway.kafka.lifecycle"`（对齐 core 的
`freeway.<module>.<thing>` 惯例），并在 javadoc 写明"适配器自有类型不标 `.primary()`"的理由。
剩余 P2/P3 不变。

**（2026-09-13 又一轮：benchmark CLI 契约统一）** §4.5 一致性里的 CLI 三项已实现：五套表格样式
收敛为 `BenchFormat.table(...)` 一处渲染（数字列右对齐、中位行加粗），速率统一走
`BenchFormat.rps()`（`17.3k`，不再是 `1200000`）；`Command` 增加显式 `name()`，分发不再用
`getSimpleName().equalsIgnoreCase(name + "Command")` 这种改名即静默失配的字符串匹配，
`CliModule.dispatch` 去掉与静态字段重复的 container 参数；用户输入错误（坏数字、未知
engine/scenario、非法 `--output` 扩展名）改为 `UsageException`：一行 `Error:` + 用法提示 + 退出码 1，
不再打 stack trace，内部错误仍保留 trace。`--output` 此前只写不校验——`run --output=report.md`
会把 JSON 写进 `.md`，现在按扩展名校验（`run`→`.json`、`suite`→`.md`）。顺带修掉一个真实缺陷：
`run` 原先先 insert 运行行、后解析 engine/scenario，用法错误会在库里留下半成品行，现在先校验再落库。
验证：五个命令实跑（`run`/`suite`/`list`/`history`/`compare` 输出与报告文件均为同一渲染器），
四类用法错误实跑确认无 stack trace 且退出码为 1，`BenchCliTest` 6 例覆盖命令名集合、表格渲染、
坏 flag 与 `--output` 扩展名。

**（2026-09-13 又一轮：ServerHarness 改走 WebServerBuilder）** §4.5 里"绕过 `WebServer.builder()`"
一项已落地：`freewayWith(...)` 改为 `WebServerBuilder.builder()` + `.engine(...)/.config(...)/
.cors(disabled)/.health(disabled)` + 逐条 `.route(...)/.webSocketGroup(...)`。收益有两条，都是
审计点名的：① 事件 sink 使用 builder 默认的 `NOOP_SINK` 哨兵，`WebServer` 因此
`publishEvents=false`，不再为"没人观察的服务器"每请求构造事件对象（此前传 `event -> {}`，
哨兵比较失败，快速路径失效）；② 默认错误映射由 builder 追加，harness 不再自己传
`ErrorHandlers.defaultHandler()`，与 `HttpModule` 的贡献语义一致（自定义在前、内置默认恒在末尾）。
CORS/health 仍显式关闭（scenario 不发 `Origin`、也不探测健康端点，开着就是每引擎都在测与场景无关的
每请求开销），并在代码注释里写明理由。顺带修掉 core 的一处文档错误：`WebServerBuilder` 的示例写的是
`WebServer.builder()`，而 core 并没有这个方法（core 提交 `593ac00c`）。
验证：三条装配路径实跑（freeway ping、freeway ws_echo、undertow-adapter ping 均 0 错误），
`ServerHarnessTest` 与 benchmark 全量测试通过。

## 6. 建议保留的有意差异

- Undertow 不映射 `maxConnections`（无对应能力，`:169` 已注释）；两个适配器都不映射
  `writeTimeout`（README:128 已文档化；建议 Jetty 侧补一行代码注释）。
- Jetty 独有 `freeway.http.http2`（h2c），Undertow 明确不启用 h2c。
- WS 帧超限的拒绝时机与 `onError`/`onClose` 差异（README:163-169 已文档化）。
- 适配器专属配置键：`freeway.http.http2`、`freeway.http.undertow.dispatch-io`、
  `freeway.http.ssl.key-password`/`key-alias`、`freeway.db.pool.leak-detection`。
- 两个引擎模块在**运行期**独立（一个应用只依赖其一）——这是 README 与 CLAUDE.md 的既定架构，
  与"源码重复"是两回事：共享工具下沉 core 不破坏它。

## 7. 核验状态说明

- ✅ 我独立复核（读源码/字节码/跑构建）：clean 构建失败与两个根因、`resetExchangeMeta` 缺失、
  `ssl.enabled` 语义、三个传输元数据方法缺失、gzip 判定漂移、`systemProperties` 三份重复、
  逐字节重复块与测试重复度、kafka `@Value` 死注解与默认值三处、`EventSink` 不抛契约、
  DLQ 策略矛盾、`MAX_HEADER_SIZE` 差异、benchmark split package、模块类命名不一致。
- ⚠ 源自模块深审、我未逐条复核（已在正文标注）：Undertow 死超限检查与 `readTimeout=0` 的完整
  触发链、TLS 分叉细节、Jetty `NetworkConnectionLimit` 语义、Hikari 时长改写与关闭语义、
  benchmark 的 JMH 策略/CLI 一致性各条。
- **一处对深审结论的修正**：hikari 的"污染连接回流"高危被降级为"语义差异"，理由见 §4.4。

## 8. 附：验证命令

```bash
# 真实构建状态（必须 clean，增量会掩盖 API 漂移）
mvn -o -B clean test

# 格式门（绑定在 verify 阶段，mvn test 不会触发）
mvn -o -B spotless:check

# 跨适配器重复度量化
diff <(sed 's/Jetty/ENGINE/g; s/jetty/engine/g' A.java) \
     <(sed 's/Undertow/ENGINE/g; s/undertow/engine/g' B.java)

# core API 表面比对
javap -p -cp ~/.m2/repository/com/jujin8/freeway/freeway-ioc/1.5.1/freeway-ioc-1.5.1.jar com.jujin.freeway.ioc.Binder
javap -p -cp ~/.m2/repository/com/jujin8/freeway/freeway-ioc/1.5.2-SNAPSHOT/freeway-ioc-1.5.2-SNAPSHOT.jar com.jujin.freeway.ioc.Binder
```

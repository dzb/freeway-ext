# freeway-ext 代码审计与 freeway-2（1.3.8-SNAPSHOT）全面适配报告

- 日期：2026-08-14
- 审计对象：`freeway-ext`（freeway-http-undertow / freeway-http-jetty / freeway-mq-kafka / freeway-db-hikari / freeway-benchmark）
- 适配目标：重构后的 Freeway core（`/Users/apple/Projects/freeway-2`，groupId `com.jujin8.freeway`，版本 `1.3.8-SNAPSHOT`）
- 结论：**编译全绿、测试全绿、基准 CLI 冒烟通过**；本文档列出审计发现、已完成的适配项与保留的有意差异。

## 1. 审计范围与方法

1. 以 `mvn install -DskipTests` 安装重构后的 core 到本地仓库（1.3.8-SNAPSHOT）。
2. 对 freeway-ext 执行 `mvn compile` / `mvn test`，确认编译与既有测试基线。
3. 逐模块比对 core 重构后的 SPI 与配置键：
   - `HttpEngine` / `HttpServerHandle` / `ExchangeHandler` / `HttpContext` / `HttpServerConfig` / `HttpConfigKeys`
   - `WebSocketSession` / `WebSocketMatch` / `WebSocketListener`
   - `EventBus` / `EventBridge` / `EventSubscriber` / `@Value` / `@Symbol`
   - `Pool` / `PoolConfig` / `DatabaseStats`
   - `WebServer` / `RequestComponents` / `ResponseFraming` / `MediaTypes`
4. 修复所有漂移点，补充测试，全量回归。

## 2. 总体结论

freeway-ext 在本次审计前已随 core 重构做过多轮对齐（git 历史中的
"align adapters with ..." 系列提交），因此**编译与既有测试均已通过**。
本次审计仍发现并修复了以下**语义/配置层面的漂移**，这些是编译期无法暴露的：

| # | 模块 | 问题 | 处理 |
|---|------|------|------|
| 1 | Jetty | HTTP/2 键为旧键 `freeway.http.http2`（默认 false），与重构后 core 的 `freeway.http.ssl.http2`（默认 **true**）不一致 | 改为共享键；`freeway.http.http2` 仅保留为 h2c（明文）开关，TLS 下忽略 |
| 2 | Undertow | 完全不支持 HTTP/2；TLS 配置只覆盖 key-store 子集 | 新增 ALPN h2（`ssl.http2` 默认 true）、`key-store-type`、`trust-store*`、`client-auth`、`protocols`、`ciphers` |
| 3 | 两者 | 硬编码超时（Undertow 60s/30s），忽略 `HttpServerConfig` 新增字段（`readTimeout`、`backlog`、缓冲、`maxConnections`、`compression`） | 映射到各服务器能力（详见 §3） |
| 4 | 两者 | 无 gzip 响应压缩，与 core 内置引擎行为不一致 | 在 `HttpContext.output(byte[])` 实现，复用 core `ResponseFraming.shouldGzip` 语义 |
| 5 | Kafka | `@Value` 不再支持 record component 目标（重构后仅 `PARAMETER`/`FIELD`） | `KafkaConfig` 迁移为在规范构造器参数上标注 `@Value`（未提交改动，本次复核确认） |
| 6 | Hikari | `healthCheckTimeout` 未映射 | 映射到 HikariCP `validationTimeout` |
| 7 | 工程 | `freeway-http-robaho/` 残留目录（仅 target/ 陈旧产物，引用已删除的 `Module2` SPI） | 删除目录；robaho `httpserver` 库保留为 benchmark 的 `robaho-native` 引擎依赖 |
| 8 | 文档 | README 属性表/示例、CHANGELOG 过期 | 已更新（§5） |

## 3. 各模块审计详情

### 3.1 freeway-http-undertow

适配完成项：

- **配置键对齐**：TLS 全部改用 core 的 `HttpConfigKeys` 常量（单一事实来源），新增：
  - `freeway.http.ssl.key-store-type`（未设置时按 `.jks` 推断，否则 PKCS12，与内置引擎默认一致）
  - `freeway.http.ssl.trust-store` / `trust-store-password` / `trust-store-type`（构建 TrustManagers）
  - `freeway.http.ssl.client-auth` → XNIO `Options.SSL_CLIENT_AUTH_MODE = REQUIRED`
  - `freeway.http.ssl.protocols` / `ciphers` → XNIO `SSL_ENABLED_PROTOCOLS` / `SSL_ENABLED_CIPHER_SUITES`
  - 保留扩展键 `freeway.http.ssl.key-password`（JKS 独立密钥口令）
- **HTTP/2**：TLS 下 `freeway.http.ssl.http2`（默认 true）→ `UndertowOptions.ENABLE_HTTP2`（JDK 9+ 自带 ALPN）。
- **HttpServerConfig 映射**：
  - `readTimeout` → `IDLE_TIMEOUT` + `REQUEST_PARSE_TIMEOUT`（取代硬编码 60s/30s；`0` 关闭）
  - `backlog` → XNIO `Options.BACKLOG`；`receiveBufferSize`/`sendBufferSize` → `Options.RECEIVE_BUFFER`/`SEND_BUFFER`
  - `maxConnections`/`writeTimeout`：Undertow 无对应能力，**有意忽略**（注释说明）
- **gzip 压缩**：在 `UndertowHttpContext.output(byte[])` 实现，复用 core
  `ResponseFraming.shouldGzip` 判定（status≠206、min-size、Accept-Encoding、可压缩 Content-Type），
  设置 `Content-Encoding: gzip` 与合并后的 `Vary: Accept-Encoding`，`Content-Length` 为压缩后长度。
  > 刻意**不**使用 Undertow 的 `EncodingHandler`：其动态编码无最小尺寸门限，会把小于
  > `min-size` 的响应也压缩，破坏共享配置语义（Jetty 同理不用 `GzipHandler`，两适配器行为一致）。
- 保留适配器专属键：`freeway.http.undertow.dispatch-io`、`freeway.http.websocket.max-frame-size`。

新增测试：`UndertowTlsTest.servesHttp2OverTlsByDefault`（默认 h2）、`UndertowCompressionTest`（4 例：
压缩大响应/最小尺寸门限/无 Accept-Encoding 不压缩/配置关闭）。

### 3.2 freeway-http-jetty

适配完成项：

- **HTTP/2 键对齐**：TLS 下读 `freeway.http.ssl.http2`（默认 true）走 ALPN（`SslConnectionFactory` +
  `ALPNServerConnectionFactory("h2","http/1.1")`）；`freeway.http.http2` 降级为仅明文 h2c，
  TLS 启用时忽略（README 已注明）。
- **TLS 选项补齐**：`key-store-type`、`trust-store`(+password/type)、`client-auth`→`setNeedClientAuth`、
  `protocols`/`ciphers`→`setIncludeProtocols`/`setIncludeCipherSuites`；保留扩展键
  `key-password`、`key-alias`。
- **HttpServerConfig 映射**：
  - `readTimeout` → `connector.setIdleTimeout(ms)`
  - `receiveBufferSize`/`sendBufferSize` → `setAcceptedReceiveBufferSize`/`setAcceptedSendBufferSize`
  - `maxConnections` → Jetty 12 `NetworkConnectionLimit`（接受时拒绝超限连接，对应内置引擎语义）
  - `writeTimeout`：Jetty 无每写超时，**有意忽略**（注释说明）
  - `compression`：上下文级 gzip，语义同 Undertow/内置引擎
- 保留适配器专属键：`freeway.http.websocket.max-frame-size`。

新增测试：`JettyTlsHttp2Test.servesHttp2OverTlsByDefault`（默认 h2）、`JettyCompressionTest`（4 例）。

### 3.3 freeway-mq-kafka

- `KafkaConfig`：重构后 core 的 `@Value` `@Target` 仅 `{PARAMETER, FIELD}`（原含
  `RECORD_COMPONENT`），因此把注解从 record component 迁移到规范构造器参数
  （`binder.bind(KafkaConfig.class).to(KafkaConfig.class)` 不变）。
- `KafkaEventBridge`：与重构后 `EventBridge`（`send(String topic, Object event)`）一致；
  序列化带 `X-Event-Type` 头、异步发送仅告警不抛出、`close()` 限时 10s —— 均保留。
- `KafkaSubscriber`：`bus.publish(topic, event)` 走字符串 topic 通道；`Defer.within` 包裹；
  allowlist 默认拒绝 typed 消息（CLAUDE.md 运营说明一致）；tombstone、DLQ、poison-policy、
  按 key 分桶并发等行为复核无漂移。
- `KafkaModule`：`RuntimeHook.start` 中 `EventBus.setEventBridge(...)` + `KafkaSubscriber.start()`
  与重构后 `EventBus` 生命周期一致（EventBus 在全部 lifecycle 回调之后才 close）。
  （注：`setEventBridge` 后来已随多 bridge 版 EventBus 移除，改为 `addEventBridge` /
  `removeEventBridge`；此处保留 1.3.8 当时的写法，仅作历史记录。）

### 3.4 freeway-db-hikari

- `Pool` SPI（`borrow/release/stats/close`）签名一致。
- 新增映射：`healthCheckTimeout` → HikariCP `setValidationTimeout`。
- 有意不映射（注释已说明）：`cleanInterval`（Hikari 自带 housekeeping）、`queryTimeout`（JDBC 语句级）。

### 3.5 freeway-benchmark

- `BenchApp`：`FreewayApp.of(...).autoDiscovery(false)` + 显式模块安装 —— 与重构后 boot/IoC API 一致。
- `ServerHarness`：直接构造 `HttpServerConfig` 与 `WebServer(engine, config, sink, pipeline)`，
  适配重构后的 `RequestComponents`（7 参）、`CorsFilter`、`HealthFilter`、`RouteIndex`。
- 实测 `bench list`：boot → IoC（4 modules）→ SQLite 建表/迁移 → CLI 分发 → 优雅关闭，全部通过。
- robaho `httpserver` 依赖保留（`robaho-native` 对照引擎）。

## 4. 保留的有意差异（不视为缺陷）

1. **Undertow `maxConnections` / 两适配器 `writeTimeout`**：底层服务器无对应能力，配置键被忽略
   （README 已列为 "ignored"）。
2. **Undertow h2c**：core 无明文 HTTP/2 开关，Undertow 适配器不启用 h2c；Jetty 保留 `freeway.http.http2` 作扩展。
3. **`freeway.http.websocket.max-frame-size`、`freeway.http.undertow.dispatch-io`、
   `freeway.http.ssl.key-password`/`key-alias`**：适配器扩展键，core 无同名键，保留并文档化。
4. **`output(InputStream)` / `outputFile` 路径**：适配器走默认缓冲实现，gzip 受 min-size 门限；
   内置引擎的流式/文件路径无尺寸门限。行为更保守，不视为缺陷。

## 5. 文档与工程变更

- `README.md`：适配器属性表重写（共享 `ssl.*` 键 + 新 TLS 选项 + `ssl.http2` 默认 true）；
  新增"Shared server settings honored by the adapters"小节；示例与 Jetty 版本号修正。
- `CHANGELOG.md`：1.3.8-SNAPSHOT 增补本次适配条目。
- `pom.xml`：快照仓库 URL 迁移到 `central.sonatype.com/repository/maven-snapshots/`（CI 解析
  core 快照所需，沿用既有未提交改动）。
- 删除 `freeway-http-robaho/`（无源码、无 POM 的陈旧构建产物，引用已删除的 `Module2` SPI）。

## 6. 验证结果

- `mvn compile`：全部模块通过。
- `mvn test`：6 个模块全绿 —— Jetty 16 例（含 TLS/h2 4 例、压缩 4 例、契约 6 例、WS 探针 2 例）、
  Undertow 14 例（含 TLS/h2 2 例、压缩 4 例、契约 6 例、帧探针 2 例）、Kafka 19 例、Hikari 11 例。
- `bench list` 冒烟：boot/IoC/DB/CLI 全链路通过。

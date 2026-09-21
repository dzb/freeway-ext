# Freeway Ext 1.5.3 Release Notes

> Version: 1.5.3 | Aligned with Freeway core 1.5.3 | Tag: `v1.5.3`

1.5.3 是一个 **breaking release**。适配器随 core 的 API 收敛一起迁移，编译错误即迁移路径。
从 1.5.0 起经过三轮适配（1.5.0 → 1.5.1 → 1.5.2/1.5.3），覆盖了核心框架的符号链收敛、
模块树值类型化、入口工厂统一、参数矩阵 record 化、Kafka 桥接修复、benchmark 全面重构。

## 模块依赖版本

| 模块 | Freeway Core | 第三方库 |
|------|-------------|---------|
| `freeway-http-jetty` | 1.5.3 | Jetty 12.1.13 |
| `freeway-http-undertow` | 1.5.3 | Undertow 2.4.3.Final |
| `freeway-mq-kafka` | 1.5.3 | Kafka Clients 4.3.1 |
| `freeway-db-hikari` | 1.5.3 | HikariCP 7.1.0 |
| `freeway-benchmark` | 1.5.3 | JMH 1.37, robaho 1.0.29 |
| 测试 | — | JUnit 6.1.3, SLF4J 2.0.19 |

---

## 1.5.3 — Core API 收敛适配

### Breaking Changes（适配 core 1.5.3）

- **`FreewayHttpEngine.Wiring` record** — 五个位置构造器替换为 `Wiring` record + `defaults()` +
  per-field withers（`withSsl`、`withSslParameters`、`withMetrics`），消除了相邻同类型参数可互换
  的隐患。`ServerHarness` 作为唯一调用点已适配。

- **`Orm.FindOptions`** — `findAll(Class, String orderBy, int limit, int offset)` 的位置哨兵收成
  `FindOptions` record。`""` 归一为无 ORDER BY，负值当场抛异常。`BenchRepository` 已适配。

- **`FreewayApp.of(...)` → `FreewayApp.create(...)`** — 框架入口统一用 `create`，`of` 留给值工厂。
  所有调用点已迁移。

- **`SymbolSource.systemProperties()` 删除** — 三个无容器适配器（Jetty、Undertow、HikariCP）
  改为显式构建共享链 `SymbolSource.of(coercer, SymbolProvider.systemProperties())`。
  独立装配与容器路径语义统一：`-D` 值中的未知 `${...}` 引用现在正确报错。

- **`SslContextFactory` → `SslContexts`** — 适配 core 的 TLS 工具类改名。

- **`HttpServerConfig` / `WebServer` 构造器收敛** — config 只剩 `defaults()` + per-field withers，
  `WebServer` 四参数构造器删除（硬编码 `secure = false` 的 TLS 测试 bug 修复）。适配器测试改走
  `WebServerBuilder` + `Pipelines`，与应用装配路径一致。

- **`CloudHttpClientDefault.Wiring` 第十个参数** — `shutdownGrace` 字段补齐，`RemoteRpcContract`
  已传入 `null`（取内置默认）。

- **`ErrorHandler` 接口** — core 的 `ErrorHandlers` 合并为单一 `ErrorHandler`，testkit 的
  `Pipelines` 已适配。

- **`CLAUDE.md` → `AGENTS.md`** — 仓库约定文件按 core 惯例重组。

### Kafka 桥接修复

- **bridge topic 对齐** — sink 写入配置的 bridge topic（而非 local dispatch topic），subscriber
  从同一 topic 轮询。新增 `X-Event-Topic` 头携带 local topic，跨 JVM 事件此前无法送达。

- **跨 JVM 契约测试** — `CrossJvmEventTest` 以独立 JVM（`CrossJvmRole`）验证 broker 级别的
  事件传递，每次运行创建独立 bridge topic。`KafkaEventSinkIntegrationTest` 改为双容器。

- **`allowed-event-types` 空列表启动告警** — 空列表接受 nothing（与"broker 沉默"不可区分），
  string-topic 事件需要 `java.lang.String` 在列表中。启动时警告。

- **module `final` + hook 命名空间** — `KafkaModule` 现为 `final`，runtime hook id 改为
  `"freeway.kafka.lifecycle"`（匹配 core 的 `freeway.<module>.<thing>` 模式）。

### Benchmark 全面重构

- **`ScenarioSpec` 单点声明** — 场景定义从四处散落收敛为一张表（method/path/content-type/body/
  echo/JSON/WebSocket flags）。添加场景只需一个 enum 常量 + 一个条目。修复了 Undertow echo 的
  两个真实缺陷：echo handler 在 I/O 线程做阻塞 I/O（UT000126），通用 handler 在 GET 场景读 body。

- **`BenchFork` 按角色拆分** — 从 291 行单文件拆为 `BenchFork`（93 行）、`ForkedRunner`、
  `BenchProcesses`。`BenchMode` 统一 `--mode` 映射，未知拼写报错。

- **`bench jmh` 命令** — JMH 微基准成绩写入与 HTTP 运行同一张表，`list`/`history`/`compare`
  统一读取。

- **`BenchRepository`** — 五条命令的 SQL 收敛到一个仓库类，`BenchRepositoryTest` 覆盖
  ordering、insert-order reads、dispersion、windowed join、baseline rule。

- **引擎错标守卫** — `bare()` 拒绝在同一 JVM 中测量两个裸引擎。`compare` 在报告回归时
  设 exit code 2。

- **CLI 统一格式** — `BenchFormat` 统一 Markdown 渲染、数字格式（`rps()`/`micros()`/`delta()`）。
  错误 flag → `UsageException`（exit code 1，无 stack trace）。

- **包结构归一** — `com.jujin.freeway.benchmarks.*` 删除，依赖单向流动。JMH 类按可见性分包。

- **事件消费** — `BenchEventListener` 消费 `RunStarted`/`ResultCollected`/`RunCompleted`。

- **JMH 协议默认值** — 八个 benchmark 类声明 `@BenchmarkMode(Throughput)`、`@Warmup(5×1s)`、
  `@Measurement(5×1s)`、`@Fork(2)`，与协议文档一致。

### HTTP 引擎适配器

- **TLS 统一读 `SslSettings`** — Undertow 删掉自写的 JSSE 构建（`KeyStore`/`KeyManagerFactory`/
  `SSLContext`），三个引擎加载 TLS 材料完全一致。

- **WebSocket 基类共享** — `AbstractWebSocketSession` 替换两个适配器的请求识别半层
  （correlation id、principal、attributes、headers），WebSocket session 缩减 47%。

- **`JettyWebSocketBridge` 提为顶层类** — 引擎文件从 568 行降到 427 行。

- **引擎契约测试收敛** — `freeway-http-adapter-testkit` 持有 `CompressionContract`、
  `ContextContract`、`RemoteRpcContract`，~994 行重复代码变为 86 行。

- **共享核心缝隙** — `Compression`（gzip 语义）、`AbstractWebSocketSession`（请求快照）、
  `SslSettings`（TLS 加载）三处共享，适配器只保留引擎特定行为。

- **Undertow `read-timeout=0`** — `0` 现在表示"无超时"（映射到 `-1`），header 预算对齐
  内置引擎的 8192。

- **Jetty `close()` 保持中断可见** — 不再吞掉 `InterruptedException`。

### HikariCP

- **`invalidate` 物理销毁** — `HikariDataSource.evictConnection` 移除并关闭物理连接，
  `release` 对已失效句柄变为 no-op。修复了"状态无法恢复的连接被回收"的问题。

- **PoolConfig divergence 记录** — HikariCP 重写低于其下限的 duration、忽略 `cleanInterval`、
  `longLeased` 恒为零、`close()` 不排空。

- **测试 13 → 19 例** — 覆盖 primary-vs-plain 池选择、leak-detection 符号级联。

---

## 1.5.1 — 配置级联 & 跨池保护

- **对齐 core 1.5.1** — 无适配器 API 变更（additive release）。

- **配置级联** — 两个 HTTP 引擎和 Hikari 池接受注入的 `SymbolSource`，`freeway.http.*` 和
  `freeway.db.pool.leak-detection` 现在尊重 CLI、JVM 属性、环境变量和文件的优先级。
  畸形的 `freeway.http.websocket.max-frame-size` 现在启动失败并命名 key。

- **HikariCP 跨池保护** — 释放属于另一个池的连接失败并抛出可操作的 `SqlException`。

- **惯用法现代化** — 显式导入、`Thread.ofPlatform` 线程工厂、命名 daemon Kafka worker、
  `Math.floorMod` key bucket。

---

## 1.5.0 — 符号规范迁移

- **对齐 core 1.5.0** — `freeway-mq-kafka` 配置类型从已删除的 `commons.config.ConfigSpec`
  迁移到 `ioc.symbol.SymbolSpec`（相同的 `of(key, type, default, parser)` / `key()` / `parse()`
  形状）。Core 的 engine/internal 可见性收窄和 Default/Impl 命名不影响适配器 API。

---

## 迁移指南

### 从 1.3.x 升级

1.5.x 是 breaking release 序列。主要迁移点：

| 旧 API | 新 API | 版本 |
|--------|--------|------|
| `FreewayApp.of(...)` | `FreewayApp.create(...)` | 1.5.3 |
| `FreewayHttpEngine` 多构造器 | `FreewayHttpEngine.Wiring` record | 1.5.3 |
| `Orm.findAll(Class, orderBy, limit, offset)` | `Orm.findAll(Class, FindOptions)` | 1.5.3 |
| `SymbolSource.systemProperties()` | `SymbolSource.of(coercer, SymbolProvider.systemProperties())` | 1.5.3 |
| `SslContextFactory` | `SslContexts` | 1.5.3 |
| `new HttpServerConfig(host, port, ...)` | `HttpServerConfig.defaults().withPort(...)` | 1.5.3 |
| `new WebServer(engine, config, sink, pipeline)` | `WebServerBuilder.builder()...build()` | 1.5.3 |
| `ConfigSpec` | `SymbolSpec` | 1.5.0 |
| `ModuleEx.subModules()` / `ModuleTree` | `ModuleNode` 值类型 | 1.5.2 |

### 注意事项

- Kafka bridge topic 必须与 subscriber 的 `freeway.kafka.topics` 一致。
- `freeway.kafka.allowed-event-types` 空列表 = 拒绝所有类型事件（包括 `java.lang.String`）。
- Undertow `read-timeout=0` 现在表示禁用（而非"已过期"）。
- HikariCP `invalidate` 后的 `release` 是 no-op（非错误）。
- Benchmark `--mode` 未知值报错（不再静默回退到 `keepalive`）。

---

## 测试

24 tests green，`spotless:check` clean。

```
mvn test                    # 全模块测试
mvn spotless:check          # 格式门禁
mvn -pl freeway-http-jetty test   # 单模块
```

---

## 已知限制

- 六个适配器测试类仍手写 `new WebServer(...)`（引擎特定 fixture：TLS、transport limits、raw WS
  probe），尚未迁移到 `WebServerBuilder`。已记录在审计中。
- `KafkaEventSinkIntegrationTest` 的断言可被同 JVM 同步 local dispatch 满足，需要第二条 bus 才是
  真正的 wire test。
- Benchmark 模块不发布到 Maven Central（`skipPublishing` + `maven.deploy.skip`）。

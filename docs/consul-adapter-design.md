# freeway-ext：Consul 服务发现适配设计（草案）

- 日期：2026-10-08
- 对象：新增模块 `freeway-cloud-consul`（`freeway-ext`）
- 目的：为 core 的 `ServiceRegistry` / `ServiceDiscovery` 提供一个 Consul 实现，
  与必做的 K8s 适配并列，覆盖 **非 K8s 生产环境**（VM / 裸金属 / 多云）
- 判据来源：core `freeway-cloud` 现有接缝的**源码事实**（下述引用均已核对）、
  `freeway-ext/AGENTS.md`、core `AGENTS.md`（依赖纪律、shape 规则、配置键规则）

## 0. 结论速览

**K8s（必做）+ Consul（第二）。** 选 Consul 的理由是运维事实而非名气：

1. **无外部存储**——Consul 服务端自带 Raft + 本地 BoltDB，生产不需要数据库；这是与
   Nacos（生产要 MySQL）最实质的差别，直接减少一层运维。
2. **模型同构**——四个方法与 Consul 的注册/健康/注销/查询几乎 1:1。
3. **adapter 零新依赖**——纯 JDK `HttpClient` 即可写完，符合 core 的依赖纪律，
   本地起一个进程即可端到端测。
4. **覆盖 K8s 覆盖不到的残余场景**——VM / 裸金属 / 多云。

本文只写这一版的最小可用设计，明确列出**不做**的部分。

## 1. 目标 / 非目标

**目标**

- 提供 `ServiceRegistry` + `ServiceDiscovery` 的 Consul 实现，使
  `freeway.cloud.registry.type=consul` / `freeway.cloud.discovery.type=consul` 生效。
- 复用 core 既有 seam：不新增接口、不改 core。

**非目标（明确不做，见 §7）**

- Consul KV / Config（freeway 自带配置级联，重叠不做）
- Consul Connect / mTLS
- 多 datacenter 联邦
- 通过 `SecretStore`（Vault）取令牌

## 2. 现有接缝（core，已核实）

| seam | 位置 | 说明 |
|---|---|---|
| `ServiceRegistry` | `cloud.discovery` | `register(ServiceInstance)` / `boolean renew(serviceId, instanceId)` / `unregister(ServiceInstance)` / `default Duration drainWindow()` |
| `ServiceDiscovery` | `cloud.discovery` | `List<ServiceInstance> instances(serviceId)` |
| `ServiceInstance` | `cloud.discovery` | `record(serviceId, instanceId, Endpoint endpoint, Map<String,String> metadata)` |
| `Endpoint` | `cloud.discovery` | `record(scheme, host, port, basePath)`，`uri()` 渲染 `scheme://host:port+basePath` |
| 后端选择键 | `CloudModule.ConfigKeys` | `freeway.cloud.registry.type` / `freeway.cloud.discovery.type`；`local` = 内建 |
| 替换方式 | `@Local` + `.primary()` | 内建默认带 `.marker(Local.class)`；适配用 `.primary()` 覆盖 |
| 失效告警 | `BackendTypeGuard.warnIfExternal` | 配了外部 type 但本地仍 active → 启动 WARN；适配 primary 后自动静默 |
| 生命周期 | `RegistryLifecycleHook` | 启动后注册 `ServiceDeclaration`；每 10s `renew`；停止 `unregister`；`renew==false` 时**重注册** |
| 健康 | `RegistryRenewal` | `renew` 连续失败 3 次 → `/health/ready` 转 unhealthy |
| 排空 | `drainWindow()` | `freeway.cloud.registry.shutdown-drain=auto` 时问适配要传播窗口 |

**关键推论**：适配只需实现两个接口 + 一个模块 + 一个 `Wiring`。
**生命周期 / 健康 / 重注册 / 排空全部由 core 既有代码驱动，适配不重复实现。**

## 3. 接口 → Consul HTTP API 映射

| freeway | Consul HTTP API |
|---|---|
| `register(instance)` | `PUT /v1/agent/service/register`，body 见下 |
| `renew(sid, iid)` | `PUT /v1/agent/check/pass/{sid}:{iid}` → 2xx=true；404/其它=false |
| `unregister(instance)` | `PUT /v1/agent/service/deregister/{sid}:{iid}` |
| `instances(sid)` | `GET /v1/health/service/{sid}?passing=true` |
| `drainWindow()` | 返回 `freeway.cloud.consul.drain-window`（默认 `PT5S`） |

`register` 的 body 形状：

```json
{
  "ID":   "{serviceId}:{instanceId}",
  "Name": "{serviceId}",
  "Address": "{endpoint.host}",
  "Port":    "{endpoint.port}",
  "Meta":  { "scheme": "...", "base-path": "...", ...instance.metadata },
  "Check": { "TTL": "15s", "DeregisterCriticalServiceAfter": "1m" }
}
```

映射要点：

- **instanceId ↔ Consul `Service.ID`**：用 `{serviceId}:{instanceId}`（Consul 要求 ID 在
  agent 内唯一）。`Name` = `serviceId`。
- **`renew` 语义对齐**：core 要"registry 已不认识就返回 false"。TTL check 被清除 / 实例被
  deregister 后 `check/pass` 返回 404 → false，正好触发 core 的**重注册**逻辑。
- **`instances` 只返回健康实例**：`?passing=true` 与 `ServiceDiscovery.instances` 的
  "live and ready" 契约一致。
- **`metadata`**：`ServiceInstance.metadata` ↔ Consul `Service.Meta`（字符串 map，天然对齐）。
- **`Endpoint.scheme` / `basePath` 落 `Meta`**（见 §4，这是保真要求，非可选）。

## 4. `scheme` / `basePath` 为何必须保留（代码事实）

```java
// CloudHttpClientDefault:425 —— 真实出站 URL 就是它拼的
String url = instance.endpoint().uri() + request.path();

// Endpoint:71
URI.create(scheme + "://" + host + ":" + port + basePath)
```

- `scheme` 还决定 mesh 的 ws/wss（`CloudEventLifecycleHook:102`）
- `basePath` 是服务挂在路径前缀下时的前缀

Consul 原生只有 `Address` + `Port`。**丢掉 `scheme`/`basePath` 不是简化，是静默出错**：
https 服务会被 http 调用、路径前缀服务会 404。

**做法（保持简约）**：写入 Consul `Meta` 的 `scheme` / `base-path` 两个键；
**当 `http` 且 basePath 为空时直接省略**；读回时缺省即 `http` / `""`。
普通服务 payload 因此与"丢掉"方案完全一致干净，少数服务不被弄坏。

## 5. 组件设计

```
freeway-cloud-consul/
├─ ConsulModule            ModuleEx：bind(ServiceRegistry/Discovery).primary()
│                                    + 贡献 KnownKeys.of(ConfigKeys.class, PREFIX)
│                                    + 无效配置启动期 fail-loud
├─ ConsulWiring            record：agentHost/agentPort/scheme/token/ttl/drainWindow/timeout
│                                    defaults() + per-field withX()
├─ ConsulClient            薄 HTTP 客户端：仅 JDK HttpClient
│                                    register / renew / unregister / instances
├─ ConsulServiceRegistry   implements ServiceRegistry
├─ ConsulServiceDiscovery  implements ServiceDiscovery
└─ ConfigKeys              嵌套类，全字面量键
```

- **`ConsulWiring`**：3+ 个可选输入 → 参数 record + `defaults()` + per-field wither
  （符合 core 的 shape 规则），不写重载阶梯。
- **`ConsulClient`**：纯 `java.net.http.HttpClient`，零第三方依赖；集成测试可用本地
  consul 或 JDK `HttpServer` stub。
- **`ConsulModule`**：只做三件事——`.primary()` 绑定、贡献词表、启动期 fail-loud。
- **不重复** core 的生命周期/健康/排空——它们读的是接口，适配 primary 后自动生效。

## 6. 配置键（遵循 core 规则）

`ConfigKeys` 嵌在 `ConsulModule`，全字面量、`PREFIX = "freeway.cloud.consul"`，
并 `contribute(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX))`：

| 键 | 默认 | 说明 |
|---|---|---|
| `freeway.cloud.consul.agent-host` | `127.0.0.1` | Consul agent 地址 |
| `freeway.cloud.consul.agent-port` | `8500` | HTTP 端口 |
| `freeway.cloud.consul.scheme` | `http` | `http` / `https` |
| `freeway.cloud.consul.token` | *(空)* | ACL 令牌；空则不发送（见 §7） |
| `freeway.cloud.consul.ttl` | `15s` | 健康 TTL；应 > renew 间隔（10s） |
| `freeway.cloud.consul.drain-window` | `PT5S` | 注销传播窗口 |

启用开关沿用 core 已定的 `freeway.cloud.registry.type=consul` /
`freeway.cloud.discovery.type=consul`。

## 7. 明确不做（附理由）

| 不做 | 理由 |
|---|---|
| `SecretStore` 取令牌 | 会让**发现平面依赖密钥平面**（启动顺序耦合，且 `SecretStore` 自身后端尚缺）。令牌走 `token` 键 + env 注入，不落明文文件，符合部署惯例。留作以后可选项 |
| mTLS / Connect 客户端证书 | 那是 service mesh 的职责，不是发现适配的职责。`https` + 可选 token 足够 |
| 自建 TLS 配置键 | 私有 CA 用 JVM 默认 truststore（`-Djavax.net.ssl.trustStore=...`），不新增概念 |
| datacenter 可配 | 只在多 DC 联邦下有意义的罕见场景，应交给 Consul 自身 federation/DNS |
| `passing` 可配 | `passing=true` 是 `ServiceDiscovery.instances` 的**契约**（"live and ready"），不是旋钮；返回不健康实例属违约 |

## 8. 测试策略

| 层 | 方式 |
|---|---|
| 单元 | JDK `com.sun.net.httpserver.HttpServer` **stub Consul agent**，断言请求路径/body，以及 `renew` 404→false、`instances` 解析（含 `Meta` 缺省回退 `http`/`""`） |
| 契约 | "注册→发现→续约→注销→发现为空"闭环；本地起真 consul 二进制（无则 `assumeTrue` 跳过，与 ext 现有 Kafka 测试的门控一致） |
| 集成 | `FreewayApp` + `ConsulModule.primary()`，断言 `isActiveBinding(ServiceRegistry, Local.class)==false`、`BackendTypeGuard` 不再告警、`/health/ready` 反映 renew 结果 |

## 9. 落地阶段

1. `ConsulWiring` + `ConsulClient`（HTTP 薄封装）+ 单测（stub）
2. `ConsulServiceRegistry` / `ConsulServiceDiscovery` + 映射单测
3. `ConsulModule`（primary 绑定 + 键表 + fail-loud）
4. 端到端（真 consul 门控）+ `/health/ready` 与 `drainWindow` 行为测试

## 10. 开放问题

（收敛后无。若实现中遇到 TTL 下限、`DeregisterCriticalServiceAfter` 取值等细节，在
实现阶段按 Consul 文档定值，不改本设计的对外形状。）

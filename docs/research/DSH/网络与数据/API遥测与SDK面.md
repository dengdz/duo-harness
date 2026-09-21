# API/遥测/SDK 面（DSH）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 22（T-26）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；传输层（WS mux/journal 流）见 [../Agent循环与会话/投影、恢复与分页.md](../Agent循环与会话/投影、恢复与分页.md)。

## 机制全貌

DSH 对外能力面无传统 REST，而是四层：①**远程面**：业务服务继承 `TypertRemoteService` 并以 `@Remote` 标注方法（session-controller/src/index.ts:87,121 namespace='session'），经 `TypertGatewayService` 拦截 Connection 的 `/api` RPC 通道做 unary 分发、流方法走 WS mux（api/gateway/src/index.ts:198-229）；②**协议面**：typert generator 构建期扫描生成严格描述符/codec，注册进双端共享的 `TypertRegistry`（typert/registry/src/service.ts:447），无生成物时 Gateway 退回 SRC 运行时反射（gateway/src/index.ts:639）；③**SDK 面**：TS（packages/sdk/client）与 Python（python/sdk）双 SDK 以 JSON-RPC over stdio 驱动 runtime 子进程，高层封装 `run` 到下一次 idle；④**遥测面**：session-telemetry 订阅 session firehose 逐事件经 redaction waterfall 交后端，OTel 包做 OTLP 导出；session-stats 另做 token/耗时投影。

- 端点=`<namespace>/<method>`，无 HTTP verb 语义（registry/src/service.ts:68）。
- webhook 是第四种入口：规则命中即建 `webhook-<uuid>` 会话（webhook/webhook/src/session.ts:132）。

## 关键流程

① **session-controller**：`list/search` 冷读不激活 Agent（index.ts:223,234）；`create` 幂等采纳；`prompt/selectModel/rename/cancel/updateQueue` 均先显式 resume 再执行；`fork` 从冷读完成轮前缀派生（:335）；`page` 冷安全、消息对齐翻页（:388）；`follow`/`control` 为 stream 模式（:400,410）。follow 先 yield 完整 snapshot+cursor，再经 buffered 队列追事件，保证 durable 事件 gap-free（history.ts:119-219）。鉴权：WS upgrade 经 `connection.requestRejection`——先 trustedHost 围栏 403、再 browserAuth 进程令牌/cookie 401（client/connection/src/rpc-host.ts:96-100）。

② **Typert**：`@Remote` 装饰器经 class field initializer 在原型上写 version:1 marker 描述符（protocol/src/index.ts:286-314）；Gateway 调用时按 descriptor 解析参数（json/lookup 两源）、Context receiver 解析、`signal` 恒为末参注入（index.ts:597-624）。错误跨线：`RemoteError` 仅携 `{code,message,details}`，**判定一律按 code 不用 instanceof**（protocol/src/remote-error.ts:12-44）；Gateway 外异常折叠为 `gateway/internal`（index.ts:993-1006）。

③ **SDK**：`DeepSeekHarness.start()` 记忆化 initialize 握手，失败回收子进程并换新实例重试（sdk/client/src/api.ts:69-95）；`HarnessSession.run` 先 `subscribeSessionTree`（按 subagent.started 血缘追后代，client.ts:370-381,417-424），prompt 后等本 message 的 inbox 回执直至 `session.status=idle`，返回 events+notifications+finalResponse（:210-224）。子进程生命周期：spawn→JSON-RPC LineTransport（client.ts:214-268），close 走 shutdown→stdin EOF→SIGTERM→SIGKILL 阶梯（client.ts:389-410；python 同构 client.py:94-129）。

④ **遥测**：coordinator 区分 live/on-demand，`handoffCursor`（WeakMap by Session）标记已交付 seq，重采纳续传不重放（session-telemetry/src/coordinator.ts:58,151-163）；逐事件过 `session-telemetry/record` waterfall，规则抛错则该记录 fail-closed 扣留（:154,202-204）；OTel 后端将记录映射 OTLP log 导出，exporter.url 必填且限 http(s)（session-telemetry-otel/src/index.ts:177-189,222）。成本可见化在 session-stats：折叠 outputTokens、llm/tool/ttft/decode 墙钟（session-stats/src/projection.ts:26-49,106-159）。

## 接口与参数要点

- Remote 清单：list/search/create/selectModel/modelCatalog/canOpenWorkspacePath/openWorkspacePath/rename/fork/prompt/attachment/updateQueue/cancel + stream: follow/control。
- @Remote：必须 public 实例方法、字符串名；`{mode:'stream'}` 二选一；重复标记冲突即抛（protocol/src/index.ts:273-274,305）。
- args 严格对账：多余字段、缺失必填即 `gateway/arguments-invalid`（gateway/index.ts:1107-1133）。
- SDK API：`DeepSeekHarness.run/session/start/close`、`HarnessSession.run`；Python 对应 `start_session().run(on_notification=)`。

## 边界与坑

- 非 JSON 值全线拒收：非有限数、循环、稀疏/带符号数组、非 plain object 均在边界校验拦截（gateway/index.ts:1159-1192）；`undefined` 字段与缺字段语义不同（:815-819）。
- stream 方法禁止走 unary 通道、反之亦然（:300-305,323-328）。
- SDK 无线上 cancel：超时=放弃，服务端继续跑到 close（sdk/client/src/client.ts:181-183）；相对 cwd 会双重解析，必须绝对化（api.ts:39-41）。
- 遥测 handoff cursor 是模块级 ambient 状态，仅进程内生命周期（coordinator.ts:49-56）；telemetry 扳机异常被 contain，不饿死后续订阅者（:239-249）。

## 对 duo 的启示

1. **@Remote 式服务暴露**：duo 的 /api/answer 为手写结构化协议（M16）。Typert 的「装饰器标注→构建期生成 descriptor→双端 registry 对账→Gateway 严格 args/JSON 校验」可参考为 duo /api 演进的契约冻结路径——尤其「端点=namespace/method 对账 + 缺字段/多字段即拒」可低成本移植到 answer 协议版本化；**错误按 code 而非 instanceof 跨线判定**也值得照搬。
2. **SDK 面成本收益**：DSH 为 SDK 维持了 TS/Python 双实现+同一 wire 协议，成本高；duo 无对外 SDK，若 M16 协议已冻结，先出单一语言薄客户端即可，双端等价是长期维护税。
3. **遥测投影**：DSH 把成本可见化拆成「事件级 telemetry 导出（OTLP）+ session-stats 统计投影」两件事，且 redaction 走可挂载 waterfall、fail-closed；duo 的 SSE 游标（M10）已具备 follow 面基础，补一个 session 级 usage/耗时投影单元（纯 fold、可持久缓存）即可低成本获得成本面板。

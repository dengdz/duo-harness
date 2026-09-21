# API/遥测/SDK 面（ZCode）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑工单 22（T-26）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；连接鉴权见 [../网络与数据/网络与远程工具族.md](../网络与数据/网络与远程工具族.md)。

## 机制全貌

ZCode 有三层对外面 + 一层协议语义。① Channel RPC 服务面：`ServiceCollection.exposeOnChannelServer`（packages/services/src/collection.ts:30-41）把注册服务批量经 `ProxyChannel.fromService` 暴露为 channel，复用于三种传输：HTTP/WS（packages/server/src/http.ts:117）、desktop MessagePort（packages/desktop/src/host/index.ts:2024）、远程桥接。② CLI protocol server：`runZCodeProtocolAgent`（apps/zcode-cli/packages/bootstrap/src/zcode-protocol-entrypoint.ts）走 JSON 协议，方法清单在 packages/shared/src/zcode-protocol/index.ts:3560-3664。③ OTel 遥测面：apps/zcode-cli/packages/telemetry 的 span 工厂/状态 sink/metrics/OTLP 导出。④ v4 协议（packages/shared/src/zcode-protocol-v4）在此之上定义 API 语义：`v4/*` 请求 + topic frame 投影订阅 + command inbox admission（v4-gateway.ts:1-8 注明职责边界）。

## 关键流程

① **Channel RPC**：`createHttpServer`（http.ts:295-343）——`/api/server-info`、`/api/rpc-host-capability`（签发一次性 capability）、`/ws`=web-remote-replayable（terminal-client）、`/ws/host`=desktop-continuous，须带 `x-zcode-rpc-host-capability` 且一次性消费（http.ts:336-343；channels.ts:498）；token 保护 `/ws`+`/api/*`（http.ts:306-314）。远程桥接仅暴露 file/git/system/terminal 四族（http.ts:387-393）；ProviderProvisioningTarget 对非 trusted host 替换为 throw stub（http.ts:107-116）。desktop host 侧以 overrides 注入 attachment 语义：window-controller/media-preview/task/zcode-agent/conversation-share（host/index.ts:1988-2024）。服务族清单见 ServiceChannels（channels.ts:74-149）：file/terminal/git/credential/oauth/zcode-agent/zcode-session/window-controller/usage-stats 等 40+。

② **protocol server**：session/create|resume|read|messages|events|debug|subscribe|compact|goal 等 + workspace/plugins/skills/workflows/automation/offPeak/interaction 族；sessionSend/Stop/Fork 已 deprecated 收敛 v4（index.ts:3573-3585）。v4 方法全集（transport.ts）：「v4/command」、v4/commands/query、v4/conversation/*（subscribe/rowsRange/fileChanges/usage/workflowRuns…）、v4/attachment/*、v4/controller/*、v4/telemetry/event、v4/usage/stats。command inbox（command-inbox.ts:1-75）做 admission：revision+epoch CAS、guard/row-target 裁决，in-flight/live-input pinned、settled 进 512/session LRU。

③ **OTel**：8 类 span 工厂（agent-trace-runtime.ts:174/210/249/299/352/412/469/541）：agent_turn、agent_step、tool_execution、context_compaction、detached_operation、model_call、command_execution、model_attempt；属性前缀 `zcode.<span>.*`，生命周期键 outcome/error_category/failure_stage/abandon_reason（:1509-1521）。model-api-recorder（model-api-recorder.ts:46-185）消费 Transport 状态事件直写活跃 Call/Attempt span：providerId/kind/origin/route、requestedModel、reasoning 五态（:309-326）、attempt/maxAttempts/transport/retryDelayMs；完成时记 **usage 五类 token**（input/output/reasoning/cacheRead/cacheWrite，:230-246）、providerRequestId/finishReason/httpStatusCode/providerError*/retryAfterMs/streamOutputCommitted；三段首事件时延（first_provider_event/content/text）与 stream stalled。**不记请求/响应正文**（:42-45）。metrics（agent-metrics.ts:56-95）：8 个 duration histogram、model.call.attempts、model.attempt.tokens（按 token_type）、time_to_first_*、stream_stall.count 等。OTLP（otlp-exporter.ts:53-156）：HTTP proto+GZIP、BatchSpanProcessor(100/2000/5s)、ParentBased+ratio 0.1 采样（:32,158-162）、metric DELTA+300s 周期、直方图显式桶+基数上限 250（:164-207）。

④ **debug 调试台**（packages/debug/server/sources.ts:11-15）数据源= `~/.zcode/cli/log` 结构化 JSONL、Session 事件 JSONL、`~/.zcode/cli/db/db.sqlite`（node:sqlite），不直接消费 OTel。

## 接口与参数要点

- env（bootstrap.ts:75-137,158-174,414-416）：`OTEL_EXPORTER_OTLP_ENDPOINT`（自动补 `/v1/traces|/v1/metrics`）、`_TRACES_ENDPOINT`/`_METRICS_ENDPOINT`、`OTEL_EXPORTER_OTLP*_HEADERS`、`OTEL_SERVICE_NAME`（默认 zcode-cli-agent）、`ZCODE_MODEL_TELEMETRY_ENABLED`（0/false/off/disabled 显式禁用）、`ZCODE_TELEMETRY_DEVICE_MID`（状态文件 `~/.zcode/v2/telemetry-state.json`）、`ZCODE_ENV`。

## 边界与坑

- 禁用路径不 import SDK：otlp-exporter 仅在确认启用后动态 `await import`（otlp-exporter.ts:49-52），`--help` 零 SDK 成本。
- 身份文件读写失败只丢匿名关联、不阻塞主链路（bootstrap.ts:257-261），锁 5min stale 回收。
- 遥测 sink 全旁路：publishObserved 捕获一切同步异常防阻断 Provider fan-out（model-api-recorder.ts:73-88）。
- 错误脱敏：message 4KB 截断+URL 清洗、链深 8；WeakSet claim 保证错误正文只记在最近的认领 span（error-sanitizer.ts:16-52）。
- metric 基数防护：标签白名单、DELTA、metric resource 剔除 installationId（otlp-exporter.ts:218-225）。
- 鉴权坑：`/ws` 忽略旧 client-mode header 不可自提升 trusted host（channels.ts:495-496、http.ts:320-321）。

## 对 duo 的启示

1. **OTel 全套成本偏高**（1674 行 trace runtime+采样/白名单/基数防护）；若 duo 做，先落 **model-attempt span + tokens/TTFT 指标**即可——model-api-recorder 的字段集（五类 token、三段首事件时延、retry/finishReason、providerError 脱敏、不记正文）正是 duo usage 日志的增强模板，且「写活跃 span、不建终态 Record」避免导出期重建。
2. **服务暴露面**：一个 ServiceCollection+overrides 复用于 MessagePort/WS/remote 三传输，鉴权差异用 clientMode+一次性 capability 而非多套 server——duo 多端演进可套用。
3. **v4 command inbox**（CAS admission、pinned/LRU 分离、投影订阅 rowsRange）是 `/api/answer` 从请求-响应走向多端订阅/断线重放的成熟参照。

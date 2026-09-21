# MCP（ZCode）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑工单 14（T-18）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

McpPort（`contracts/src/interfaces/mcp.port.ts:290-311`）定义 connectConfiguredServers/connectServer/disconnectServer/pingServer?/status/listTools/callTool/close。装配在 bootstrap：`packages/bootstrap/src/zcode-protocol-entrypoint.ts:227-243` 用 `createMcpAdapterConnectionPool` 建进程级池，再 `acquireLease({leaseId:"protocol-settings"})` 得到 mcpPort（features.mcp=false 时为 undefined）。adapter 层（`adapters/src/mcp/index.ts:157-174`）= createMcpConnectionPool + 每连接一个 NodeMcpAdapter。工具经 core 桥接为模型工具：descriptor 归一（`descriptor.ts:18`）→ `mcp__{server}__{tool}` 命名（`core/src/mcp/name.ts:3-12`），`core/src/mcp/index.ts:102-197` 把 descriptor 包成 ToolEntry（needsApproval=true、annotations 定 riskLevel、默认 sideEffectScope=network、resultBudget 截断、description 透传进模型输入）。OAuth 凭据经 shared credential store 复用。

## 关键流程

① **连接池**（`pool.ts`）：`acquireLease`(:162) 产自增 leaseId；connectionKey=serverName+scope+stableStringify(config)（:441-453），scope 默认 leaseId（session 隔离），仅声明 isolation:"workspace" 的无状态 server 用 workspaceIdentity/workingDirectory 跨 session 复用。refs 为 leaseId 集合，release 后 refs=0 则 scheduleClose（:92-103）：30s 空闲宽限（DEFAULT_IDLE_GRACE_MS，:16，timer unref）。revalidate 置位时 pingServer（5s 超时，`index.ts:95`）验活、死则原 entry 原地重连保持 lease 不打断（:111-160）。connectConfiguredServers 并行 acquire、未配置项自动 release（:347-365）。

② **stdio**：`stdio-transport.ts:45` ProcessTreeStdioClientTransport 继承 SDK StdioClientTransport；覆写 `_dispose`（:155-174）：先 terminate Windows Job Object→`terminateMcpStdioProcessTree(pid)`→SDK 原生 dispose。start 时 win32 把子进程挂入 Job Object（JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE，`windows-job-object.ts:4,144`，kernel32 FFI；失败回退 taskkill）；posix 用进程组/子进程枚举杀树（`process-tree.ts:33-43`）。

③ **OAuth**：authorization_code（browser+localhost callback：state=randomBytes(24)、PKCE verifier 仅内存，`oauth-interactive.ts:121-178,254`）与 client_credentials 两形态（`mcp.port.ts:25-42`）。凭据键前缀=`mcp:oauth:`+sha256(serverName/serverUrl/clientId/scope/redirectPath) 前 24 hex（`oauth.ts:16-35`）；canonical 记录带 generation/published_by，近过期刷新（`oauth-credentials.ts:62-67,185`）。运行期认证错误经 classifyInteractiveAuthorizationTrigger 触发 Phase2→Phase1 重授权自愈（`index.ts:481-496`，仅非 stdio）。

④ **工具桥接与调用**：callTool 超时=options.timeoutMs ?? config.timeoutMs ?? 30s（`index.ts:93,443-445`），deadline 约束 await connecting/重连；"Not connected" 竞态重连重试一次（:497-523）；isError 结果附 `zcode/officialMcpServerRequestId` 到 _meta（`mcp.port.ts:159`）。server 配置三形态 stdio(command/args/cwd/env)/http(url/headers/oauth)/sse（`mcp.port.ts:74-102`），多 server 并存、配置变更即换 key 隔离。

## 接口与参数要点

- 池：acquireLease({leaseId,sessionId})→McpPort；stats()={activeConnections,pendingCloseConnections}。
- OAuth：authorizationTimeoutMs 单独限授权等待（`mcp.port.ts:203-206`）；openAuthorizationUrl/onAuthorizationRequired 可注入。
- 工具命名：非 `[a-zA-Z0-9_-]` 折叠为 `_`；inputSchema 强制 type:"object"（`descriptor.ts:30-44`）。
- McpCallToolRequest 带 runtimeScope:"main"|"subagent" 与 workspaceIdentity 隔离键（`mcp.port.ts:226-229`）。

## 边界与坑

- SDK 2.0 版本探测会克隆一次性兄弟 stdio 进程，其私有回收只杀直接子进程→launcher/watchdog 后代孤儿化，故劫持 `_dispose` 杀全树+Job Object 兜底（`stdio-transport.ts:39-44`）。
- HTTP/SSE 被停不派发 onclose，status 会假 connected，必须显式 ping（`mcp.port.ts:301-306`）。
- 未 lease 的 server 调用直接报 "MCP server is not leased by this session"（`pool.ts:174`）。
- SDK version negotiation 重包错误使 instanceof 失效→抛出点写 lastOfficialAuthKind 槽位而非解析错误文本（`index.ts:201-206`）；OAuth 键含 clientId/scope/redirectPath，任一变更即要求重新授权（`oauth.ts:21`）。

## 对 duo 的启示

1. **连接池/lease 形态**：duo 可借鉴 connectionKey（server+isolation scope+config 指纹）+引用计数+空闲 30s 宽限，Java 侧用 scope 管理 lease，避免每 session 全量重启 stdio 子进程。
2. **进程树兜底跨平台**：SDK 只杀直接子进程的问题在 Java MCP SDK 同样存在，可参照 Job Object(KILL_ON_JOB_CLOSE)/posix 进程组双路兜底（duo 0.16.0 已做 stdin EOF 自退+shutdown hook，可再对照）。
3. **运行期 OAuth 自愈与凭据键设计**：授权语义变化（scope/clientId）即换键重授权、401 触发交互式重授权而非报错退出，对 duo 后续接 OAuth MCP server 有直接价值。

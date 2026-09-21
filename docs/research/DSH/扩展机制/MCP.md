# MCP（DSH）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 14（T-18）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

每个 mcp-client 插件实例连一个 MCP server：config（stdio：command/args/env/cwd；或 streamable-http：url/headers）→ `createTransport`（transport.ts:31）→ SDK `Client.connect` → 工具同步进 `ctx.tools`，命名 `mcp__<serverName>__<rawName>`（tools.ts:82）；server instructions 以 `### MCP server: <name>` 注入 system prompt（connection.ts:319）。多 server = 加载多实例。要点：每实例一个连接监督器（connection.ts:127），管生成代际、重连、工具同步串行队列。

## 关键流程

① **连接初始化**：`apply` 先 fail-loud 解析 reconnect 策略（index.ts:158）、按 scope 预留 serverName 命名空间（index.ts:162-176），再 `startConnection`；stdio 子进程由 SDK spawn，env 经 `scrubbedParentEnv` 清洗后合并 config.env（transport.ts:21-23）。initialize 握手后取 instructions（超 32KiB 抛错，connection.ts:320），`listTools`（capability 无 tools 则空表，tools.ts:121）构建 ToolDefinition；`apply` await `connection.ready` 阻塞激活，`failOnStartupError`（默认 false）决定失败是否回滚 fiber（index.ts:199-202）。

② **工具调用**：入参透传（非对象 args 回落 `{}` 让 server 报缺参错，tools.ts:286）；`{signal: exec.signal, timeout: toolCallTimeoutMs}`（默认 60s，index.ts:37）；结果经 `CallToolResult` schema 校验，`isError` → throw(text)（tools.ts:297-299）；text 换行拼接、image 走持久 attachment（模型须声明 image 输入，否则降级占位文本）、audio/resource/resource_link 占位（tools.ts:476-505）；structuredContent 保留进 McpResult。

③ **resources**：mcp-resources 提供 `list_mcp_resources`/`list_mcp_resource_templates`/`read_mcp_resource` 三个共享工具（tools.ts:34-60），按调用方 agent scope 解析 provider（index.ts:122-125），转发到当前连接代际的 listResources/listResourceTemplates/readResource，同用 toolCallTimeoutMs（connection.ts:366-387）；渲染时 blob base64 替换为占位（render.ts:17-23）；system prompt 列出可用 server 名（index.ts:59-71）。

④ **重连失败**：指数退避 `min(maxDelayMs, initial*2^(n-1))`（connection.ts:236）；存活超 maxDelayMs（稳定窗）重置预算（connection.ts:222）；maxAttempts（默认 10）耗尽→**注销全部工具并停止**；reconnect.enabled=false 只报错不重试；失败代际若 5s 内无法确认 transport 关闭则停止重连防子进程重叠（connection.ts:54,188-197）。

⑤ **关闭**：dispose 停 timer→关 transport（5s 屏障）→await 在途 attempt+sync 队列→统一注销工具（connection.ts:388-407）。

## 接口与参数要点

- config 全表：stdio{transport,serverName,command,args,env,cwd,toolCallTimeoutMs=60000,failOnStartupError=false,maxInstructionBytes=32768,reconnect}；streamable-http 以 url/headers 替代 command 组（index.ts:119-142）。
- reconnect{enabled=true,initialDelayMs=500,maxDelayMs=30000,maxAttempts=10}（connection.ts:41-46）。
- serverName 必须 `^[A-Za-z0-9_-]{1,32}$` 且 scope 内唯一（index.ts:40,169）；agent-scoped 实例可跨 Agent 复用同名（index.ts:42-47）。公名 ≤64 字符。

## 边界与坑

- reconnect 误配在加载期抛（含绕过 schema 的编程构造，connection.ts:69-94）。
- 公名规范化有损（非法字符/超 64）时追加 12 位 SHA-256 哈希防坍缩（tools.ts:81-87）；server 列表内重名 raw tool→fetch 阶段失败，旧代际不动（tools.ts:126-130）；注册冲突视为外部抢占命名空间→整代回滚（tools.ts:145-160）。
- 能力协商：仅注册 server 宣告的能力（tools 无 capability→不注册任何工具）；resources provider 无条件注册，无 capability 门控。
- 工具表变更经 SDK listChanged 回调触发重同步，所有同步串行（syncChain）防代际交错（connection.ts:168-177）；taskRequired 工具直接抛不支持（tools.ts:280）。

## 对 duo 的启示

1. **工具名冲突**：duo `McpToolSync.publicToolName`（McpToolSync.java:267-271）仅字符替换，无 64 上限截断与哈希后缀——server 返回超长/重名工具时会撞 DeepSeek 契约或互相坍缩，DSH 的有损规范化+哈希值得移植。
2. **重连语义**：DSH「一次故障一份预算+稳定窗重置+耗尽注销工具+无法确认子进程关闭即停」与 duo 的 ConnectionSupervisor/孤儿防护同向，可对照补「耗尽后仅 reload 可恢复」的终态语义与 fail-loud 策略校验。
3. **resources 桥**：DSH 用 3 个共享工具+scope 化 provider 替代为每个 resource 建工具，成本低；duo 若暂无需求可缓做，但 system-prompt 列 server 名的引导模式值得借鉴。

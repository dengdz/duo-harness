# config.yml 全量字段参考

duo-harness 的配置分布在**两个文件 + 两份独立配置**里——先分清「什么配在哪」，再按表查字段：

| 你要配的东西 | 在哪 |
|---|---|
| 模型与 LLM 行为 | `~/.duo/config.yml` 的 `llm` 段（本文 §llm） |
| 装配哪些能力、各插件的参数 | 装配 yml（如 `agent-demo.yml`）的 `plugins:` 行内 `config` 段（本文 §插件段索引） |
| hooks 脚本 | `~/.duo/hooks.json`（独立文件，见[ hooks 指南](../02-指南/hooks.md)） |
| 权限规则 | 项目根 `.duo/settings.json` 的 `permissions` 段（见[权限与审批](../02-指南/权限与审批.md)） |

注意：**没有**顶层 `mcp` / `governance` / `bind` 聚合段——mcp 是每插件行 config、governance 是 web/cli 行内子段、Web 监听地址固定本机回环不可配。

## llm 段（`~/.duo/config.yml`）

唯一顶层段。环境变量 `DUO_LLM_BASE_URL` / `DUO_LLM_API_KEY` / `DUO_LLM_MODEL` 逐项覆盖文件值（env 优先）；文件不存在但 env 三项齐全也能跑，关键项最终缺失则启动报错并给重配指引。

| 字段 | 类型 | 缺省 | 说明 |
|---|---|---|---|
| `baseUrl` | string | 必填 | provider 地址，如 `https://api.deepseek.com` |
| `apiKey` | string | 必填* | 凭证（*env 可代）；**永不入仓库** |
| `model` | string | 必填* | 模型名，如 `deepseek-chat` |
| `systemPrompt` | string | 无 | 行为指令——作为 prompt 注册表的最前用户片段 |
| `retryMaxAttempts` | int | 3 | 重试总尝试次数（含首次） |
| `retryInitialBackoffMs` | long | 1000 | 首次重试退避毫秒，×2 递增（网络故障与 429/5xx 指数退避） |
| `streamIdleTimeoutMs` | long | 90000 | 流式空闲超时毫秒（连续无新字节即中止；首字节前超时自动重试，已输出后中止并保留已生成文本） |
| `vision` | bool | false | 视觉开关——false 时收图与读图均被闸门拒绝（非视觉部署零感知） |
| `imageDelivery` | string | `inline` | 图片投递：`inline`（base64 data URI）\| `files`（DeepSeek Files API 换 file_id，省大图传输，仅 DeepSeek 端点） |
| `provider` | string | `openai-compat` | 适配器选型：`openai-compat` \| `anthropic` \| `deepseek` \| `glm`——决定鉴权头、流式解析与思考档位映射 |
| `models` | string[] | 空 | `/model` 可切白名单；空 = 不可切 |
| `effort` | string | `medium` | 思考档位 `off/low/medium/high`（/effort 运行时切换） |

## 插件段索引（装配 yml 的 `plugins:` 行内）

每行 `id / name / config`；各段逐字段注解见[插件配置参考](插件配置参考.md)——本表只做「哪段有什么」的地图，不复制细节：

| 装配行 | config 关键字段（缺省） | 详注 |
|---|---|---|
| `web` | `port`(8080；覆盖：sysprop `duo.web.port` > env `DUO_WEB_PORT` > 本值，M37 工单 01)、`pageSize`(50)、`auth`(token)、`maxIterations`(10)、`maxParallelToolCalls`、`pipelineTimeoutMs`、`governance` 子段 | [插件配置参考](插件配置参考.md) |
| `cli` | `maxIterations`(10)、`maxParallelToolCalls`、`pipelineTimeoutMs`、`governance` 子段 | 同上 |
| `web`/`cli` 的 `governance` | `spillThresholdChars`、`pruneThresholdChars`、`compactionThresholdRatio`、`contextWindowTokens`、`minRemoteMessages`、`microcompactEnabled`(true)、`microcompactKeepRecent`——段与字段均可省略即内置缺省；microcompact 触发阈值由压缩阈值派生不可独立配 | 同上 §governance |
| `fs-tools` | `mode`(workspace-write)、`root`(进程 cwd)、`output{inlineTailChars, spillMaxChars, taskOutputTailChars}` | 同上 |
| `web-tools` | `timeoutMs`、`maxResponseBytes`、`maxBodyChars`、`maxOutputChars`、`maxRedirects`、`userAgent`、`search{type, apiKey, apiKeyEnv, baseUrl, maxResults}` | 同上 |
| `attachment` | 图片准入上限组（`maxImageBytes`、`maxImagesPerMessage`、`maxImagePixels`、`maxImageDimension`、`normalizedImage*` 等 8 项） | 同上 |
| `session-query` | `sessionsDir`（省略用 duo home 的 agent-sessions）、`maxResults`(8) | 同上 |
| `prompts` | `systemPrompt` | 同上 |
| `agents-md` | `budgetChars`(65536) | 同上 |
| `memory` | `budgetChars`(16384) | 同上 |
| `skills` | `disabled`（技能名数组） | 同上 |
| `mcp`（一行一服务器） | `serverName`(必填，1~32 位限定字符)、`command`(必填)、`args`、`env`、`failOnStartupError`(false)、`reconnectEnabled`(true)、`reconnect{initialDelayMs:500, maxDelayMs:30000, maxAttempts:10}`、`stableWindowMs`(30000，不可配)、`requestTimeoutMs`(20000) | [MCP 接入](../02-指南/MCP接入.md)、[MCP 深入](../03-高级/MCP深入.md) |
| `subagent` | `templates[]{name, tools, prompt, maxIterations}`（未知字段 fail-fast） | [子代理](../02-指南/子代理.md) |
| `repeat-reminder` | `thresholds`（升序数组，省略即 [3, 5, 8]） | 同上 |
| `hooks` / `commands` / `answers` / `tool-stats` / `approval` / `permission-rules` | 无 config 块（部分要求空块 `config: {}` 在场——声明了 config 类型的行必须带） | [插件配置参考](插件配置参考.md) |

装配行本身的四字段（`id / name / config / disabled`）与行序契约见[插件配置参考 §通用约定](插件配置参考.md)。

## 深读

- 逐行注解与装配示例：[插件配置参考](插件配置参考.md)
- 配置严格绑定语义（错字段启动即 FAILED 点名）：[架构·模块划分](../04-架构/模块划分.md)

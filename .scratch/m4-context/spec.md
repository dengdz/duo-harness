# M4 Spec：上下文（会话事件溯源）

> ADR-0007 渐进序列第二站。M3 打通了"单轮问答"，M4 给它装上记忆——多轮对话有上下文，进程重启能继续，`~/.duo/sessions` 的每条记录可回放。

## Problem Statement

使用者与 ChatRepl 聊天时，每一轮都是独立的：上一句说过什么模型完全不知道（"我刚才说叫什么名字"它答不上来），进程重启后对话全部丢失。作为想用 harness 搭 agent 的开发者，没有会话记忆的 LLM 调用只是 API 套壳，无法支撑任何真实任务。

## Solution

引入会话域（新模块 `duo-harness-session`）：对话以**事件溯源**方式记录——`append()` 是唯一写入原语，事件逐条同步落 JSONL 文件（`~/.duo/sessions/<id>.jsonl`）；多轮记忆由日志**投影**派生；REPL 启动时自动继续最近的会话，`/new` 开新话题。 ChatRepl 升级后：同一进程内多轮对话有记忆；重启进程可继续上次会话。

## User Stories

1. As a harness 使用者, I want 每轮对话自动记录为会话事件, so that 对话历史不丢失且可回放
2. As a harness 使用者, I want 多轮对话时模型记得之前说过的内容, so that 追问和指代能被正确理解
3. As a harness 使用者, I want 重启进程后能继续上次的会话, so that 长任务不被进程生命周期打断
4. As a harness 使用者, I want 用 `/new` 开启新会话, so that 不同话题的上下文互不污染
5. As a harness 使用者, I want 会话文件以人类可读的名字存放在 `~/.duo/sessions`, so that 我能直接找到并查看原始记录
6. As a harness 使用者, I want 流式增量也记录在案, so that 会话日志与真实交互过程一一对应（审计/回放）
7. As a agent 循环开发者（M5）, I want 会话以 append 单写原语暴露, so that 循环、工具、压缩等能力都基于同一份不可破坏的日志
8. As a Web 面开发者（M8）, I want 会话事件可被逐条订阅消费, so that 对话界面能实时渲染事件流
9. As a 治理链使用者, I want 会话投影只产出对话消息（流式细节不进请求）, so that 发给 LLM 的请求形态与 provider 协议对齐
10. As a 框架维护者, I want session 模块不依赖任何功能模块, so that 事件词汇保持中立且演进不牵动消费方

## Implementation Decisions

- **新模块 `duo-harness-session`**（依赖仅 core + Jackson）：中立数据基座；llm/tools/agent/Web 都是消费方，它不依赖任何功能模块（遵循 DSH"llm 不依赖 session"先例）。
- **事件建模**：单一 record `SessionEvent(type, at, text)`——M4 三种事件载荷同构（`user/message`、`assistant/chunk`、`assistant/message`，各带 epoch millis 时间戳 `at`）；M5 `tool/call` 载荷不同构时演进为 sealed 接口，JSONL 按 `type` 判别让演进不破坏旧文件。
- **Session API**：`id()` / `events()` 只读视图 / `append(event)` 唯一写入原语（内存追加 + JSONL **同步追加落盘**）/ `deriveMessages()` 投影。
- **投影规则**：`user/message` → USER 消息、`assistant/message` → ASSISTANT 消息；`assistant/chunk` 是流式细节不投影。
- **会话身份在文件名**：`~/.duo/sessions/<yyyyMMdd-HHmmss>-<随机后缀>.jsonl`；id 人类可读、文件名天然排序（"最新会话"即文件名最大的）。
- **持久化时机**：`append()` 内同步追加写（不做 write-behind 批量）——M4 事件频率低，同步写消掉"崩溃丢尾部事件"的整类问题；批量缓冲属将来优化。
- **消息类型归属**：session 与 llm **各自定义**消息类型（`session.Message(role, content)` 投影产物；`llm.ChatMessage(role, content)` 请求形态），转换放消费方（ChatReplMain）——遵循 DSH"llm 不依赖 session"先例。
- **llm 契约演进**：`ChatRequest` 从 `(systemPrompt, userMessage)` 演进为 `(systemPrompt, List<ChatMessage> messages)`；system prompt 仍单列（不进消息列表、不进会话日志）。OpenAiCompatAdapter 适配 messages 数组序列化。
- **恢复交互**：REPL 启动默认自动继续最新会话（横幅显示会话 id 与已有消息数；目录空则新建）；`/new` 开新会话（旧文件保留）。
- **system prompt**：`config.yml` 的 `llm.systemPrompt` 可选配置（M3 已交付），不进会话日志（每轮单独传）。

## Testing Decisions

- **只测外部行为**：Session 用例走"append → events 视图 / JSONL 文件内容 / load 重放 / deriveMessages 投影"的外部观测量，不窥内部列表结构。
- **会话域测试**（session 模块单测）：append 后事件可见且文件逐行落盘；load 读回与写入序列一致（重放）；投影规则（user/message 与 assistant/message 入列、chunk 不入列）；空会话投影为空列表；`DUO_HOME` 重定向。
- **llm 契约演进测试**：现有 `OpenAiCompatAdapterTest` 迁移到 messages 形态（mock 端点断言 messages 数组含历史轮次）。
- **REPL 冒烟**（example，先例：ChatReplMainTest）：mock 适配器 + 脚本输入，断言多轮记忆（第二轮请求含第一轮内容——经 mock 记录的请求体验证）、自动继续横幅、`/new` 后上下文清空。
- **先例**：`LlmConfigTest`（@TempDir + 注入 env/map 的可测性模式）、`MockOpenAiServer`（内置 HttpServer 夹具模式）。

## Out of Scope

- 工具调用循环与 `tool/call|result` 事件（M5）——事件词汇按 type 判别，届时追加不破坏旧文件
- system-prompt 组装注册表（M6）；plan-mode、agent skills（M7）
- Web 双面与会话事件流的实时订阅消费（M8）
- write-behind 批量刷盘、多存储后端、会话检索/统计/删除管理（DSH 的 session 周边包）——按需后置
- compaction（上下文压缩）——依赖会话 + LLM，属 M9+ 能力扩展
- 会话加密/多用户隔离——单用户本地产品定位

## Further Notes

- 事件词汇与 JSONL 格式自本里程碑起是 **M8 Web 面的直接消费契约**——字段命名与语义变更需按公开 API 演进对待。
- `ChatRequest` 的 M3 形态（systemPrompt + 单条 userMessage）在 M4 起废弃，唯一消费方（ChatReplMain）随本里程碑迁移。
- 会话恢复的交互决策（自动继续最新 + `/new`）在 M8 Web 面会重新审视（Web 的会话列表/切换 UI 是另一形态），机制不变。

# M3 Spec：模型单次对话（LLM 适配 + CLI）

> ADR-0007 渐进序列第一站。M3 只做"能跟模型单轮问答"——无上下文记忆（M4）、无工具循环（M5）、无提示注册表（M6）。每轮独立，端到端打通"输入 → 流式调 LLM → 输出"。

## 目标

`duo-harness-llm` 新模块（provider 中立契约 + OpenAI 兼容适配器 + 配置加载）+ REPL 聊天 demo。跑起来后：命令行输入问题 → 流式打印回答 → 下一轮（每轮独立）。

## 已定决策（grill 2026-09-11）

| 决策点 | 结论 | 理由摘要 |
|---|---|---|
| 接入协议 | 内部中立接口（`LlmAdapter`）+ 首个适配器走 OpenAI chat/completions 兼容协议 | 事实标准：DeepSeek/通义/Kimi/vLLM/Ollama 全覆盖；换模型 = 改配置 |
| 流式 | 第一天就做（SSE 逐 chunk） | 会话事件词汇 `assistant/chunk` 的存在前提；CLI 体验 |
| HTTP/SSE | JDK 内置 HttpClient + 手写 SSE 行解析 | 零新依赖；OpenAI SSE 格式极简 |
| 重试 | M3 不做，失败原样呈现 | 验收观察友好；重试属打磨期 |
| 配置 | `~/.duo/config.yml` 为主（llm: baseUrl/apiKey/model）+ `DUO_LLM_*` 环境变量逐项覆盖；`DuoHome` 解析与配置加载封装为可复用机制 | 配一次到处用；key 在用户 home 天然不入库（红线 2） |
| system prompt | yml 固定字符串起步 | 组装注册表是 M6 |
| CLI | REPL 交互式（`你> `/`AI> `、`/exit` 退出），每轮独立 | M4 接会话时循环结构不动，只在发请求处加历史投影 |
| 模块 | `duo-harness-llm`（契约 + internal），依赖仅 core + Jackson | 模块惯例（tools/mcp 同款形态） |

## 非目标（后续里程碑）

- 上下文/多轮记忆、会话持久化、恢复（M4）
- 工具调用循环、tool-call 协议解析（M5）
- system-prompt 组装注册表（M6）
- 重试/退避、多 provider 适配器（打磨期/M9+）

## 完成判据

- REPL demo：配好 `~/.duo/config.yml` 后一条命令进入对话，流式回答逐段打印，`/exit` 干净退出
- 单元测试不依赖真实 LLM：内置 HttpServer 起 mock SSE 端点验证解析与 chunk 回调
- 配置三级验证：config.yml 缺失时 env 兜底、env 优先级高于文件、缺关键项点名报错
- 全量测试绿；验收对照表（工单 03 产出）逐行核对通过

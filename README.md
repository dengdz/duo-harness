# duo-harness

Java 实现的插件化 AI agent harness：内核是自研轻量插件容器，所有能力（工具 / MCP / LLM / 会话 / 技能 / 计划模式 / 人机协同）以插件形式组装。最新发布 0.2.0；main 分支已落地 M3-M7（LLM 适配、会话上下文、工具循环、HITL、技能与计划模式），随下一版本发布。

## 一条命令看它做什么

```bash
mvn -pl duo-harness-example -am package exec:java
```

输出按时间顺序叙述两段：M1 插件化机制（配置驱动 boot → 服务注入 → 工具管线 → 拔服务级联停止 → 整树回滚）与 M2 治理链（MCP 连接、远端工具读真实文件、guard 拦截、审批拒绝）。另有两个可交互 REPL（聊天 / 工具循环），见 [运行 Demo](docs/01-入门/运行Demo.md)。

## 模块

| 模块 | 职责 |
|---|---|
| `duo-harness-core` | 插件容器内核：生命周期（六态 + epoch 依赖指纹）、服务注入（视图接口寻址）、事件（五种分派模式）、配置驱动 boot、DuoHome 目录约定 |
| `duo-harness-tools` | 工具域：注册与三段执行管线，审批 / guard / 输出契约治理挂链生效 |
| `duo-harness-mcp` | MCP 接入：官方 Java SDK 连接 stdio 服务器，断连重连，远端工具自动同步进工具域 |
| `duo-harness-llm` | LLM 适配：provider 中立流式调用契约 + OpenAI 兼容适配器（`~/.duo/config.yml` 配置） |
| `duo-harness-session` | 会话域：事件溯源（append 单写 + JSONL 落盘）与多轮上下文投影 |
| `duo-harness-agent` | agent 编排：工具循环（Function Calling 闭环）、prompt 注册表、技能系统、计划模式、AGENTS.md 注入 |
| `duo-harness-example` | 示例插件集与演示入口（DemoMain / ChatRepl / AgentRepl） |

## 文档

入口在 [docs/index.md](docs/index.md)：入门（运行 Demo）、架构（模块划分与关键语义）、已知限制、术语表与 ADR。

构建要求：JDK 21、Maven 3.9+。

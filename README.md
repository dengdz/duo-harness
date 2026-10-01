# duo-harness

Java 实现的插件化 AI agent harness：内核是自研轻量插件容器，所有能力以插件形式组装——工具 / MCP / LLM / 会话与上下文治理 / 技能 / 计划模式 / 人机协同 / 子代理 / hooks，双呈现位（终端 REPL + 浏览器）与 headless 同源运行。自 0.1.0 起小步发版至今（完整账本见 [CHANGELOG](CHANGELOG.md)）。

## 快速开始

前提：JDK 21。

**1. 配置 LLM**（`~/.duo/config.yml`，字段以 `llm` 段为准）：

```yaml
llm:
  provider: openai-compat   # 缺省；另支持 anthropic / deepseek / glm
  baseUrl: https://api.deepseek.com
  apiKey: <你的 API Key>     # 永远不要提交真实 Key 到任何仓库
  model: deepseek-chat
```

**2. 启动**（从 [GitHub Releases](https://github.com/dengdz/duo-harness/releases) 下载 fat-jar，或 `mvn -pl duo-harness-example -am package` 自建）：

```bash
java -jar duo-harness-0.26.0.jar
```

**3. 双面即起**：终端进入 REPL（`/help` 看命令）；浏览器打开启动日志打印的带令牌地址（形如 `http://127.0.0.1:18080/?token=...`，缺省随机令牌鉴权）——两面共享同一会话与审批路由。

无人值守形态：`java -jar duo-harness-0.26.0.jar --json "任务文本"` 按一次性任务驱动，stdout 输出 NDJSON 事件流，进程退出码即成败契约。

## 能力概览

- **工具域**：本机 fs 工具族 + web 工具族（抓取转 Markdown、搜索、SSRF 三道防线），三段执行管线挂审批 / guard / 输出契约 / 缺省超时治理
- **MCP**：官方 Java SDK 接 stdio 服务器，断连重连，远端工具自动同步进工具域
- **LLM**：provider 中立流式契约，OpenAI 兼容 / Anthropic / DeepSeek / GLM 四声明，思考等级归一档位，运行时 /model 切换
- **会话域**：事件溯源 JSONL 落盘、独占锁、崩溃恢复、FTS5 全文检索、/export 交付清单
- **上下文治理**：真实 usage 计量、阈值可配、自动压缩、压缩点事件化
- **agent 编排**：Function Calling 工具循环（并发调度 + 顺序屏障）、prompt 注册表、技能系统（发现/热加载）、计划模式、AGENTS.md 注入、todo 分解、子代理（spawn/fork）
- **人机协同**：审批 / 提问 / 计划确认卡片，谁发起谁作答的路由；Web 面工具卡分型、思考折叠卡、产物预览卡、代码高亮
- **hooks**：复用 Claude Code/Codex 配置格式的外部命令钩子（PreToolUse/PostToolUse）

## 模块

| 模块 | 职责 |
|---|---|
| `duo-harness-core` | 插件容器内核：生命周期（六态 + epoch 依赖指纹）、服务注入（视图接口寻址）、事件（五种分派模式）、配置驱动 boot、DuoHome 目录约定 |
| `duo-harness-tools` | 工具域：注册与三段执行管线，审批 / guard / 输出契约 / 管线缺省超时治理挂链生效；本机 fs 工具族与三档权限预设 |
| `duo-harness-hooks` | hooks 扩展域：外部命令钩子挂工具三段管线（fail-open，ADR-0019） |
| `duo-harness-mcp` | MCP 接入：官方 Java SDK 连接 stdio 服务器，断连重连，远端工具自动同步进工具域 |
| `duo-harness-llm` | LLM 适配：provider 中立流式契约，四 provider 声明 + 思考等级归一（`~/.duo/config.yml` 配置） |
| `duo-harness-session` | 会话域：事件溯源（append 单写 + JSONL 落盘）与多轮上下文投影、独占锁、崩溃恢复 |
| `duo-harness-session-query` | 会话检索：FTS5 全文索引 + 检索工具（ADR-0022） |
| `duo-harness-attachment` | 附件域：图片等附件入库（SHA-256 寻址）与读档位治理 |
| `duo-harness-agent` | agent 编排：工具循环（Function Calling 闭环 + 并发调度与顺序屏障）、prompt 注册表、技能系统、计划模式、AGENTS.md 注入、上下文治理、todo 任务分解、子代理（spawn/fork + 控制面） |
| `duo-harness-web` | Web 呈现域：本地 HTTP 服务（对话 + 状态 + HITL 卡片，SSE 实时推送，loopback + 随机令牌鉴权）；工具卡分型 / 思考折叠卡 / 产物预览卡 / 语法高亮 |
| `duo-harness-cli` | CLI 呈现域：终端 REPL（/new、/plan、/permission、审批 y/n、ask 选项、/技能名直调）——与 Web 面对称的呈现位 |
| `duo-harness-stats` | 第三方插件形态范例（ADR-0029）：事件监听 / 命令 / 工具 / 服务消费四扩展点串一插件 |
| `duo-harness-example` | 插件装配与发布入口（DuoMain，`java -jar` 即跑）+ 机制演示入口（DemoMain 等，见运行 Demo） |

## 文档

文档站：**https://dengdz.github.io/duo-harness/** ——入门（[运行 Demo](docs/01-入门/运行Demo.md)：M1/M2 一条命令演示与各 REPL）、指南、高级（技能编写 / MCP 深入 / 多插件协同）、架构、已知限制、术语表与 ADR。

构建：JDK 21、Maven 3.9+（直接用入库的 `./mvnw`，首次运行自动获取发行版）。打包产物为根 example 模块的 fat-jar（`mvn -pl duo-harness-example -am package`）。

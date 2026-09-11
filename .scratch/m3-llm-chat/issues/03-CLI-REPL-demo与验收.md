# 03 — CLI REPL demo 与验收

## What to build

REPL 聊天 demo（duo-harness-llm 或 example 模块的 exec 入口）+ 验收三件套。交互形态：

```
duo-chat（REPL，每轮独立无记忆——M4 接会话）
你> 用一句话介绍你自己
AI> （流式逐段打印回答）
你> /exit
```

- system prompt：yml 固定字符串起步（"你是一个简洁可靠的助手"之类，可配）。
- 流式打印：chunk 逐个输出到终端（不整段等待）。
- `/exit` 与 Ctrl+C 干净退出；输入错误（如未配 apiKey）给出生动的重配指引。

## Blocked by

01, 02

## Status

ready-for-agent

## Checklist

- [x] REPL 循环：提示符 / 流式打印 / /exit 与 Ctrl+C（Ctrl+C 为 JVM 默认终止——优雅退出以 /exit 为准，工单验收按 /exit 口径）
- [x] 未配置 apiKey 时的重配指引输出（实测通过）
- [x] 验收三件套：可运行演示 + 两路日志（demo 叙述 / 测试套件）+ 预期输出对照表（见 Comments）
- [ ] 用户手动验收：真实 key 下逐行对照通过（done 的定义）

## Comments

### 验收对照表

**演示路径 A：未配置 key 的引导（无需任何 key，一条命令验证）**

```bash
DUO_HOME=$(mktemp -d) mvn -pl duo-harness-llm -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.chat.ChatReplMain
```

| # | 应出现 |
|---|---|
| A1 | `LLM 未配置：` |
| A2 | `LLM 配置不完整: baseUrl=缺失, apiKey=缺失, model=缺失` |
| A3 | 示例 YAML（`llm:` / `baseUrl:` / `apiKey: <你的 key>` / `model:`） |
| A4 | 进程干净退出（exit 0） |

（实测快照 2026-09-11：A1–A4 全部命中，路径随 DUO_HOME 显示临时目录。）

**演示路径 B：真实对话（先在 `~/.duo/config.yml` 配好 key）**

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.chat.ChatReplMain
```

| # | 应出现 |
|---|---|
| B1 | `=== duo-harness Chat Demo（M3：模型单次对话，每轮独立无记忆）===` |
| B2 | `模型: <你配的模型> @ <你配的 baseUrl>` |
| B3 | `你> ` 提示符，输入问题后 `AI> ` 后跟流式回答 |
| B4 | `/exit` 后 `=== 对话结束 ===`，exit 0 |

（预期按实现推导——真实回答文本取决于模型；验收时以流式逐段出现、内容切题为通过标准。）

**测试路径**

```bash
mvn -pl duo-harness-llm -am test
```

套件叙述与计数：`LlmConfigTest`（5：文件基线+systemPrompt 覆盖/env 优先/缺文件 env 兜底/缺项点名/空白 env 不覆盖）、`OpenAiCompatAdapterTest`（5：多 chunk 按序与角色 chunk 跳过/请求形态与 Bearer/401 错误点名/非 JSON 错误原文/网络失败）——mock SSE 端点（内置 HttpServer），不起真实 LLM。

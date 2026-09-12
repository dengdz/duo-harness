# 03 — AgentRepl demo 与验收对照表

## What to build

`AgentReplMain`（example）：Boot 配置（tools 插件 + 审批 always-deny + MCP files 连接）装配 `ToolsService` 与会话目录，注入 ChatAgent——两大标志性场景端到端：

1. **自主调工具**：用户"读一下 notes.txt" → LLM 调用 `mcp__files__read_file` → 真实文件内容回填 → LLM 总结回答
2. **治理可见**：LLM 想写文件 → 审批拒绝 → LLM 向用户解释"写被策略拒绝了"

REPL 交互（`你> `/`AI> `、过程叙述：调工具/工具结果/审批拒绝）、`/exit` 退出；验收对照表（本工单 Comments）。

## Blocked by

01, 02

## Status

done（2026-09-12 用户手动验收通过：场景一读文件闭环、场景二审批拒绝后 LLM 自行解释；思考模型 reasoning_content 修复后无 400）

## Checklist

- [x] AgentReplMain：Boot 配置装配（tools + approval always-deny + MCP files）+ ChatAgent 注入 + REPL 循环（过程叙述：调工具/工具结果）
- [x] 标志性场景：读文件自主调用成功 / 写文件被审批拒绝且 LLM 自行解释
- [x] 冒烟测试：mock LLM（tool_calls 脚本）+ 真 ToolsService（测试工具）
- [x] 验收对照表（本工单 Comments）：含预期日志原文段
- [x] 用户手动验收（done 的定义）

## Comments

### 验收对照表（M5 端到端）

**前置**：`~/.duo/config.yml` 配置 llm 段（baseUrl/apiKey/model）。

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

**场景一：自主调工具读文件**

| 输入 | 预期输出 |
|---|---|
| （启动） | `=== 会话 <id>（工具循环上下文）。/exit 退出。` + MCP files 连接日志 |
| `读一下 notes.txt` | `[调工具] mcp__files__read_file {"path":"notes.txt"}` + `[工具结果] agent 演示的文件内容` + `AI> <LLM 总结文件内容>` |

**验证点：LLM 自主决定调用工具（无人指定），真实文件内容经六段管线回填。**

**场景二：治理可见（审批拒绝）**

| 输入 | 预期输出 |
|---|---|
| `帮我写一个 output.txt，内容是测试` | `[调工具] mcp__files__write_file {...}` + `[工具错误] 工具 "mcp__files__write_file" 执行被拒绝: …（策略: always-deny）` + `AI> <LLM 解释无法写文件及原因>` |

**验证点：审批拦截在 LLM 驱动下依然生效，且 LLM 能理解拒绝原因并向用户解释。**

**测试路径**：`mvn -pl duo-harness-example -am test`——AgentReplMainTest 2 用例（Function Calling 闭环：调工具→管线执行→回填→二轮直答；迭代上限恰好 3 轮终止）。

### 状态

待用户手动验收。通过后置 done。

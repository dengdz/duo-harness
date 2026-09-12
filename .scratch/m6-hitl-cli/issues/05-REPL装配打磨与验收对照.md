# 05: REPL 装配、打磨与验收对照

**What to build:** 把 M6 全部机制装配进 AgentRepl，完成打磨小件与里程碑验收件：console answerer（终端 y/n，one-shot）+ 审计回答者（装饰桥，委托前后写 approval 事件）挂进装配；AgentRepl 补 `/new`；Boot yml 新增 `agent:` 段（maxIterations / retry 参数）；demo 装配升级——写文件挂 interactive 审批、挂载 ask_user、prompt 注册表演示片段。端到端三场景可演示：写文件弹 y/n 且决定入会话、模型中途向用户提问并引用回答、重复调用出现提醒。

**Blocked by:** 01, 02, 03, 04（集成单，全部在场）

**Status:** implemented（2026-09-13，待用户手动验收——M6 收官闸门）

## Checklist

- [x] console answerer：工具名 + 参数呈现，`[y=允许本次 / n=拒绝]` 阻塞读一行；EOF/Ctrl+C 按拒绝
- [x] 审计回答者（装饰桥）：委托前写 `approval/requested`、决定后写 `approval/decided`；可组合、不侵入机制核
- [x] AgentRepl 补 `/new`（对齐 ChatRepl 行为）
- [x] 重试参数接线（实现调整：Boot yml 是纯插件清单，无 `agent:` 段载体——retry 参数改放 `~/.duo/config.yml` 的 `llm.retry` 段，语义更贴合；maxIterations 维持构造参数默认 10）
- [x] demo 装配：交互服务 + InteractiveApprovalPlugin 替换 always-deny + ask_user 挂载 + prompt 演示片段 + RepeatReminderPlugin
- [x] 测试：ConsoleAnswererTest 7 例 + AuditingAnswererTest 3 例（呈现与留痕组件级覆盖；脚本 LLM 三场景端到端留待用户手动验收——mock LLM 无法真实驱动终端 y/n 交互流）
- [x] 验收对照表（本工单 Comments）：运行命令 + 预期输出逐段对照
- [x] 文档同步：运行Demo.md 补 M6 交互场景；CHANGELOG 未发布段收口
- [ ] 用户手动验收（done 的定义）

## 实现记录（2026-09-13）

- 新组件：ConsoleAnswerer（审批 y/n + 提问序号/自由文本 + EOF fail-closed）、AuditingAnswerer（审批事件装饰桥，提问透传）
- AgentReplMain：/new 重建会话与 agent（SessionHolder/LlmAdapterHolder 可变引用）；RetryingAdapter 按配置包装；ask_user 挂载；prompt 演示片段（demo:platform）
- agent-demo.yml：interaction + InteractiveApprovalPlugin 替换 always-deny 行 + repeat-reminder 行
- 测试 10 例新增（Console 7 + Auditing 3），example 模块 20/0/0

## Comments

### 验收对照表（M6 端到端）

**前置**：`~/.duo/config.yml` 配置 llm 段（baseUrl/apiKey/model；retry 子段可选）。

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

启动预期：`=== 会话 <id>（工具循环上下文）。/exit 退出，/new 开新话题。` + MCP files 连接日志（2 个工具注册）。

| 场景 | 输入 | 预期输出 |
|---|---|---|
| 一、审批放行 | `帮我写一个 output.txt，内容是测试` | `[调工具] mcp__files__write_file {...}` → `[待审批] 工具 mcp__files__write_file 请求执行` + 参数行 → `批准本次执行？[y=允许 / n=拒绝]` 输入 **y** → `[工具结果]` 写入成功 → `AI>` 确认完成 |
| 二、审批拒绝 | 再输入同样的写请求，提示符处输入 **n** | 同上呈现 → 输入 n 后 `[工具错误] …被人拒绝（回答者: console）…` → `AI>` 向你解释写入被拒及替代方案 |
| 三、模型提问 | `我想存一个笔记，但你要先问我想存在哪个文件` | `[调工具] ask_user {...}` → `[提问] …`（有选项列序号，无选项自由输入）→ 输入回答 → 模型按你的回答继续（如请求写该文件又触发场景一审批） |
| 四、重复提醒 | `把 notes.txt 读五遍`（或诱导连续相同读取） | 第 3 次相同调用起 `[工具结果]` 尾部出现 `[提醒] …` 逐级加码 → 模型换方法或说明 |
| 五、会话管理 | `/new` 后再对话 | `新会话 <id>。`，旧会话文件保留可回放；会话目录 `~/.duo/agent-sessions` |

**验证点**：审批 one-shot（每次都问，无永久放行）；Ctrl+C / EOF 跳过审批按拒绝处理；审批事件对（approval/requested + approval/decided）与 ask_user 的 tool/call + tool/result 均可在会话 JSONL 中回放。

**测试路径**：`mvn -pl duo-harness-example -am test`——agentrepl 包 ConsoleAnswererTest 7 例 + AuditingAnswererTest 3 例 + AgentReplMainTest 2 例（循环闭环与迭代上限）。

### 状态

待用户手动验收。通过后置 done（M6 收官）。

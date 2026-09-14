# 运行 Demo

> 状态：M1 + M2 一条命令演示全链路可用；M3 聊天 REPL、M5 工具循环 REPL、M6 人机协同（HITL）、M7 技能与计划模式、M8 Web 双面均可交互运行（见下文）。

## 一条命令

```bash
mvn -pl duo-harness-example -am package exec:java
```

输出按时间顺序叙述两段：

**M1 段**：boot 状态迁移（`plugin/status` 事件）→ 服务注入（消费者经视图获得问候）→ 工具三段管线（正常执行 / 准入否决 / 结果治理）→ 运行时拔服务级联停止（消费者 UNLOADING 回 PENDING）→ 整树回滚。

**M2 段**：临时目录写入真实文件 → boot 治理配置（审批 always-deny + 写保护插件）→ 挂载 MCP 连接（迷你 filesystem server，真实 stdio 子进程）→ 远端工具自动同步 → `read_file` 经三段管线读真实文件 → guard 拦截涉密文件（署名 guard）→ `write_file` 被声明需审批遭策略拒绝 → dispose 连接后工具消失。

## Demo 演示什么

| 机制 | 叙述中的体现 |
|---|---|
| 配置驱动 boot | `demo.yml` 一行一插件；`demo-disabled` 行在场而实例未装载 |
| 行序无加载语义 | `greeting-client` 行排在 `greeting` 前，先 PENDING 后被唤醒 |
| 服务注入 | GreetingClientPlugin `inject` 声明 + `ctx.as(视图)` 调用 |
| 工具三段管线 | echo 工具的 pre-execute 否决（敏感词）与 post-execute 结果后缀 |
| 依赖驱动生命周期 | 拔掉临时提供者 → 消费者 UNLOADING → PENDING（等待回归） |
| 整树回滚 | 收尾 root.dispose，每个插件 DISPOSED 逐一叙述 |
| MCP 连接与工具同步 | `mcp__files__*` 工具自动注册，`read_file` 读到真实文件内容（M2） |
| 审批策略（ask 三态） | `write_file` 被治理插件声明 ask → always-deny 拒绝并署名策略（M2） |
| guard 单调否决 | 读 `secret.txt` 被拒并署名 guard，拒绝无法翻回（M2） |
| 连接即生命周期 | dispose MCP 连接 → 远端工具随作用域注销（`未注册` 点名，M2） |

## 想改着玩

- 编辑 `duo-harness-example/src/main/resources/demo.yml`：翻转 `disabled`、改 config 前缀、增删行，重跑命令即见形态变化
- 编辑 `demo-m2.yml`：把审批 policy 换成 `auto-approve` 并白名单 `mcp__files__write_file`，重跑可见写操作放行、临时目录出现新文件
- 示例插件源码在 `duo-harness-example` 的 `dev.duo.harness.example` 包树——`greeting`（服务对）、`tools`（工具与治理）、`approval`（审批策略）、`contract`（输出契约与 guard）、`mcpfs`（迷你 filesystem server 与写保护）五个功能子包

M2 逐工单验收对照表见仓库归档（`.scratch/` 随库入库，站外内容）。

## 聊天 REPL（M3 + M4）

前置：`~/.duo/config.yml` 配置 `llm:` 段（baseUrl/apiKey/model，DeepSeek 等 OpenAI 兼容 provider 开箱即用）。

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.chat.ChatReplMain
```

`你> ` 提问，回答流式打印；多轮对话有上下文记忆，重启自动继续最近会话，`/new` 开新话题，`/exit` 退出。会话 JSONL 落 `~/.duo/sessions`，可回放。

## 工具循环 REPL（M5 + M6）

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

LLM 驱动真实工具的完整闭环（同一 `llm:` 配置）：启动挂载 MCP files 连接（迷你 filesystem server，指向临时目录），标志性场景：

| 输入 | 预期 |
|---|---|
| `读一下 notes.txt` | `[调工具] mcp__files__read_file` → `[工具结果]` 真实文件内容 → 模型总结回答 |
| `帮我写一个 output.txt，内容是测试` | `[调工具] mcp__files__write_file` → `[待审批]` 终端呈现工具与参数，输入 `y` 放行（文件真实写入）或 `n` 拒绝（模型解释原因）——决定落会话审计 |
| 需要补充信息的任务 | `[提问]` 模型经 ask_user 向你提问（选项序号或自由文本）→ 回答后模型继续 |
| 连续重复同一调用 | 第 3 次起 `[提醒]` 附加于工具结果，逐级加码 |

交互安全语义（ADR-0008）：审批 one-shot、无"永久放行"；你不回答（Ctrl+C / EOF）一律按拒绝处理。`/new` 开新话题，`/exit` 退出；会话 JSONL 落 `~/.duo/agent-sessions`。LLM 调用自带重试（网络故障与 429/5xx 指数退避，参数见 config.yml `llm.retry` 段）。

## Web 双面（M8）

同一条命令（agent-demo.yml 已含 web 行，端口 18080），启动时控制台打印 `Web 面已启动: http://127.0.0.1:18080`——浏览器打开即用：

| 操作 | 预期 |
|---|---|
| 打开页面 | 三区布局：会话侧栏（列出/当前高亮）/ 对话（续接最新会话，历史回放）/ 状态面（上下文占用 + 插件六态表 + 工具清单） |
| 输入框发消息 | 发送按钮转「思考中…」禁用（完成后恢复）→ 用户气泡唯一 → 工具合一卡（⟳ 运行中 → ✓ 成功/✗ 失败，结果可折叠）→ 流式回复，完成后 Markdown 整段渲染（代码块/列表/标题） |
| 状态面「上下文」 | `N / 窗口 tokens（占比 %，压缩阈值 M · 实测/估算）`——与治理判定同源，超阈值变红 |
| 触发写操作 | [待审批] 卡片 → 批准（工具 ✓ 继续）/ 拒绝（工具 ✗，模型解释） |
| 页面刷新 | 悬空审批卡片保留可答（不误杀）；历史完整重建 |
| 模型提问 | [提问] 卡片（选项 + 自由输入）→ 回答后模型继续 |
| 计划呈交 | 模型调 `exit_plan_mode` → [计划呈交] 卡片 → 批准开始执行 / 打回带反馈 |
| ＋ 新话题 | 新会话 + 初始欢迎态；侧栏点旧会话切换（历史回放） |
| 关闭页面 | 悬空审批按 fail-closed 拒绝（宽限期内无新连接入列），重开可见"✗ 已拒绝" |
| 服务故障 | 发送/刷新失败在页面顶部弹出红色提示（5 秒自动消失），不静默 |
| CLI 并存 | 终端 AgentRepl 照常可用（同一会话目录；会话独占——最新会话被 Web 面持有时 CLI 明确提示并改开新会话） |

安全基线：只绑 127.0.0.1（局域网不可访问），无鉴权；会话切换 id 白名单校验、请求体上限 1MB、错误响应不回显内部细节。

## 技能与计划模式 REPL（M7）

同一命令入口（见上节），新增能力：

| 输入 | 预期 |
|---|---|
| `帮我按发布规范写个说明` | 模型看到技能清单后调 `[调工具] skill {"name": "release-notes"}` → 按技能指令产出 |
| `/release-notes 0.3.0` | 用户直调：技能指令注入本轮，模型直接按技能行事 |
| `/plan 整理示例目录` | 进入计划模式（模型先探索与设计、不做修改性操作）→ 模型调 `exit_plan_mode` 呈交计划 → 你选批准或打回给反馈 |
| 计划复核不回答 | fail-closed：计划不批准，保持计划模式 |

技能目录（四根发现，同名高优先根胜）：项目 `.duo/skills/` → 项目 `.agents/skills/`（行业标准）→ `~/.duo/skills/` → `~/.agents/skills/`；本仓库内置演示技能 `release-notes`。AGENTS.md（用户全局 `~/.duo/AGENTS.md` 与项目根）自动注入 agent 上下文（64KB 预算）。

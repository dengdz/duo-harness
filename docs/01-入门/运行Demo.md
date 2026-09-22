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

## 工具循环 REPL（M5 + M6，M12 起本机工具族）

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

日常验收/反复启动加 `-DskipTests`（`-am` 会拉起上游全部模块，`package` 默认逐模块跑全量测试，属收口 `mvn test` 的职责）——首次构建或发布前仍用原命令完整跑：

```bash
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

LLM 驱动真实工具的完整闭环（同一 `llm:` 配置）。启动即含本机 fs 工具族六件（read / write / edit / glob / grep / bash，workspace 绑定 = 启动进程的当前目录）、三档权限预设（默认 workspace-write，终端 `/permission [档位]` 查看与切换），以及 web 工具族（M20：`web_fetch` 恒在；解开 `agent-demo.yml` 的 `search` 注释并 `export TAVILY_API_KEY=…` 即含 `web_search`）。标志性场景：

| 输入 | 预期 |
|---|---|
| `读一下 pom.xml 的前 30 行` | `[调工具] read` → `[工具结果]` 带行号窗口（大文件带续读提示）→ 模型总结回答 |
| `新建 hello.txt，内容是测试` | `[调工具] write` → workspace 内写**免审批**直接 `Created file`（区内写档位放行） |
| `往 /tmp/duo-escape.txt 写点东西` | 越界写 → `[待审批]`（双面装配落 Web 卡片；纯 CLI 装配为终端 y/n）——批准落盘 / 拒绝模型解释，决定落会话审计 |
| `用 bash 跑 ls` | `[调工具] bash` → 非 danger 档**一律 ask**（bash 写范围不受 workspace 约束）→ 放行后输出 + `[exit code: 0]`（非零退出也是结果不是错误） |
| `抓一下 https://example.com` | `[调工具] web_fetch` → 头行（最终 URL + 状态码）+ Markdown 正文总结；抓 404 页也是结果（头行带状态码），不是错误 |
| `抓一下 http://localhost:18080` | SSRF 拒绝——回环/内网目标与解析到内网的域名直接报错（重定向跳内网同样被拦，ADR-0021） |
| `搜一下 <关键词>`（需 TAVILY_API_KEY） | `[调工具] web_search` → Sources 列表（标题 + 链接 + 摘要）→ 模型挑条目 `web_fetch` 深入 |
| `/permission read-only` 后再让它写或联网 | 写、bash 与联网（web_fetch/web_search）全部 ask——只读档不出网边界，切档即时生效 |
| 需要补充信息的任务 | `[提问]` 模型经 ask_user 向你提问（选项序号或自由文本）→ 回答后模型继续 |
| 连续重复同一调用 | 第 3 次起 `[提醒]` 附加于工具结果，逐级加码 |

交互安全语义（ADR-0008 / ADR-0012）：审批 one-shot、无"永久放行"；你不回答（Ctrl+C / EOF）一律按拒绝处理。`/new` 开新话题，`/exit` 退出；会话 JSONL 落 `~/.duo/agent-sessions`。LLM 调用自带重试（网络故障与 429/5xx 指数退避，参数见 config.yml `llm.retry` 段）；流式响应连续 90s（`llm.streamIdleTimeoutSeconds` 可配）无新字节即中止——首字节前超时自动重试，已输出内容后中止并保留已生成文本。

## Web 双面（M8）

同一条命令（agent-demo.yml 已含 web 行，端口 18080），启动时控制台打印 `Web 面已启动: http://127.0.0.1:18080`——浏览器打开即用：

| 操作 | 预期 |
|---|---|
| 打开页面 | 三区布局：会话侧栏（标题或 id / 当前高亮 / 被占会话灰显"使用中"）/ 对话（续接最新会话；超过 50 条投影消息的大会话首屏只回放最近 50 条，不足则全量呈现，ADR-0013）/ 状态面（上下文占用 + 插件六态表 + 工具清单） |
| 输入框发消息 | 发送按钮转「思考中…」禁用（完成后恢复）→ 用户气泡唯一 → 工具合一卡（⟳ 运行中 → ✓ 成功/✗ 失败，结果可折叠）→ 流式回复，完成后 Markdown 整段渲染（代码块/列表/标题）；首条消息后侧栏与标签页自动出现会话标题（异步生成，失败降级首条前 20 字） |
| 滚动到顶 | 自动加载更早一页历史（顶部占位"更早还有 N 条"），视窗不跳屏；加载到最早后占位消失 |
| 状态面「上下文」 | `N / 窗口 tokens（占比 %，压缩阈值 M · 实测/估算）`——与治理判定同源，超阈值变红 |
| 触发写操作 | [待审批] 卡片 → 批准（工具 ✓ 继续）/ 拒绝（工具 ✗，模型解释） |
| 页面刷新 | 悬空审批卡片保留可答（不误杀）；首屏重建为最近 50 条消息（更早的滚动加载） |
| 模型提问 | [提问] 卡片（选项 + 自由输入）→ 回答后模型继续 |
| 计划呈交 | 模型调 `exit_plan_mode` → [计划呈交] 卡片 → 批准开始执行 / 打回带反馈 |
| ＋ 新话题 / 侧栏切换 | 均不整页重载——页面静默换绑后呈现新会话尾部窗口；输入框未发送的草稿按语境清空；侧栏高亮与标签页标题跟随；被占会话切换仍明确报错 |
| 关闭页面 | 悬空审批按 fail-closed 拒绝（宽限期内无新连接入列），重开可见"✗ 已拒绝" |
| 服务故障 | 发送/刷新失败在页面顶部弹出红色提示（5 秒自动消失），不静默 |
| CLI 并存 | 终端 REPL 由 `cli` 插件承载（ADR-0011，与 web 行对称启停）——会话独占：最新会话被 Web 面持有时 CLI 明确提示并改开新会话；`/exit` 只停终端（锁释放、Web 不受影响），彻底停止 Ctrl-C（shutdown hook 级联释放） |

安全基线：只绑 127.0.0.1（局域网不可访问），无鉴权；会话切换 id 白名单校验、请求体上限 1MB、错误响应不回显内部细节。

## 输入面与会话工具（M21，ADR-0022）

同一命令入口。`agent-demo.yml` 已含 `attachment` 与 `session-query` 行（M21 起）；视觉多模态还需 `~/.duo/config.yml` 的 `llm:` 段补 `vision: true`（缺省 false 时收图与读图均被闸门拒绝——非视觉部署零感知）：

| 操作 | 预期 |
|---|---|
| Web 输入框拖拽/粘贴图片（`vision: true`） | 图片上传入库 → 发送后消息渲染图片 → 模型以图片部件"看见"并回答；`vision: false` 时上传/发送明确拒绝（409），不浪费任何轮次 |
| 对话让模型看图：`用 read_image 看一下 <图片路径>` | `[调工具] read_image` → 图片入库（附件引用返回）→ 模型基于图内容回答；同图重复读存储层去重 |
| 输入框敲 `@` | 弹出工作区路径补全下拉（`[目录]/[文件]` 标记）；继续输字符过滤，`↓`/`↑` 选词、`Enter`/`Tab` 选中、`Esc` 收起；选中目录（尾 `/`）继续输入即下钻；含空格路径自动 `@"..."` 引号 |
| 发送带 `@pom.xml` 的消息 | 模型调 `read` 工具读取后回答——@ 只是指路，内容永远经 read（未 read 不得声称已看过） |
| 模型 bash 建新文件后再输 `@新文件名` | 补全能找到（tool/result 触发后台重建）；IDE/终端里带外新建的文件在下次搜索自动收录 |
| 侧栏搜索框输入关键词回车（如 `权限档`） | 命中会话列表（标题/事件类型/带【】摘录）→ 点击命中切换到那个会话；`×` 关闭回列表；搜不到提示"无命中"且列表照常可用 |
| 对话问 `搜一下之前哪个会话讨论过附件库` | 模型调 `session_search` → 引用会话 id 与摘录回答 |
| 终端敲 `/export`（或 Web 敲，自动下载） | 当前目录生成 `duo-session-<id>.md` 人读记录（角色/时间戳 + 工具摘要 + 附件清单）；`/export json` 得日志原样副本（逐行等价） |

注：会话检索为内存倒排索引（个人会话规模量级），检索/补全均懒构建——启动零成本，首次使用才扫；图片投递缺省 inline base64，`llm.imageDelivery: files` 切 DeepSeek 形态 Files API 上传换 file_id（省大图传输，仅 DeepSeek 端点可用，见[已知限制](../limitations.md)）。

## headless --json（M23，ADR-0025）

同一入口加 `--json`：一次性跑任务，stdout 输出逐行 JSON 事件流（NDJSON），退出码即成败——自动化脚本/CI 不碰交互界面：

```bash
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args="--json 总结一下当前目录结构"
```

| 消费点 | 预期 |
|---|---|
| stdout 逐行 JSON | `session{sessionId,cwd}` 开场 → `status{phase}` 相位 → `tool_call{callId,tool,input}` / `tool_result{callId,status,result|error}` 工具过程 → `text{text}` 提交点全文 → `final{text}` 无损答案（消费锚点） |
| 退出码 | completed→0；迭代上限/异常→1；SIGTERM→0、SIGINT→130；usage 错误→2 |
| 诊断 | 只走 stderr——stdout 可以直接接 `jq` / `grep '"type":"final"'` 管道消费 |
| 禁交互 | 审批/提问自动拒绝 + 显式 `error` 帧，流程不挂死；超长中间帧 8K/32K 截断带 `truncated:true`（final 永不截断） |
| 恢复会话 | `--session-id <id>` 续跑既有会话（事件流只推新事件）；会话被占/不存在退出码 1 |
| yml 装配 | 首个 positional 若为 .yml 文件即自定义装配（cli/web 呈现位行自动禁用），缺省 agent-demo.yml |

## 技能与计划模式 REPL（M7）

同一命令入口（见上节），新增能力：

| 输入 | 预期 |
|---|---|
| `帮我按发布规范写个说明` | 模型看到技能清单后调 `[调工具] skill {"name": "release-notes"}` → 按技能指令产出 |
| `/release-notes 0.3.0` | 用户直调：技能指令注入本轮，模型直接按技能行事 |
| `/plan 整理示例目录` | 进入计划模式（模型先探索与设计、不做修改性操作）→ 模型调 `exit_plan_mode` 呈交计划 → 你选批准或打回给反馈 |
| 计划复核不回答 | fail-closed：计划不批准，保持计划模式 |

技能目录（四根发现，同名高优先根胜）：项目 `.duo/skills/` → 项目 `.agents/skills/`（行业标准）→ `~/.duo/skills/` → `~/.agents/skills/`；本仓库内置演示技能 `release-notes`。AGENTS.md（用户全局 `~/.duo/AGENTS.md` 与项目根）自动注入 agent 上下文（64KB 预算）。

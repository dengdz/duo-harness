# M11 验收对照表

> duo-acceptance 里程碑级验收件。M11「CLI 插件化」：三张功能单（01 共享装配器 / 02 CliPlugin / 03 DuoMain）合并验收 + 本件（04 收官）。

## 运行命令

测试路径（291 用例）：

```bash
lsof -ti :18080 | xargs kill 2>/dev/null; mvn -o test
```

演示路径（**用户自己的终端**——CLI 要读键盘，不能用后台启动；需 `~/.duo/config.yml` 的 llm 段）：

```bash
mvn -o -pl duo-harness-example -am package -DskipTests exec:java -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

浏览器开 `http://127.0.0.1:18080/`（Web 卡片路由优先——yml 行序 cli 在 web 后）。

## 预期日志 A：测试路径

M11 相关套件叙述（顺序随执行而定）：

```
=== 套件：PresenterAssemblyTest —— 呈现位共享装配器：执行链装配、交互工具查重注册、LLM 配置失败点名（3 用例） ===
=== 套件：CliPluginTest —— CLI 呈现位插件：REPL 循环、/new 换绑、/exit idle 锁释放、占用提示（5 用例） ===
=== 套件：CliPluginAssemblyTest —— 纯 CLI 装配：HITL 交互工具随装配注册（1 用例） ===
=== 套件：SkillInvocationTest —— CLI 技能直调识别：前缀注入、未知名提示、非斜杠透传（1 用例） ===
=== 套件：ConsoleAnswererTest —— 终端回答者：审批 y/n、EOF fail-closed、序号与自由文本回答、多选、未知类型放弃（6 用例） ===
=== 套件：AgentReplMainTest —— agent 演示冒烟：Function Calling 闭环（调工具 → 治理链 → 回填 → 直答）+ 迭代上限（1 用例） ===
```

末尾汇总：合计 **291 用例，0 失败 0 错误**（cli 模块 14 + 既有 277）。

## 预期日志 B：演示路径（实测快照）

### 验收点 1：双开启动（CLI 插件 + Web 插件同树）

```
Web 面已启动: http://127.0.0.1:18080
[提示] 会话已被占用：20260914-224438-c267（/Users/…/.duo/agent-sessions/….jsonl）——同一会话同一时刻只允许一个进程使用，请先关闭占用它的程序
[提示] 改为新建会话继续；被占会话仍由占用方使用。
会话 20260915-013413-f6fa（工具循环上下文）。/exit 退出，/new 开新话题。
你>
```

（Web 先持锁最新会话、CLI 让位新建——`/api/status` 插件清单含 `CliPlugin`，工具清单含 `ask_user` 与 `exit_plan_mode`。）

### 验收点 2：终端交互（行为与 M10 前的 CLI 完全一致）

```
你> 用一句话介绍自己
（流式输出；工具调用时 [调工具]/[工具结果] 叙述）
你> /new
新会话 <新id>。
```

### 验收点 3：/exit 的 idle 语义（本里程碑核心）

```
你> /exit
=== 对话结束 ===
```

- 进程**不退出**：浏览器仍 200、可继续对话（插件树与 Web 面不受影响）
- CLI 的会话锁已释放（`/exit` 前两个会话被锁 → 后只剩 Web 的一个）
- 想再进终端需重启服务（idle 语义，ADR-0011）；彻底停止 Ctrl-C——shutdown hook 级联释放全部锁

### 验收点 4：纯 CLI 部署（可选）

yml 去掉 web 行（或标 `disabled: true`）→ 只有终端入口，agent 全能力可用（HITL 经终端 y/n）——`CliPluginAssemblyTest` 已自动化锁定该形态。

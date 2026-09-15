# 03: bash 工具与档位联动

## What to build

bash 工具（每次调用全新进程，工作目录固定 workspace 根）——env 硬化、超时 clamp、输出截断、非零退出 marker；与三档权限预设联动：danger 放行、其余档经审批 seam ask（终端 y/n / Web 卡片）。

## Blocked by

01（档位裁决经 WorkspacePolicy 服务）

## Status
done（2026-09-15 验收通过）

## Checklist
- [x] 全新进程执行（`bash -c`）+ env 硬化（NO_COLOR/TERM=dumb/PAGER=cat）+ 工作目录固定 workspace 根
- [x] 超时 clamp（模型可传低值；缺省 120s、上限 600s）→ 超时 marker（`[timed out]`）+ 进程树终止
- [x] 每流输出截断（完整输出提示走治理 spill 兜底）+ 非零退出 `[exit code: N]` marker 非 error
- [x] requiresApproval = true（静态声明）+ 档位联动判定测试：danger 放行 / workspace-write 与 read-only 档 ask
- [x] 呈现回归：终端叙述行与 Web 工具卡既有形态零特化验证

## Comments

- 实现：`tools.fs.FsBashTool`（`FsToolsPlugin` 注册第六件）。要点——① 每次调用 `new ProcessBuilder("bash","-c",command)`，`directory(workspace.root())`，env put 三项硬化，stdin 接空设备（读 stdin 的命令立即 EOF，既不挂到超时也不偷吃 REPL 输入）；② 超时 `timeoutFor()`：低值透传、缺省 120s、上限 600s，非正数/非数值按缺省；到点 `terminateTree()`——**先快照 `descendants()` 再 kill**（父进程死后句柄查不到），SIGTERM 宽限 500ms 后按句柄复查强杀（忽略 SIGTERM 的孙进程不为"父进程已退"所掩盖）；③ 双流并发读（虚拟线程）——管道写满会与 `waitFor` 互锁；单流上限 100000 字符（内存护栏，取值高于治理层 spill 阈值 50000：大而正常的输出由治理 spill 落盘给定位符、模型可回读全文），超护栏部分只计数不保留（缓冲区仍排空，子进程不阻塞）并报省略量与补救方向；④ 结果形态：stdout 原样 → `[stderr]` 标题分段 → `[exit code: N]`（非零不转 error）；超时行替换退出码行为 `[timed out] 300ms limit reached; process tree terminated`；命令已退但流仍被后台进程持有时追加 `[output may be incomplete: ...]`（不静默交半截输出）。
- 测试：`FsBashToolTest` 10 用例（cwd 锚定 workspace 根且产物落区内 / env 硬化 / 跨调用状态不残留 / stdin 空设备立即 EOF / 双流分离 / 非零退出 marker 且非错误形态 / 双流截断计数与补救方向 / 后台进程持有流的不完整 marker / 超时 300ms 即刻返回且睡醒后的产物不出现——进程树确已终止 / clamp 矩阵）；`BashTierLinkageTest` 4 用例（工具服务级装配：danger 短路放行且不咨询回答者 / workspace-write ask 且拒绝即命令不执行 / read-only ask 且放行后执行 / 非零退出经管线仍非错误）。`CliPluginTest` 新增第 7 用例 `bashToolRendersThroughGenericToolLines`：read-only fixture 下 bash 经终端审批 `y` 放行，叙事行 `[调工具] bash {"command":"echo 呈现回归"}` 与 `[工具结果] 呈现回归` 由通用 onToolCall/onToolResult 产出。
- 呈现零特化证据：终端侧行文由 `CliPlugin` 通用监听器产出（无工具名分支）；Web 侧仅 `ask_user` / `exit_plan_mode` 有专用卡片分支（`app.js`），bash 走 `render.toolCall` 工具卡与 `render.interactiveCard` 审批卡；后端无任何工具名特化（全仓 grep 无 `"bash"` 字面分支）。视觉确认随演示路径第 9 步。
- 文档同步（同 diff）：`docs/05-参考/工具目录.md` 补 bash 条目（描述与注册原文逐字一致，供 ToolCatalogTest 对账）+ 段首"五件"改"六件" + 状态行改版本戳措辞。
- 审查轮（duo-code-review：OCR 端点 403 → 委托模式行级自查 + Standards/Spec 双轴子代理）：
  - **Spec 轴实义缺口（已修）**：原实现单流上限 30000（低于治理 spill 阈值 50000），治理层拿到的是已截断文本——spec 的"截断 + 回收"设计点落空、完整输出无回收路径。改为上限 100000（高于 spill 阈值）：正常大输出走治理 spill 落盘可回读，超护栏才丢尾部并给补救方向。
  - **正常退出但流被后台进程持有（已修）**：读线程 2s 收不了尾即返回半截输出且无标记 → 补 `[output may be incomplete: ...]` marker + 用例。
  - **委托自查（已修）**：stdin 未接空设备（读 stdin 命令挂到超时 / 会偷吃 REPL 输入）→ `redirectInput(/dev/null)` + 用例；`terminateTree` 在"父进程已退但孙进程忽略 SIGTERM"时会漏杀 → 宽限期后按句柄复查强杀；`appendStream` 的 boolean 旗标参数 → 拆 `appendStdout`/`appendStderr`/`appendBody`；类 JavaDoc 补基础设施故障上抛语义；`FsToolsPlugin` 空 catch 补抑制理由；`FsToolsTest` 删纯转发断言 helper（Middle Man）。
  - **判为可接受（不复改）**：三份测试文件各自的 6 行 JSON 解析 helper 重复——测试夹具自成一体，第四个出现时再抽取；`redirectInput` 与 `[stderr]` 标题分段属工单未明写但必要的进程卫生与可读性（Unix-only，spec 已声明 Windows 不承诺），此处留痕。
  - **上抛的跨工单项**：CHANGELOG 0.7.0 段未建（M12 各工单共有）——按 spec 归工单 05"CHANGELOG 0.7.0 段记账"，本工单不动，此处留痕以免被当成遗漏。
- 验证证据（本机 mvn，2026-09-15）：`mvn -o -pl duo-harness-tools,duo-harness-cli -am test -Dtest='FsBashToolTest,BashTierLinkageTest,FsToolsTest,WorkspaceGatePolicyTest,WorkspacePolicyTest,CliPluginTest'` → tools 55 用例 + cli 7 用例零失败。全量交用户手动跑。

## 验收对照表（工单级，2026-09-15 备）

- 演示路径：`mvn -pl duo-harness-example -am package exec:java -Dexec.mainClass=dev.duo.harness.example.DuoMain`（工作目录 = `duo-harness-example`，即 workspace 根；需 `.env` 的 LLM 配置；浏览器 http://127.0.0.1:18080）
- 测试路径：`mvn test -pl duo-harness-tools -am` 与 `mvn test -pl duo-harness-cli -am`（套件叙述行：FsBashToolTest 10 用例 / BashTierLinkageTest 4 用例 / FsToolsTest 26 / WorkspacePolicyTest 9 / WorkspaceGatePolicyTest 6 / CliPluginTest 7）
- **审批路由提醒**：demo 装配里 `web` 行在 `cli` 之前——bash 的 ask 落 **Web 卡片**（非 danger 档一律 ask）。终端只会看到 `[调工具]` 之前的等待；作答在浏览器。
- **命令入口提醒**（2026-09-15 验收实测修正）：`/permission` 是 **REPL 内置命令**（ADR-0012，归 CLI 呈现位）——在**启动 demo 的终端窗口**输（提示 `会话 … /exit 退出` 的那个）；Web 聊天输入框不拦截斜杠命令，输进去会当普通消息发给模型（本验收第 8 步首跑即在此踩坑，属脚本口径缺陷非产品缺陷；Web 侧等效入口记 backlog）。

| # | 操作（对 agent 说 / 输入） | 应出现（逐字可指认） |
|---|---|---|
| 1 | 启动后输 `/permission` | `当前预设: workspace-write（可选: read-only / workspace-write / danger-full-access）` |
| 2 | "用 bash 执行 echo bash-ok" | Web 出现 `[待审批] 工具 bash 请求执行` 卡片（参数含 command）→ 点允许 → 终端 `  [调工具] bash {...}`、`  [工具结果] bash-ok` 且末行 `[exit code: 0]` |
| 3 | "用 bash 跑 pwd，再跑 ls"（或"看看当前工作目录有哪些文件"） | `pwd` 输出 = **启动 demo 进程的当前目录**（fs-tools 未配 `root` 时 workspace 根即进程 cwd：exec:java 从模块目录启动是 `duo-harness-example`，IDEA 从仓库根启动就是仓库根——2026-09-15 实测为后者，行为正确） |
| 4 | "用 bash 输出 $NO_COLOR/$TERM/$PAGER" | `1/dumb/cat`（env 硬化三项生效） |
| 5 | "用 bash 执行一个退出码为 3 的命令" | `  [工具结果] `（**不是** `[工具错误]`）+ `[exit code: 3]`；agent 据此自行继续而非报故障 |
| 6 | "用 bash 执行 sleep 30，timeoutMs 设成 500" | Web 卡片允许后 → `[timed out] 500ms limit reached; process tree terminated`（不等 30 秒） |
| 7 | "用 bash 输出 12 万个字符"（如 `printf '%120000s' '' \| tr ' ' 'a'`） | `[stdout truncated: 20000 chars omitted — narrow the command or redirect the rest to a file]`，不刷屏 |
| 8 | 在**终端 REPL**（非浏览器）输 `/permission danger-full-access`，再让它跑一条 bash | `已切换: danger-full-access`，随后**无 Web 卡片**直接执行（`[工具结果]` + `[exit code: 0]`）；再 `/permission workspace-write` 恢复——下一条 bash 又出卡片（danger 放行 / 其余档 ask 的两态对照） |
| 9 | 观察呈现（第 2 步的卡片与终端） | Web 工具卡为既有形态 `🔧 bash` + 可折叠结果（与 read/glob 卡同构，无 bash 专用样式）；审批卡为既有交互卡形态 |
| 10 | 测试路径两命令 | `BUILD SUCCESS`；上述套件叙述行可见，零失败 |

（可选观察，测试已锁定、非验收必过项）让 agent 跑 `sleep 30 & echo done`：命令退出后输出末尾出现 `[output may be incomplete: a background process still holds the stream]`。

通过条件：1-9 逐条现象与表一致且 10 全绿；用户明示"验收通过"后工单转 done。
不通过处理：现象与表不符即按 duo-bug-ledger 建档（症状留证 → 排查 → 修复），工单保持 in-progress，修复后重备验收件再验；不以"测试是绿的"作为反驳依据。
## 验收记录（2026-09-15 用户手动验证通过）

- **演示路径**（DuoMain + 浏览器，会话 20260915-165956-cf9a）：第 2 步 bash 首调出 `[待审批] 工具 bash` Web 卡片、批准后 `[工具结果] bash-ok` + `[exit code: 0]` ✔；第 3 步 `pwd` = 启动进程 cwd（本次从仓库根启动显示仓库根——workspace 根即进程 cwd，行为正确，对照表原措辞已按实测修正）✔；第 5 步 `exit 3` 呈现工具结果非错误 ✔；第 6 步 `sleep 30` + timeoutMs=500 即刻 `[timed out] 500ms limit reached; process tree terminated` ✔；第 7 步 12 万字符输出 `[stdout truncated: 20000 chars omitted]` 且模型说出 spill 落盘路径（`~/.duo/agent-sessions/<id>/spill/1-call_00_….txt`）——**"截断 + 回收"闭环端到端跑通** ✔；第 9 步 Web 工具卡 `🔧 bash` 与审批卡均为既有形态，零特化 ✔。
- **第 8 步首跑踩坑留痕**：`/permission danger-full-access` 在浏览器输入框被当普通消息透传给模型（`/permission` 是 REPL 内置命令，ADR-0012 归 CLI 呈现位）——对照表口径缺陷非产品缺陷，已修正为"终端 REPL 输入"；Web 面斜杠命令缺口记 backlog（交互路由小节）。模型事后自行 bash grep/sed 排查根因，两连审批-放行链路顺带再证 ask 管线正常。
- **测试路径**：用户全量 `mvn test` 确认通过（tools 55 + cli 7 等套件叙述行可见）。
- **验收期间修正**：对照表第 3 步（workspace 根 = 启动进程 cwd，非固定模块目录）、第 8 步（命令入口为终端 REPL）——均为脚本口径，不动产品代码。

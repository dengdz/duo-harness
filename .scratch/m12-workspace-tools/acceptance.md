# M12 验收件：本机工具族与权限预设（里程碑级）

> 覆盖工单 01-05 全部用户可见行为。实测快照取自 2026-09-15 用户手动验收的真实产物（工单 03/04 的页面与全量构建日志），按验收点分段标注——逐行对照，不凭描述猜。

## 演示路径（一条命令）

```bash
mvn -pl duo-harness-example -am package exec:java -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

浏览器 http://127.0.0.1:18080；终端 REPL 同窗口可用（`/permission`、`/exit`）。**审批路由**：web 行在 cli 前，ask 落 Web 卡片，终端等待非卡死。

## 测试路径

```bash
mvn -o test          # 全量（先杀 18080 上的旧 demo 实例，避免端口/会话锁冲突）
```

应见：10 模块全 SUCCESS；套件叙述行（中文）逐套件可指认——FsToolsTest 26 / FsBashToolTest 10 / BashTierLinkageTest 4 / WorkspacePolicyTest 9 / WorkspaceGatePolicyTest 6 / CliPluginTest 7 / ToolCatalogTest 1（打 `[对账] 工具目录覆盖 9 个注册工具`）等，共 10 模块约 400+ 用例零失败（0.6.0 基线 341 + M12 新增）。

## 对照表（演示逐条）

| # | 验收点 | 操作 | 应出现 |
|---|---|---|---|
| 1 | 装配收敛 | 启动后看控制台与状态面 | 无任何 MCP 挂载叙述；插件表 10 行（含 cli，无 McpClientPlugin/write-protector）；工具表 9 个、零 `mcp__` 前缀；标题"工具（本机 + MCP 远端）" |
| 2 | read 三帽窗口 | "读一下 pom.xml 的前 30 行" | `[调工具] read`（免审）+ 带行号结果 |
| 3 | read 续读 footer | "读取 duo-harness-agent 里最长的 java 文件的前 100 行"（诱导大文件） | 结果尾部 `(Showing lines … of N. Use offset=… to continue.)` 或 `(End of file - total N lines)` |
| 4 | read 二进制拒读 | "用 read 读 target/ 下任意 .jar" | `  [工具错误] [read 错误] 二进制文件，read 工具不支持` |
| 5 | write 区内免审 | "新建 hello-m12.txt 内容为 demo" | **无卡片**直接 `[工具结果] Created file` |
| 6 | 读前写闸门 | "把 hello-m12.txt 内容改成 v2"（未先 read） | `[工具错误] [write 错误] …先用 read…（读前写闸门）`→ agent 自行 read 后改写成功 |
| 7 | edit 四态 | "把 pom.xml 里的 duo-harness 改成 duo-harnessX"（多命中） | `[edit 错误] …N 处…replace_all`；正确唯一替换则 `Edited` |
| 8 | glob/grep | "glob 找 **/*.md；grep 搜包含 WorkspaceApproval 的 java" | 文件列表（修改时间倒序）/ `路径:行号:文本` 命中行；`.git` 下不出现 |
| 9 | bash 档位 ask | "用 bash 跑 ls" | Web 卡 `[待审批] 工具 bash` → 放行 → 输出 + `[exit code: 0]` |
| 10 | bash 非零退出 | "用 bash 跑一个退出码为 3 的命令" | `[工具结果]`（**非** `[工具错误]`）+ `[exit code: 3]` |
| 11 | bash 超时杀树 | "用 bash 跑 sleep 30，timeoutMs 设 500" | `[timed out] 500ms limit reached; process tree terminated`（即刻返回） |
| 12 | bash 截断 + spill 回读 | "用 bash 跑 printf '%120000s' '' \| tr ' ' 'a'" | `[stdout truncated: 20000 chars omitted — narrow the command or redirect the rest to a file]`；模型能说出 spill 落盘路径（`~/.duo/agent-sessions/<id>/spill/…`）并可 read 回读 |
| 13 | 越界写 ask | "往 /tmp/m12-escape.txt 写点东西" | Web 卡 ask（write 越界）；拒绝后文件不存在，模型给补救选项 |
| 14 | danger 直通 | 终端 `/permission danger-full-access` 再跑 bash | `已切换: danger-full-access`；**零卡片**直接执行 |
| 15 | read-only 全 ask | `/permission read-only` 后让它写区内文件 | 写与 bash 全部出卡；`/permission workspace-write` 恢复 |
| 16 | 呈现零特化 | 观察上述工具卡与审批卡 | `🔧 <工具名>` 合一卡（⟳ 运行中 → ✓/✗，结果可折叠）与 `[待审批]` 交互卡均为既有形态，无 bash/read 专用样式 |
| 17 | 文档对账 | 测试路径 | `[对账] 工具目录覆盖 9 个注册工具`；工具目录.md 含六件与 bash 输出语义 |

## 预期日志原文段（实测快照，2026-09-15）

**验收点 1（装配收敛）**——状态面插件表（实测）：

```
dev.duo.harness.tools.ToolsPlugin            ACTIVE
dev.duo.harness.tools.fs.FsToolsPlugin       ACTIVE
dev.duo.harness.agent.PromptPlugin           ACTIVE
dev.duo.harness.agent.AgentsMdPlugin         ACTIVE
dev.duo.harness.agent.SkillsPlugin           ACTIVE
dev.duo.harness.tools.InteractionPlugin      ACTIVE
dev.duo.harness.tools.fs.WorkspaceApprovalPlugin ACTIVE
dev.duo.harness.example.mcpfs.RepeatReminderPlugin ACTIVE
dev.duo.harness.web.WebPlugin                ACTIVE
dev.duo.harness.cli.CliPlugin                ACTIVE
```

**验收点 5/6（write 免审 + 闸门）**——实测审批日志行：

```
审批决策：工具=write 结果=ALLOW 策略=workspace          # 区内写档位短路（03 验收实测）
```

**验收点 9/13/14（档位联动三态）**——BashTierLinkageTest 断言同路径的实测审批日志（tools 模块套件输出）：

```
审批决策：工具=bash 结果=ALLOW 策略=workspace            # danger 档短路（同 write）
审批决策：工具=bash 结果=ALLOW 策略=interactive          # ask 放行（回答者 y）
审批决策：工具=bash 结果=DENY 策略=interactive           # ask 拒绝（回答者 n）
```

**验收点 10/11/12（bash 结果语义）**——FsBashToolTest 实测（10 用例套件）：

```
[exit code: 3]
[timed out] 300ms limit reached; process tree terminated
[stdout truncated: 20000 chars omitted — narrow the command or redirect the rest to a file]
[output may be incomplete: a background process still holds the stream]
```

**验收点 17（对账）**——ToolCatalogTest 实测输出：

```
[对账] 工具目录覆盖 9 个注册工具
```

**全量基线**——2026-09-15 实测（04 验收日志）：10 模块全 SUCCESS，`BUILD SUCCESS`，39.2s，零失败零错误。

## 通过条件与不通过处理

- 1-17 逐条与表一致 + 测试路径全绿 + 两处视觉确认（状态面标题、插件表 10 行）→ 用户明示验收通过，M12 收官（0.7.0 可发布）。
- 任一条不符：duo-bug-ledger 建档留证 → 修复 → 重备本验收件再验；不以"测试是绿的"反驳。

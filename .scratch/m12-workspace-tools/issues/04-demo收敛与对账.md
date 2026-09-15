# 04: demo 收敛与对账

## What to build

agent-demo.yml 移除 MCP files 沙箱挂载（本机工具取代——消除双写工具选型噪音）+ 全部对账更新：工具目录对账测试（六件入册、`mcp__files__*` 移除）、CliPluginTest 端到端形态断言、运行Demo 验收叙述同步。

## Blocked by

02, 03（本机文件与执行能力就位后才收敛 demo）

## Status
done（2026-09-15 验收通过）

## Checklist
- [x] AgentReplMain 演示装配回调收敛（MCP 挂载段移除；演示提示片段去留裁定并留痕）
- [x] 工具目录对账测试更新：六件入册、`mcp__files__*` 移除断言
- [x] CliPluginTest 端到端形态断言更新（MCP 工具不再可用——相关用例改用本机工具或移除）
- [x] 运行Demo 验收叙述同步（工具名、审批示例形态）

## Comments

- **AgentReplMain 收敛**：`mountDemoExtras` 整体移除，类退化为"固定装载 agent-demo.yml 的 DuoMain 别名"（兼容壳，启动命令不变，ADR-0011）。**演示提示片段裁定：移除**——`demo:platform`"执行文件操作前先确认目标路径"是 MCP 沙箱时代无路径约束的补丁；本机六件自带 workspace 解析 + 读前写闸门 + 档位审批，保留会诱发模型在区内写前多余确认，与"区内写免审、越界才 ask"的演示语义打架。mcpfs 模块与 MCP 集成代码按 spec 保留（demo.yml / demo-m2.yml 的 M1/M2 机制演示不动，DemoMainTest 照常绿）。
- **agent-demo.yml**：① fs-tools 注释五件→六件（补 bash）；② 移除 `write-protector` 行——其 ask/guard 目标是 `mcp__files__write_file/read_file`，MCP 挂载移除后即成无操作死行（写保护演示的真实落点是 demo-m2.yml，经 DemoMain 的 M2 段装载——初稿误写 demo.yml，审查轮修正）；③ 尾注 MCP 句改"不再随 demo 挂载"并指向 demo.yml / demo-m2.yml。
- **ToolCatalogTest**：MCP 编程挂载段移除（不再起 MiniFileSystemServer 子进程——顺带消掉"150+ 僵尸进程"的主要制造源）；新增两组断言：注册表无 `mcp__` 前缀工具、fs 六件（read/write/edit/glob/grep/bash）逐个在册。实测对账覆盖 9 个注册工具（fs 六件 + ask_user + exit_plan_mode + skill）。
- **CliPluginTest / CliPluginAssemblyTest 核对结论：无需改**——cli 测试 fixture 从未挂 MCP（cli-assembly-test.yml 无 MCP 行），端到端形态用例（`bashToolRendersThroughGenericToolLines`）在 03 已改用本机 bash。
- **文档同步（同 diff）**：`工具目录.md` MCP 段从"demo 的 files 服务器"详解改为一节机制说明（`mcp__<server>__<tool>` 命名 + demo.yml/demo-m2 保留演示）；`运行Demo.md` 工具循环 REPL 段整体改写为本机工具族场景表（read/write 免审与 ask、bash 一律 ask、`/permission` 切档）；`组装你的第一个agent.md` 对齐 0.7.0（装配表补 fs-tools 去 write-protector 补 cli 行、第三步实验从"拆审批→写直接执行"改为"拆审批→未配置即拒 fail-closed"——原叙述与现在的静态 requiresApproval 语义相反，属安全相关修正、第四步改"默认不挂 MCP"）；`插件配置参考.md` 去 write-protector 两处（yml 样例 + 逐行注解；全量 fs/workspace 行同步留 05）。
- 验证证据（本机 mvn，2026-09-15）：`mvn -o -pl duo-harness-example,duo-harness-cli -am test -Dtest='ToolCatalogTest,AgentReplMainTest,DemoMainTest,CliPluginAssemblyTest,CliPluginTest'` → example 5 + cli 8 全绿，`[对账] 工具目录覆盖 9 个注册工具`。

## 验收对照表（工单级，2026-09-15 备）

- 演示路径：`mvn -pl duo-harness-example -am package exec:java -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain`（启动命令不变；浏览器 http://127.0.0.1:18080）
- 测试路径：`mvn test -pl duo-harness-example -am`（套件叙述行：ToolCatalogTest / AgentReplMainTest 3 用例 / DemoMainTest）

| # | 操作（启动后观察 / 对 agent 说） | 应出现（逐字可指认） |
|---|---|---|
| 1 | 启动后看控制台与 Web 状态面工具清单 | **无任何 MCP 挂载叙述**（无 McpClientPlugin / MiniFileSystemServer / notes.txt）；工具清单 = read/write/edit/glob/grep/bash + ask_user + skill + exit_plan_mode，**无 `mcp__` 前缀** |
| 2 | "读一下 pom.xml 的前 30 行" | `[调工具] read`（读类免审，无卡片）+ 带行号结果 |
| 3 | "新建 hello-m12.txt 内容为 demo" | `[调工具] write` **免审**直接 `[工具结果] Created file`（区内写档位放行——不再有 MCP 时代的双写工具可供选择） |
| 4 | "往 /tmp/m12-escape.txt 写点东西" | 越界写 → Web 卡片 `[待审批] 工具 write`；拒绝后文件不存在 |
| 5 | "用 bash 跑 ls" | Web 卡片 ask → 放行 → 输出 + `[exit code: 0]` |
| 6 | 问它"你能看到哪些工具" | 回答里只有本机工具（无 mcp__files__read_file / write_file）——模型选型无噪音 |
| 7 | 测试路径 | `BUILD SUCCESS`；ToolCatalogTest 打出 `[对账] 工具目录覆盖 9 个注册工具`，AgentReplMainTest 3 用例绿 |

通过条件：1-6 逐条现象与表一致且 7 全绿；用户明示"验收通过"后工单转 done。
不通过处理：现象与表不符即按 duo-bug-ledger 建档（症状留证 → 排查 → 修复），工单保持 in-progress，修复后重备验收件再验；不以"测试是绿的"作为反驳依据。

## 验收记录（2026-09-15 用户手动验证通过；证据核验制——用户亲手跑演示与全量构建，贴回页面与日志，由实现方逐条对照核验）

- **演示路径**（Web 页面，会话 20260915-174057-8567）：① 状态面插件表无 McpClientPlugin / WriteProtectorPlugin（repeat-reminder 保留 ✓），工具表 9 个 = fs 六件 + ask_user + skill + exit_plan_mode，**零 `mcp__` 前缀**；② `read pom.xml` 免审直出带行号窗口；③ `write hello-m12.txt` **区内写免审**直接成功——MCP 时代"写必弹卡"的时代结束；④ `/tmp/m12-escape.txt` 越界写出卡、拒绝生效（✗ 失败），模型自述"被拒绝（策略: interactive，回答者: web）"并给三条补救选项；⑤ `bash ls` 出卡 → 放行 → 输出 + 退出码 0，`hello-m12.txt` 落在 workspace 根；⑥ 工具清单问答：模型列出 9 个本机工具，明确"没有 mcp__files__read_file / write_file"——选型噪音消除。
- **测试路径**（用户跑 `mvn -pl duo-harness-example -am package exec:java` 全量日志，两次构建，末次 10 模块全 SUCCESS / BUILD SUCCESS / 39.2s）：core 75 / tools 99（含 bash 三套件 20 用例）/ mcp 19 / llm 25 / session 20 / agent 50 / web 32 / cli 16 / example 12——**零失败零错误**；ToolCatalogTest 在册（对账 9 工具，无 mcp__ 断言过）；DemoMainTest 的 M1/M2 MCP 机制演示（demo.yml）照常绿——mcpfs 模块按 spec 保留的落证。
- **验收期间顺手修正**：状态面标题"工具（含 MCP 远端）"改"工具（本地 + MCP 远端）"（demo 已不挂 MCP，原标题误导）——UI 文案一行，待 05 里程碑验收时随演示视觉确认。
- **观察项（不阻断，留确认）**：用户所贴状态面插件表 9 行、缺 cli 行（yml 10 行的最后一个）——后端 `snapshots()` 无过滤、前端全量渲染，疑似粘贴截行；请下次启动时确认终端 REPL 提示行在、状态面插件表 10 行。若确缺再按 duo-bug-ledger 建档。

## 审查轮（2026-09-15，duo-code-review：OCR 端点仍 403 → 委托自查 + Standards/Spec 双轴）

- **Spec 轴实修**：仓库根残留演示产物 `hello-m12.txt`（验收第 3 步产物，workspace 根=仓库根）——已删；工单本 Comments"写保护演示保留在 demo.yml"措辞不准——实为 demo-m2.yml（DemoMain M2 段装载），已修正。
- **Standards 轴实修**：注释/文档泄漏 4 处改现在时陈述（agent-demo.yml 尾注、ToolCatalogTest 断言注释、工具目录.md MCP 段、AgentReplMain JavaDoc）；状态面文案"本地"改"本机"（全库术语对齐 ADR-0012）；AgentReplMain 补文件尾换行。
- **判为不修**：测试视图接口与 json helper 的"重复"——视图接口是容器既定约定（方法名即服务名）、helper 夹具自成一体；ToolCatalogTest 注释引用 BUG-20260915-02 依赖 `.scratch/bugs/` 台账——提交时随 diff 入库即可解析。
- **上抛 blocker（归属 05，已排期）**：`插件配置参考.md` 全量滞后（无 fs-tools/WorkspaceApprovalPlugin 行、approval 仍写 InteractiveApprovalPlugin、"二选一"应为三选一、状态行 M8）——05 的 checklist 本就含"插件配置参考全量同步"，**提交建议放在 05 完成后**，避免提交带 blocker 级文档漂移。

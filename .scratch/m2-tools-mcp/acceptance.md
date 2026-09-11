# M2 验收对照表

> duo-acceptance 里程碑级验收件。含**预期日志原文**（实测快照）——跑完命令逐行对照，不用凭描述猜。全部通过后 5 张工单的 done 才完整闭环。

## 运行命令

测试路径（120 用例）：

```bash
mvn clean test
```

演示路径（M1 + M2 全链路）：

```bash
mvn -pl duo-harness-example -am package exec:java
```

IDEA 等价路径（输出与上述一致，在 Run 工具窗核对）：

- **测试**：测试类（或包/模块）右键 → Run Tests
- **Demo**：打开 `DemoMain` → main 方法旁绿色箭头 → Run（M1 段之后自动接 M2 段）
- **玩法循环**：改 `demo.yml` / `demo-m2.yml` 后直接 rerun（自动重编译资源）

## 预期日志 A：测试路径（mvn clean test）

输出中应依次出现 11 条套件叙述（顺序随执行而定，内容与数字逐条核对）：

```
=== 套件：MinimalPluginLoopTest —— 插件闭环：apply 运行、config 严格绑定（缺字段/类型错点名）、effect 逆序回滚、销毁后拒绝注册（12 用例） ===
=== 套件：ServicesTest —— 服务注入：provide/Service 基类发布、as 视图寻址（方法名即服务名+类型校验）、inject 声明许可、依赖缺失挂起、注销级联停依赖方（14 用例） ===
=== 套件：LifecycleTest —— 六态与 epoch：状态迁移事件广播、依赖消失回 PENDING、服务回归/换实现自动重启、失败隔离（FAILED 兄弟无感）、DISPOSED 终态不复活（10 用例） ===
=== 套件：EventsTest —— 事件五模式：emit 异常隔离、waterfall 否决/参数改写/返回包装、serial·bail 顺序投票、parallel 并发聚合、监听器随作用域摘除（19 用例） ===
=== 套件：BootTest —— 配置驱动 boot：行加载（行序无关）、审计点名（缺失服务/坏配置定位/重复 id/未知类）、disabled 行跳过、失败整树回滚（14 用例） ===
=== 套件：ToolsServiceTest —— 工具域三段管线：pre 准入否决（否决后本体不执行）、execute around 包装、post 结果改写与转错误、工具异常收敛 error 结果、插件停止自动注销工具（10 用例） ===
=== 套件：ApprovalPolicyTest —— 审批策略：always-deny 拒绝且点名策略、auto-approve 白名单内外行为差异、ask 委托策略裁决、未配置即拒、未被声明不介入、MCP 工具同路径、决策日志可观察、yml 配置端到端（9 用例） ===
=== 套件：OutputContractAndGuardTest —— output 契约：违约点名/合规放行/未声明透传；guard：理由拒绝/null 放行/顺序短路/拒绝不可翻回/时机在审批后/随作用域失效（10 用例） ===
=== 套件：ReconnectPolicyTest —— （4 用例） ===
=== 套件：ConnectionLifecycleTest / McpClientPluginTest / McpToolSyncTest —— 连接生命周期 4 / 内核级 3 / 工具同步 8 ===
=== 套件：DemoMainTest —— Demo 冒烟：一条命令跑通，输出叙述覆盖 M1（boot/服务注入/三段管线/级联停止/整树回滚）+ M2（MCP 连接/远端工具/审批/guard/拔除消失）（1 用例） ===
```

末尾汇总（core 69 / tools 29 / mcp 19 / example 3，合计 120，0 失败 0 错误）。

测试固有的预期现象（不是缺陷）：EventsTest 的"监听器抛错（已隔离）"warn；mcp 用例的
`TimeoutException` 栈（exit/once 模式夹具启动即退出，握手必然超时——正是被测行为）；
`Process terminated with code 7/143`（夹具 System.exit 与测试 cleanup 的 kill）；
exec:java 结束时 2 条 reactor 线程 linger 警告（SDK 全局 scheduler 为 JVM 级共享资源）。

## 预期日志 B：演示路径（Demo，实测快照）

> 下文为输出原文（stderr 的审计日志在命令行下交织在对应幕内；IDEA 控制台可能把
> stderr 集中显示在尾部——内容一致）。`# ←` 注释行是验收导航（实际输出无此行）。
> 段前 `§` 标注对应工单。

### § 工单 01–04 的 M1 部分（boot / 三段管线 / 级联）

```
=== duo-harness M1 Demo：插件化全链路 ===
[boot] 读取 demo.yml
  [状态] ToolsPlugin: PENDING -> LOADING
  [状态] ToolsPlugin: LOADING -> ACTIVE
  ...
[工具] 正常执行:
  -> [正常] echo:世界 [已治理]
[工具] 准入否决（参数含敏感词）:
  -> [错误] 工具 "echo" 执行被拒绝: 参数含敏感词（治理插件否决）
[工具] post-execute 结果治理:
  -> [正常] echo:第二次调用 [已治理]
[级联] 运行时挂临时服务提供者与消费者，再拔掉提供者:
  ...
[级联] 提供者已停——消费者经 UNLOADING 回到 PENDING（见上方状态叙述）
  ...
=== Demo 结束（整树已回滚） ===
```

（M1 段与 0.1.0 的验收件一致，完整快照见 `.scratch/m1-plugin-kernel/acceptance.md`。）

### § 工单 01 连接与工具同步（M2 段开始）

```
=== duo-harness M2 Demo：MCP 连接与治理链 ===
[M2] 临时目录就绪: duo-m2-demo…（notes.txt / secret.txt 已写入磁盘）
[M2] boot demo-m2.yml（tools / 审批 always-deny / 写保护）
  [状态] ToolsPlugin: PENDING -> LOADING
  [状态] ToolsPlugin: LOADING -> ACTIVE
  [状态] ApprovalPlugin: PENDING -> LOADING
  [状态] ApprovalPlugin: LOADING -> ACTIVE
  [状态] WriteProtectorPlugin: PENDING -> LOADING
  [状态] WriteProtectorPlugin: LOADING -> ACTIVE
[M2] 挂载 MCP 连接（等价 yml 行: serverName=files, command=java, args=[-cp, <classpath>, MiniFileSystemServer, duo-m2-demo…]）:
  [状态] McpClientPlugin: PENDING -> LOADING
[mcp-conn-files] INFO dev.duo.harness.mcp.internal.ConnectionSupervisor - MCP 服务器 [files] 已连接
[mcp-conn-files] INFO dev.duo.harness.mcp.internal.McpToolSync - MCP 服务器 [files] 工具同步完成：2 个工具已注册
  [状态] McpClientPlugin: LOADING -> ACTIVE
  [M2] 连接状态: ACTIVE（远端工具已自动同步进工具域）
```

### § 工单 02 远端工具读真实文件

```
[M2] read_file(notes.txt)——经三段管线调用远端 filesystem server:
  -> [正常] M2 演示：这是 notes.txt 的真实内容
```

### § 工单 04 guard 单调否决（涉密拦截，署名 guard）

```
[M2] read_file(secret.txt)——guard 治理（涉密拦截）:
[...DemoMain.main()] INFO dev.duo.harness.tools.internal.ToolsServiceImpl - guard 拒绝：工具=mcp__files__read_file 理由=禁止读取涉密文件
  -> [错误] 工具 "mcp__files__read_file" 执行被拒绝: 禁止读取涉密文件（guard）
```

### § 工单 03 审批策略拒绝（写操作被声明 ask，always-deny 拒绝）

```
[M2] write_file——写操作被声明需审批，always-deny 策略拒绝:
[...DemoMain.main()] INFO dev.duo.harness.tools.internal.ToolsServiceImpl - 审批决策：工具=mcp__files__write_file 结果=DENY 策略=always-deny
  -> [错误] 工具 "mcp__files__write_file" 执行被拒绝: 被审批策略拒绝（策略: always-deny）
```

### § 工单 02 拔连接 → 工具消失

```
[M2] dispose MCP 连接:
  [状态] McpClientPlugin: ACTIVE -> UNLOADING
  [状态] McpClientPlugin: UNLOADING -> DISPOSED
  -> [消失] 工具 "mcp__files__read_file" 未注册
  [状态] WriteProtectorPlugin: ACTIVE -> UNLOADING
  [状态] WriteProtectorPlugin: UNLOADING -> DISPOSED
  [状态] ApprovalPlugin: ACTIVE -> UNLOADING
  [状态] ApprovalPlugin: UNLOADING -> DISPOSED
  [状态] ToolsPlugin: ACTIVE -> UNLOADING
  [状态] ToolsPlugin: UNLOADING -> DISPOSED
=== M2 Demo 结束（整树已回滚） ===
```

## 手动玩法

| 操作 | 预期标志行 |
|---|---|
| 在临时目录手动放一个 `hello.txt`，改 DemoMain 的 read_file 参数读取 | `-> [正常] <你写的内容>` |
| 把 `demo-m2.yml` 的审批 policy 改为 `auto-approve` + 白名单 `["mcp__files__write_file"]` | write_file 变 `-> [正常] 已写入 injected.txt…`，临时目录出现该文件 |
| 删掉 `WriteProtectorPlugin` 的 guard 注册（或注释 demo-m2.yml 的 write-protector 行） | secret.txt 读取变 `-> [正常] 绝密内容…` |

（玩法后恢复：`git checkout -- duo-harness-example` 。）

## 结果记录

- 2026-09-11 演示路径：`mvn -pl duo-harness-example -am package exec:java` 一条命令全链路（package 阶段含全部测试 + exec:java M1+M2 演示），BUILD SUCCESS（31.5s）；Demo 叙述逐段核对通过（含 IDEA Run 窗二次确认）。
- 2026-09-11 测试路径：同上命令的 package 阶段——core 69 / tools 29 / mcp 19 / example 3，合计 120 用例 0 失败 0 错误。

## 结论

- M2 五张工单（01–05）全部验收闭环（2026-09-11）。

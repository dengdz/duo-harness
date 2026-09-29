# C2 处置验收（0.24.0，19 票）

粒度：里程碑级。行为等价类由测试路径承载；行为变更类（P1×3 + 用户可感修复）需交互实测。

## 路径 1 · 全仓测试（等价类证据——骨架抽取/单点收敛/清理批的行为不变）

```bash
./mvnw verify
```

预期：`BUILD SUCCESS`；各套件 `@BeforeAll` 叙述行上屏（重点新套件）：`SpillLedgerTest`（3 用例）、`AdapterParityTest`（双协议面对拍 3）、`InteractionRequestApprovalTest`（4）、`ToolTimeoutsTest`（3）、`McpToolNamesTest`（3）、LifecycleTest 11（含 disposedScopeRejectionsAreTyped）。**实现期已自跑绿（2026-09-28，多轮）。**

## 路径 2 · 配置口径点名（工单 19 行为变更——已自跑通过，附实测）

构造 `agents-md` 行 `config: {budgetChars: 0}` 的 yml，headless 启动。实测输出（2026-09-29 自跑）：

```
[agents-md] 启动失败（state=FAILED）: PluginException: agents-md.budgetChars 须为正整数: 0
```

此前行为：静默回退 64KB。**此路径已验证，无需重复。**

## 路径 3 · CLI REPL：审批序号跨轮复位（工单 03，P1）

```bash
./mvnw -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.DuoMain
```

（demo yml 缺省为 web+cli 双面；CLI REPL 就绪后）

1. 第一轮：发一条会触发 bash 工具的消息（如「用 bash 看一下当前目录」），审批提示按 `y` 放行，等回答完成
2. 第二轮：再发一条触发审批的消息
3. **对照**：
   - ✅ 第二轮首项审批呈现为 `[待审批] 工具 bash 请求执行`（**不带**「（本轮第 2 项审批）」——修复前误显跨轮累计序号）
   - ✅ 会话轮换（`/new`）后新轮首项同样零噪声
   - ✅ `/exit` 正常退出、会话锁释放提示正常

## 路径 4 · Web 面：侧栏双开保护 + 并发作答（工单 02/07，P1）

同一实例（路径 3 的服务已含 Web 面，横幅有 `http://127.0.0.1:<port>/?token=…`）：

1. **侧栏不放锁（02）**：浏览器开 Web 面发起一轮对话（会话活跃）→ 刷新页面数次（侧栏每次重拉 `/api/sessions`）→ 另开终端再起一个 CLI 实例（同命令，新实例会尝试 resume 最新会话）
   - ✅ 第二实例提示「会话被占用，改为新建会话继续」——**修复前**侧栏每刷一次锁就被放掉，第二实例可直接双开同一会话（双写分脑）
2. **作答不串卡（07）**：让模型先发一张提问卡（如问选择题）——保持未答；同轮再触发一张计划卡（或先用一张提问卡 + 一张审批卡验证）
   - ✅ 先答第二张卡 → 第一张不受影响；再答第一张 → 各自拿到自己的答案（修复前「答最旧」会串卡）
3. ✅ Web 面审批卡、计划卡、SSE、导出等既有功能无回归

## 路径 5 ·（可选）task-output 长等待不腰斩（工单 09）

CLI 中让模型把一个长任务转后台（`run_in_background`）并 `task-output` 等 `timeoutMs: 200000`（> 默认管线 120s）：✅ 等待满程返回运行状态/完成，**修复前** 120s 处被管线以通用超时错误腰斩。耗时 2-3 分钟，可跳过（结构断言已由 ToolTimeoutsTest 锁）。

## 通过判据

路径 1/2 已由实现方自跑确认；路径 3/4 为人工实测面——四项对照全 ✅ 即 C2 验收通过，19 张工单置 done。

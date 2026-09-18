# 02: awaitStartup 可配超时 + 静默等待点名

**What to build:** 编程挂载的开发者用 `awaitStartup(Duration)` 限时等待——超时拿到点名缺失服务的异常，快速失败且知道在等谁；无参版语义不变（服务晚到会被唤醒是合法模式），但进入等待时日志点名，"静默卡死"从根上消失。

**Blocked by:** None (can start immediately)

**Status:** done（2026-09-18 用户验收日志核对通过：AwaitStartupTest 5 用例绿、等待点名日志三现）

语义权威：ADR-0019 决策 10/12；backlog 搭车原文"测试进程收割随 awaitStartup 超时语义一并评估"。

- [x] 新增重载 `awaitStartup(Duration timeout)`：超时抛点名异常，消息含缺失服务清单（"缺失服务： a, b"——与 BootLoader 审计文案同源，缺失清单逻辑与 01 的抽取共用，不重写）
- [x] 超时后插件保持 PENDING：后台服务就绪照常激活，再次 await 可成功；异常沿调用方路径走（apply 内抛 = 只杀本实例，容器既有语义承接）
- [x] 无参版语义不变（无限等待），进入等待时打一条 INFO 日志点名在等哪些服务
- [x] 超时来源 = 调用方传参，不加全局配置键
- [x] 测试进程收割限时评估（backlog 搭车项）：结论二选一——方案很小顺手修（带修复与测试），或方案与规模写清继续挂账；不无限展开
- [x] 测试走接缝 B（编程 API）：超时异常点名、超时后激活仍可达、无参版等待日志可见

## Comments

**实现摘要（2026-09-18）**

- `PluginHandle` 新增重载 `awaitStartup(Duration)`（零时长 = 立即探测；负时长 IllegalArgumentException）；`PluginInstance.await(Duration)`：超时抛点名异常——消息含状态、缺失清单（复用 01 的 `missingHardDependencies()`）、"插件保持等待，可再次 awaitStartup"指引；无参版语义不变，`announceWaitIfPending()` 在确将阻塞时打 INFO 点名（静态口径抽为包内 `waitAnnouncement()` 供断言）。

**裁定留痕**

- 超时消息的缺失清单对 LOADING 态（依赖已在、apply 未完）自适应为"无缺失硬依赖（apply 可能仍在进行）"，避免出现"缺失服务: []"的空点名。
- 日志行的自动断言采取"静态口径直测 + 动态打日志不进断言"：slf4j-simple 输出流在 JVM 内初始化后不可靠重定向，捕获式断言会引入 flaky——外部行为（超时点名）已有硬断言，日志行由演示路径人眼确认。

**测试证据**

- core 89 例全绿：新增 `AwaitStartupTest` 5 例（超时点名且保持 PENDING / 超时后服务到达仍激活 / 零时长立即探测 / 负时长拒绝 / 等待点名口径）。

**验收件（待手动验证——本单为编程 API，以测试路径为主）**

| # | 动作 | 应出现 |
|---|---|---|
| T1 | 跑 `./mvnw -pl duo-harness-core test` | 套件叙述行 `AwaitStartupTest —— awaitStartup 可配超时：超时点名缺失、超时后可激活、零时长探测、负时长拒绝、等待点名口径（5 用例）` 全绿 |
| T2 | 同输出内找日志行 | `等待插件 …首次启动：缺失服务 [late-service]（服务就绪后自动激活）`（等待点名日志，INFO） |
| T3 | backlog 复核 | "awaitStartup 超时语义"条目已勾销账；"测试进程收割"条目含 M18-02 评估结论（方案记档继续挂账） |

**搭车评估结论（测试进程收割，backlog 同 diff）**

- awaitStartup 超时与点名日志已消除"编程挂载静默卡死"这一测试挂起成因；与本条两形态（fork 强杀遗留 stdio 子进程、REPL 线程阻塞 IO）均正交。
- stdio 子进程兜底方案已写清：ConnectionSupervisor 持活动连接注册幂等 shutdown hook、JVM 退出时 destroyForcibly，约 40 行；但 fork 强杀路径无法在 surefire 内稳定复现，测试成本与收益不成比例——**方案记档，继续挂账**（backlog 已更新）。

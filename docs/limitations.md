# 已知限制

本清单是仓库已知限制的唯一权威来源（duo-doc-standards 约定）：任何"暂不可用/未接线/有意推迟"的表述以这里为准，别处不再重复维护。消除某条限制时同一 diff 内删除对应条目并记 CHANGELOG。

## 遗留（自 0.1.0，跨版本仍有效）

| # | 限制 | 说明与去向 |
|---|---|---|
| 1 | 运行时行级配置热重载不可用 | `Boot.from(yml)` 为一次性引导，改配置需重启进程；HMR 与 DSH 式 Entry.update 行对账属后续里程碑 |
| 2 | `bail` 与 `serial` 语义等价 | 同步阻塞模型下无差异（DSH 的差异源于异步监听器），bail 保留词汇（ADR-0001 决策） |
| 3 | recheck 的真并发路径未经多线程测试 | 六态迁移经 ReentrantLock 串行化 + LOADING dirty 标志兜底，单线程语义已测；跨线程同时 provide/remove/dispose 的深度竞态为已知未测区 |
| 4 | isolate 多作用域隔离不可用 | 注册表二阶键已预留，Realm/作用域注入/迁移未实现（M1 决策） |
| 5 | 独立 jar 热加载不可用 | 同 classpath + 配置声明模型（ADR-0001），child Classloader 动态加载保留为扩展点 |
| 6 | Web 双面界面未开始 | agent 循环已落地（M3 单次对话 → M4 上下文 → M5 工具循环，ADR-0007 渐进序列）；图形界面排在 M8 |

## M2（0.2.0）

| # | 限制 | 说明与去向 |
|---|---|---|
| 1 | guard 的"仅作用域内调用生效"不可用 | guard 现为随注册作用域销毁摘除 + 全局生效（内核无调用方作用域概念，工单 04 决策）——内核补 ambient 作用域机制后可收敛 |
| 2 | MCP Java SDK 与 networknt 校验器版本耦合 | `mcp-json-jackson2:0.18.1` 硬依赖 networknt json-schema-validator 2.0.0；升级 SDK 时必须同步核对该版本（1.5.0 会因缺类在子进程抛 NoClassDefFoundError） |
| 3 | demo 的 MCP 子进程 classpath 依赖运行环境探测 | `subprocessClasspath` 先取 `java.class.path`（IDEA/裸 java）、再枚举 ClassRealm URL（exec:java）；非标准 classloader 下探测会失败——仅 demo 受影响，核心模块无此依赖 |

## M5（未发布）

| # | 限制 | 说明与去向 |
|---|---|---|
| 1 | 思考内容（reasoning_content）不落会话日志 | 思考内容仅在 agent 循环内存中逐轮传递、不写入 JSONL——恢复历史会话时历史工具调用消息不带思考内容；provider 只要求最近一轮回传，实际影响为零 |

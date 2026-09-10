# 已知限制

本清单是仓库已知限制的唯一权威来源（duo-doc-standards 约定）：任何"暂不可用/未接线/有意推迟"的表述以这里为准，别处不再重复维护。消除某条限制时同一 diff 内删除对应条目并记 CHANGELOG。

## M1（0.1.0）

| # | 限制 | 说明与去向 |
|---|---|---|
| 1 | 运行时行级配置热重载不可用 | `Boot.from(yml)` 为一次性引导，改配置需重启进程；HMR 与 DSH 式 Entry.update 行对账属后续里程碑 |
| 2 | `bail` 与 `serial` 语义等价 | 同步阻塞模型下无差异（DSH 的差异源于异步监听器），bail 保留词汇（ADR-0001 决策） |
| 3 | recheck 的真并发路径未经多线程测试 | 六态迁移经 ReentrantLock 串行化 + LOADING dirty 标志兜底，单线程语义已测；跨线程同时 provide/remove/dispose 的深度竞态为已知未测区 |
| 4 | isolate 多作用域隔离不可用 | 注册表二阶键已预留，Realm/作用域注入/迁移未实现（M1 决策） |
| 5 | 工具域强化件部分缺位 | 并发安全分类、scope 遮蔽未实现——后续交付（输出契约与 guard 已落地；guard 的"仅作用域内调用生效"因内核无调用方作用域概念，现为生命周期局部 + 全局生效） |
| 6 | Web 双面 / agent 循环未开始 | M2 仅剩端到端验收 Demo（工单 05）；M3（页面插件）/ M4（agent+LLM）见 ADR-0004 路线图 |
| 7 | 独立 jar 热加载不可用 | 同 classpath + 配置声明模型（ADR-0001），child Classloader 动态加载保留为扩展点 |

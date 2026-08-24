# Changelog

本文件记录 duo-harness 的用户可见变更。版本号规则见 `.agents/skills/duo-workflow/references/版本号.md`。

## 0.1.0（未发布）

### Added

- 引入 agent 工程规范体系：`AGENTS.md` 路由总纲（需求分流、显式命令、红线）
- 引入 9 个 `.agents/skills/` 技能：duo-workflow（含版本号规范）、duo-code-review、duo-tracker、duo-pre-push-checks、duo-release-workflow、duo-prose-standard、duo-project-structure、duo-trim-cot-leakage（含示例与检索模式集）、duo-doc-standards
- 引入领域文档 `docs/agents/`：`domain.md`（术语表）、`issue-tracker.md`（本地工单规范）
- 引入 `.gitignore`（构建产物、IDE、密钥环境、日志；`.scratch/` 随库入库）
- 引入 duo-research 技能与 `docs/research/` 研究资产：首份产出为 DSH 项目总览（架构、模块、数据流、技术栈、目录结构）
- 新增 DSH 插件化架构研究三件套（`docs/research/DSH/插件化架构/`）：Cordis 容器核心（Context/Fiber/Registry/Reflect/Events）、profile→bundle patch 层序合成、工具/MCP/页面三类插件的插拔机制、生命周期与依赖驱动启停、双面模块系统——为从零构建插件化框架提供架构参考
- 完成插件化框架方案 grilling：建立术语表 `CONTEXT.md`（13 个领域术语）与四份 ADR（自研插件容器内核、同步 API 与虚拟线程、Jackson 统一序列化与配置绑定、三支柱先行路线图）
- 发布 M1 spec（`.scratch/m1-plugin-kernel/spec.md`）：插件容器内核 + 示例插件的 25 条用户故事、全部实现决策（含配置行结构与六态状态机浓缩形状）、双接缝测试方案
- M1 spec 拆分为 7 张 tracer-bullet 工单（`.scratch/m1-plugin-kernel/issues/01~07`）：依赖拓扑 01→{02,03}→04→05→07、06 旁路可并行，每张含验收清单
- 工单 01 落地：duo-harness 首个代码模块 `duo-harness-core`（插件容器内核最小闭环）——Plugin/Context/Disposable 公共 API、config record 的 Jackson 绑定（失败即加载失败、点名插件与字段路径）、副作用栈逆序回滚与级联停子、销毁后拒绝注册；9 个接缝 B 用例全绿；docs 立起 index 导航与架构篇（模块划分）
- 审查修复（行为变更）：config 绑定启用严格模式——record 缺字段由 Jackson 默认补 null/0 改为绑定失败（错误前移）；声明了 config 类型却未提供配置由静默传 null 改为点名报错；父作用域并发销毁时已启动子实例不再泄漏；销毁后注册/加载的拒绝行为与 null 参数快速失败写入 JavaDoc 契约
- 工单 02 落地：事件总线五种分派模式（emit 隔离 / parallel 虚拟线程并发聚合 / serial·bail 顺序投票 / waterfall 洋葱管线——否决、参数改写、返回值包装）；监听器表全树共享、注册即作用域副作用随插件停止自动摘除；引入 SLF4J（api + simple test），清理与隔离错误改为可观察的 warn 日志；30 用例三连跑全绿
- 工单 03 落地：服务注册表与视图接口寻址——provide/Service 基类（构造即发布）、`ctx.as(视图)` 动态代理（方法名即服务名、惰性解析、类型校验）、`Plugin.inject()` 依赖声明（未声明读取点名拒绝）、依赖缺失挂起 + awaitStartup 真实阻塞语义、服务注销与提供方插件停止的级联传导（依赖方自动停止）；注册表 (服务名, 作用域) 二阶键 isolate 预留；44 用例三连跑全绿

# M1 插件容器内核 + 示例插件 — Spec

Status: ready-for-agent

## Problem Statement

我需要一个完全属于自己的 Java 版插件化 agent harness 框架（duo-harness）：所有组件（工具、MCP、页面模块）都是可插拔插件，核心框架只提供容器与扩展点。参考对象 DSH（deepseek-harness）证明了这套"一切皆插件"的架构可行，但它是 TypeScript 实现——我要在 Java 里从零重建这套能力。当前仓库没有任何代码，第一个里程碑必须把插件化的地基立稳：容器内核、插件生命周期、服务注入、事件系统，以及一组示例插件证明插拔机制真的可用。

## Solution

交付一个自研轻量插件容器内核（duo-harness-core）：插件是声明 inject 依赖并提供 apply 入口的扩展单元；加载与否由 YAML 配置树的一行决定；生命周期由依赖驱动自动启停（服务全就绪才启动、消失即停止、换实现自动重启）；插件间经字符串服务名寻址（视图接口提供类型窗口）、经五种分派模式的事件总线协作；一切注册都是可逆 effect，禁用插件即逆序回滚。工具域骨架（duo-harness-tools）提供工具注册与三段执行管线。示例模块（duo-harness-example）以 Demo 形式跑通"配置加载 → 插件激活 → 服务注入 → 工具注册 → 管线执行 → 禁用传导"全链路。agent 循环、LLM、MCP、Web 面均为后续里程碑，以插件形式叠加在本内核之上。

## User Stories

**框架使用者（用 duo-harness 组装自己的 harness）**

1. As a 框架使用者, I want 只编辑 plugins.yml 就能改变运行形态（增插件/禁用插件/改配置），so that 不改一行代码就能裁剪与重组能力。
2. As a 框架使用者, I want 禁用一个服务提供者后，依赖它的插件自动干净停止，so that 运行期插拔不产生僵尸监听器或悬空引用。
3. As a 框架使用者, I want 服务恢复后依赖链自动重启，so that 插拔是可逆操作而非一次性装配。
4. As a 框架使用者, I want 换掉服务的实现插件后，依赖方自动用新实现重启，so that 能力替换不需要重启整个进程。
5. As a 框架使用者, I want 启动失败时得到点名式报错（哪个插件、因为什么、缺哪个服务），so that 排错不用翻堆栈猜。
6. As a 框架使用者, I want boot 失败时整体回滚不留半启动状态，so that 失败的进程要么全好要么干净退出。
7. As a 框架使用者, I want 同一插件类以不同配置加载多份，so that 一个插件定义能服务多个场景（如连多个 MCP server 的原型）。
8. As a 框架使用者, I want 一条命令跑起示例 Demo 演示全部机制，so that 不读文档就能直观理解框架能力。

**插件作者（给 duo-harness 写插件）**

9. As a 插件作者, I want 实现 apply(ctx, config) 入口即成为一个插件，so that 上手成本最小。
10. As a 插件作者, I want config 是强类型 record 且绑定错误在加载时报错，so that 配置错误不拖到运行深处才爆。
11. As a 插件作者, I want 用 inject 声明依赖服务而非关心启动顺序，so that 不写初始化顺序代码也不出错。
12. As a 插件作者, I want 通过 ctx.as(XxxView.class) 以视图接口拿服务，so that IDE 补全、可跳转、类型安全。
13. As a 插件作者, I want 继承服务基类（或调用 provide）就发布一个具名服务，so that 发布能力是一行代码的事。
14. As a 插件作者, I want 在 apply 里注册的一切（监听器/子插件/服务/工具）在插件停止时自动回收，so that 不手写清理也不会泄漏。
15. As a 插件作者, I want 回收按注册逆序执行，so that 依赖关系决定拆卸顺序，资源不先释放仍被引用的东西。
16. As a 插件作者, I want 注册的工具执行自动经过三段管线，so that 准入、包装、结果治理都有标准挂点而不用改工具本体。
17. As a 插件作者, I want waterfall 监听器能否决工具执行（不调 next），so that 审批/风控类插件无需侵入工具代码。
18. As a 插件作者, I want waterfall 监听器能改写工具结果（post-execute），so that 结果脱敏/裁剪/补写是普通插件的事。
19. As a 插件作者, I want 我的插件抛异常只导致自己进入失败态，so that 一个坏插件不拖垮整个 harness。
20. As a 插件作者, I want 事件监听支持广播、并发、顺序、投票、瀑布五种模式，so that 观察与治理类需求各有顺手表达。
21. As a 插件作者, I want 全部 API 是同步阻塞式签名，so that 插件代码按顺序写、栈完整可调试（并发由内核虚拟线程消化）。

**维护者（维护 duo-harness 本身）**

22. As a 维护者, I want 每个插件实例的状态（等待中/加载中/激活/失败/停止中/已销毁）可查询，so that 诊断与后续 Web 状态页有唯一事实源。
23. As a 维护者, I want 服务注册与解析走 (服务名, 作用域) 二阶键，so that 未来 isolate 多实例能力不用重构地基。
24. As a 维护者, I want 配置树的行结构从第一天就带稳定 id 且以 disabled 语义表达关闭，so that 未来 patch 层序合成能无损叠加。
25. As a 维护者, I want 内核对外只暴露 api 包（实现藏于 internal），so that API 边界靠可见性而非自觉维护。

## Implementation Decisions

以下决策已在 grilling 中与用户逐条确认，详细理由见 `docs/adr/0001` ~ `0004`，术语定义见 `CONTEXT.md`。

**容器内核（ADR-0001）**

- 服务寻址 = 字符串服务名 + 动态代理：Context 实例是动态代理，插件用 `ctx.as(视图接口)` 取得服务，代理按方法名解析到字符串服务名。不用 Class<T> 做服务身份，不用注解字段注入，不引入 DI 框架。
- 视图接口是 DSH declare module 的 Java 对应物：第三方插件自定义视图接口（方法名即服务名），不修改核心。
- 插件加载 = 同 classpath + 配置声明：配置树（单文件 yml）的一行决定加载与否（id/name/config/disabled）；运行期"拔掉"= effect 逆序回滚的逻辑卸载；不做类卸载；独立 jar 热加载保留为未来扩展点。
- 生命周期 = 依赖驱动启停：插件声明 inject，epoch（依赖实现集合的指纹）变化触发实例自动重载/停止。不用一次性拓扑排序，不用显式 before/after。
- 事件 = 五种分派模式（emit / parallel / serial / bail / waterfall）一次定稳；waterfall 监听器收 (args, next)，不调 next 即否决含默认行为在内的链。
- isolate = 仅预留 (服务名, 作用域) 二阶 key 结构，只存在全局默认作用域；Realm 管理与 isolate 配置语法后补。
- 服务名全局扁平命名空间；harness 保留裸名（tools、llm、sessions…），第三方加前缀。

**API 与依赖（ADR-0002 / 0003）**

- 并发模型：内核 API 全同步阻塞签名，内核用 JDK 21 虚拟线程承载插件启停与 IO。
- 配置绑定：Jackson（jackson-databind + jackson-dataformat-yaml）；插件声明 config record，内核绑定，失败即加载失败。
- 依赖清单（全部经用户同意）：jackson-databind、jackson-dataformat-yaml（传递 snakeyaml）。除此之外零第三方运行时依赖。

**模块与工程**

- 三模块：duo-harness-core（容器内核：api 包公开、internal 包藏实现）、duo-harness-tools（工具域骨架，依赖 core）、duo-harness-example（示例插件与 boot 入口 Demo）。遵循 duo-project-structure 规范（package-by-feature、package-info.java、测试包镜像）。
- JDK 21 锁根 pom；版本统一由根 pom dependencyManagement 管理。
- 工具域 M1 范围 = 骨架 + 三段管线：ToolDefinition 基础字段（name / description / parameters / execute）+ register 返回 disposer + pre-execute / execute / post-execute 三段 waterfall。output schema 契约、审批、guard、并发分类、scope 遮蔽后补（M2 强化）。

**决策浓缩形状**（来自 grilling，非代码承诺）：

配置行结构（yml 一行 = 一个插件）：

```
id:      稳定标识，必填（层序合成的锚点）
name:    插件类坐标
config:  任意 yml，绑定到插件 config record
disabled: 布尔；关闭 = 卸载该实例但保留行（不用删除表达）
```

插件实例状态机（六态，epoch 驱动迁移）：

```
PENDING --依赖全就绪--> LOADING --apply 成功--> ACTIVE
LOADING --apply/config 失败--> FAILED
ACTIVE/PENDING --依赖消失/dispose--> UNLOADING --> DISPOSED
（同 DSH Fiber 状态机，术语见 CONTEXT.md「插件实例」）
```

## Testing Decisions

- 只测外部行为：断言落在公共 API 的可观测结果（boot 产物、状态查询、事件效果、工具执行结果），不断言私有方法或内部字段。
- 两个测试接缝（已与用户确认）：
  - **接缝 A（验收主口）= boot 全链路**：输入 plugins.yml（含测试插件类与内存/临时 yml）→ 断言插件激活、服务经视图接口可寻址、工具注册后经三段管线执行、禁用行导致依赖传导停止、boot 失败整体回滚。示例插件的全部验收故事在此覆盖。
  - **接缝 B = 内核编程 API**：ctx.plugin / provide / on / as 直测状态机边界——epoch 变化重载、dispose 逆序、apply 抛错隔离、注入服务缺失进入等待、事件五种模式的语义差异。
- 工具域不单独设缝：经接缝 A（boot 后取 ToolsView 执行）与接缝 B（编程注册后执行）覆盖。
- 本 spec 是仓库首个 feature，无先例可循；本批测试即建立测试惯例（XxxTest 命名、测试包镜像主包）。

## Out of Scope

- isolate / Realm 作用域管理（仅预留 key 结构）
- patch 层序合成引擎（bundle / profile / overlay 叠加）
- HMR（配置或类热重载）
- 工具域强化件：output schema 契约校验、审批（pre-execute ask 流程）、guard 单调否决、并发安全分类、scope 分层遮蔽、restrict 可见性
- MCP 接入（M2）、页面插件 / Web 双面系统（M3）、agent 循环与 LLM 适配（M4）
- 独立 jar 动态加载与类卸载
- `!!js` 类配置表达式求值

## Further Notes

- 架构参考依据：`docs/research/DSH/插件化架构/`（架构设计 / 代码说明 / 细节备忘三件套，DSH 源锚点 b150a551）。本 spec 的每个机制决策都能在该文档找到 DSH 原型与出处。
- 本 spec 只产出 spec；实现工单由 `/to-tickets` 拆分到 `.scratch/m1-plugin-kernel/issues/`。
- 里程碑路线图（ADR-0004）：M1 本 spec → M2 工具域强化 + MCP → M3 页面插件 / Web 双面 → M4 agent 循环 + LLM。

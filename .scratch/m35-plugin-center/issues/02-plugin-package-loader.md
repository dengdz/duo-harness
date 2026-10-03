# 02: 插件包装载器——fat-jar 行装载与类隔离

## What to build

第三方交来的自包含插件包（fat-jar），配合 yml 行（`jar:` 来源字段 + `name` 入口 FQCN，无 manifest 约定）即可被 Boot 装载激活——与 classpath 行完全同语义（config 绑定、依赖声明、失败点名整树回滚，既有 Boot 审计契约不变）。每包独立类加载器、插件间类互不可见；卸载 = 容器拔除 + 加载器丢弃，泄漏可检测并提示"需重启生效"。

验收标准：最小 fat-jar fixture 经 yml 行装载后服务在册、yml 回读断言通过；坏包各路径点名清晰。

## Blocked by

无硬阻塞（与 01 并行开工）；拔除/回落全链路联调依赖 01，本单先交静态装载断言。

## Status

in-progress（实现与回归锁已完工待提交；提交后随 09 单端到端验收转 done）

## Checklist

- [x] yml 行 `jar:` 字段解析（正向兼容：既有行零感知；`jar:` 与 `name` 组合校验，空白 jar 字段解析期点名）
- [x] per-jar 类加载器装载（parent = 应用 classpath，**自优先**委派）；最小 fat-jar 测试 fixture（测试内现打：夹具类字节 + 每包不同 marker 资源）
- [x] 类隔离验证：同 FQCN 两包各自读本包 marker 资源（parent-first 下会统一落宿主副本，断言即分辨器）
- [x] 卸载：容器拔除 + 加载器 close + 丢弃；关闭失败 warn"需重启生效"兜底（closer 随行挂 RowRegistry，拔除链路自动执行）
- [x] 坏包点名：缺文件（open 时 fail-fast）/损坏包/入口类缺失/构造失败/LinkageError——行 id + 原因 + 包路径，整树回滚
- [x] S1 测试：jar 行装载 → 服务在册（值来自包内资源）→ rows 断言；拔除释放 + 同 id 重装冒烟（依赖方回落联调在 05 单收拢）
- [x] CHANGELOG 记账（用户可见：jar 行装载能力，同 diff）

## Comments

- **实现形态（2026-10-03）**：`PluginJarClassLoader`（internal.boot，public 供 05 复用）——self-first 委派（findClass 优先、CNFE 才放行 parent），这是"同 FQCN 互不可见"的实现点；`BootLoader` 单行装载抽取 `loadPluginInstance`（classpath 反射 / 插件包两分支，LinkageError 折进点名防 Error 逃过审计）；closer 链路 = RowEntry 携带随行关闭器 → `PluginRows.dispose` 拔除后执行。
- **交货约定落点**：插件包不得打入宿主 API 类（自优先会取到包内副本，同类不同器）——写进加载器 javadoc，08 单进交货文档。
- **验证**：JarRowTest 6 用例（jar 行装载/双包隔离/缺包/损坏包/空白字段/拔除重装）+ 全仓 13 模块 1118 例全绿。

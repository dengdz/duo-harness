# 04: 端点命名空间贡献口——内核口二

## What to build

插件可按申请制获得 `/plugins/<贡献者前缀>/**` 形态的端点注册位，HTTP 可达。三条铁律（spec 在案）：①贡献端点一律纳入既有鉴权栅栏——无 token 401、fail-closed，`web.auth: none` 行为与既有端点一致；②路由层异常兜底一律 500 + 日志（BUG-20261002-01 教训：无兜底 = 连接裸关静默盲）；③前缀冲突点名拒绝。插件中心是第一个消费者（其 API 全走贡献口，05/06 单落地）。

验收标准：测试插件申请前缀后端点 HTTP 可达、鉴权负路径全过、提供方拔除后端点摘除。

## Status

in-progress（实现与回归锁已完工待提交；提交后随 09 单端到端验收转 done）

## Checklist

- [x] 贡献口注册 API：`WebRouteRegistry`（claim 申请前缀 / mount 挂端点）+ `WebContributedRoutes` 实现；前缀占用点名、非法段（空白/含 /、. 段/非 ASCII）点名
- [x] 鉴权栅栏覆盖：贡献端点无 token 403 / 带 token 200 / `auth: none` 直达（S3 HTTP 缝；实态为 403——Web 栅栏既有口径即 fail-closed 403，工单原文 401 系笔误，语义一致）
- [x] 路由层兜底：贡献端点内抛错 → 500 + 文本响应 + 日志（WebEndpoints.routeHandler 统一包裹，内部路由与贡献口共用同一份兜底代码——BUG-20261002-01 铁律单点化）
- [x] 提供方拔除 → 贡献端点同步摘除：claim 移除器挂消费方作用域（ctx.effect 即得）；摘除后同前缀可复用
- [x] CHANGELOG 记账（用户可见：插件可贡献端点，同 diff）

## Comments

- **实现形态（2026-10-03）**：`WebContributedRoutes`（前缀占用表 + 每前缀路径清单，synchronized 串行化）挂 WebFace；挂接统一走 `WebEndpoints.routeHandler` 新静态包裹（内部 `route()` 同步收敛到它——兜底代码单点化）；WebPlugin 在 face 启动成功后发布 `webRoutes` 服务；`HttpServer.removeContext` 摘除。
- **摘除后的可观测形态（实测澄清）**：web 单页有 `/` 兜底上下文（JDK 最长前缀匹配），贡献路由摘除后请求落回单页（200 HTML）而非 404——摘除的本质是"贡献处理器退出调用链"；404 形态仅存在于无兜底上下文的部署。测试按"pong 不再出现"断言，工单原文的 404 预期就此勘误。
- **验证**：WebRouteContributionTest 7 用例 + WebPluginAssemblyTest 增 1 例（webRoutes 服务在册）+ web 模块全量 111 例全绿（-am 真实链，mvn exit 0）。

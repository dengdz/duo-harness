# 13: core 作用域销毁异常类型化

## What to build
core 提供作用域销毁的类型化异常（插件树停止期间的注册失败可类型判别）；MCP 工具同步从"匹配异常消息文案子串"改为类型判定——core 侧异常文案演进（本地化、改标点）不再静默改变 MCP 侧的日志级别与降级行为。

证据锚点：审计报告 P2-C 族第 12 条（`contains("作用域已销毁")` 文案匹配）。

验收标准（用户可感）：插件树停止期间 MCP 工具注册失败不再误升 warn 噪音；core 文案调整不引发 MCP 行为变化。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] core 类型化异常（api 公开 ScopeDestroyedException extends PluginException；ContextImpl 两处抛出点改型，文案保持）
- [x] MCP 工具同步改类型判定（catch 分流：ScopeDestroyedException → debug 忽略 / 其余 PluginException → warn），文案匹配删除
- [x] 停止期注册失败分类用例（LifecycleTest.disposedScopeRejectionsAreTyped：dispose 后加载/注册均抛类型化异常且 is-a PluginException；类型判定天然不依赖文案——core 文案改写不破坏判定的负例由类型机制承载）
- [x] CHANGELOG 记账（对插件开发者可见：新公开异常类型）

## Comments

- 2026-09-28 实现：全消费方回归绿（core/mcp/agent/web/cli/example）；既有测试无文案匹配引用（grep 佐证）。MCP 侧既有停止期场景由连接器测试覆盖（全绿），分类行为由类型分流结构保证。


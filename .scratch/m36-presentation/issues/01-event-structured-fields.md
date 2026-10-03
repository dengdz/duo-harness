# 01: 事件载荷结构化字段——魔法串收敛后端半边

## What to build

前端不再猜文案的地基：tool/result 事件补 status 枚举（ok / denied / failed——拒绝语义由审批管线注入、计划批准语义由工具结果注入），approval/decided 事件补 decision + source 枚举字段；SSE 事件与 headless NDJSON 帧同源对齐。旧文案字段原样保留（1.x 向后兼容承诺内，纯新增），CLI 侧 deny 前缀耦合断言不动。前端切换归 05（expand–contract：本单先扩，05 收缩退役嗅探）。

验收标准：S3 事件缝断言新字段在载荷中且旧文案不变；契约文档同 diff 更新。

## Blocked by

None (can start immediately)

## Status

in-progress（实现与回归锁已完工待提交；提交后随 08 单端到端验收转 done）

## Checklist

- [x] status / decision / source 字段定义与发射点接入（SSE 与 NDJSON 同源）
- [x] 会话事件类型表 + NDJSON 契约文档同 diff 更新（CLI参考.md 帧说明）
- [x] S3 事件缝测试：新字段断言 + 旧文案不变断言（兼容锁）
- [x] CHANGELOG 记账（用户可见：事件载荷新增结构化字段，同 diff）

## Comments

- **实现形态（2026-10-03）**：`SessionEvent` 加三可空组件（status/decision/source）——JSONL 手工序列化仅非 null 落盘、parse 缺字段归 null（error 布尔 M25 先例同款）；`ToolResult` 加 outcome 组件（ok/denied/failed 常量 + `denied` 工厂 + 二参兼容构造按 isError 归一）；ToolsServiceImpl 三条拒绝路（准入/审批/guard）与 ToolCallingAgent plan 白名单拒绝、SubagentToolView 子代理策略拒绝改 `denied`；执行管终局加 ToolResult 自声明采纳（`instanceof` 解包——工具可返回完整语义 ToolResult）。
- **计划批准语义（工具自声明）**：ExitPlanModeTool 打回/无人应答两分支返回 `(text, isError=false, denied)`——isError 保持 false，模型侧修订循环语义不变，outcome 仅供呈现层；ask_user 拒绝/不可达同形（isError=true + denied，文本经直返省去「执行失败」前缀——前端该路径按 isError 判读不嗅文案，测试无旧文案锚点）。
- **NDJSON 同源对齐**：tool_result 帧新增 `outcome` 字段（取暂存事件 status），既有 `status`（completed/error）不动——纯新增不改既有词汇；AgentListener 接口零改动（callId 同款暂存配对）。
- **验证**：五目标套件绿（Session 63 / Tools 11 / Auditing 5 / ExitPlan 5 / Headless 8，新 surefire 实证）；全量回归 14 模块 1144 例 0 失败 0 错误（mvn exit 0）；CliPluginTest deny 前缀耦合断言不动即兼容锁。

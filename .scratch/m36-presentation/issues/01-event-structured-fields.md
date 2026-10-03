# 01: 事件载荷结构化字段——魔法串收敛后端半边

## What to build

前端不再猜文案的地基：tool/result 事件补 status 枚举（ok / denied / failed——拒绝语义由审批管线注入、计划批准语义由工具结果注入），approval/decided 事件补 decision + source 枚举字段；SSE 事件与 headless NDJSON 帧同源对齐。旧文案字段原样保留（1.x 向后兼容承诺内，纯新增），CLI 侧 deny 前缀耦合断言不动。前端切换归 05（expand–contract：本单先扩，05 收缩退役嗅探）。

验收标准：S3 事件缝断言新字段在载荷中且旧文案不变；契约文档同 diff 更新。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] status / decision / source 字段定义与发射点接入（SSE 与 NDJSON 同源）
- [ ] 会话事件类型表 + NDJSON 契约文档同 diff 更新
- [ ] S3 事件缝测试：新字段断言 + 旧文案不变断言（兼容锁）
- [ ] CHANGELOG 记账（用户可见：事件载荷新增结构化字段，同 diff）

## Comments

- spec 锚点：ADR-0038 决策五；实施面事实——三处嗅探横跨 agent/tools/web 三模块（探索实测在案），本单只动发射侧，消费侧 05 收。

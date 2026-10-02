# 01: BUG-01 /api/status 空响应——状态面五区块复明

## What to build

波及面最大的一档先行：修 `/api/status` 对任何鉴权形态返回空响应（连接被静默关闭）的缺陷。执行三步：①**假设验证**——BUG-20261002-01 档案主假设为 `statusJson` 在 `resolveTab` 返回 null 时返回 null、调用点未兜底（WebEndpoints.java:522-526），先实钉「为何已连接 tab 会拿不到 tab 上下文」与调用点 null 处理；与假设不符先改档案再动手。②**修复**——tab 缺席返回结构化空态（而非 null/断连），请求级异常一律落日志（对照 file-complete handler 的 error-log+500 兜底形态），状态面五区块（上下文/后台任务/连接器/插件六态/工具）恢复真实数据。③**回归锁**——档案「回归测试」候选：六鉴权形态（无 token/错 token/伪 Origin-POST/伪 Host/SSE 无 token/篡改）下 /api/status 行为正确（403 或 200 结构化，绝无空响应）。spec 见 [spec.md](../spec.md)。

## Blocked by

None (can start immediately)。

## Status

ready-for-agent

## Checklist

- [ ] 根因实钉回写 BUG-20261002-01 档案（验证结果与假设不符时先改档案）
- [ ] 修复落地：tab 缺席 → 结构化空态；异常 → 日志 + 5xx；状态面五区块真实数据回归
- [ ] 回归锁入库：/api/status 集成测试（真起 HttpServer 形态，六鉴权形态 + 正常路径断言 JSON 结构）
- [ ] 档案 Status 流转 reported → done + 防复发节填写
- [ ] CHANGELOG 记账（用户可见变更：状态面恢复）
- [ ] 提交前核对：调试残留 grep 零命中 + 测试绿

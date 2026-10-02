# 01: BUG-01 /api/status 空响应——状态面五区块复明

## What to build

波及面最大的一档先行：修 `/api/status` 对任何鉴权形态返回空响应（连接被静默关闭）的缺陷。执行三步：①**假设验证**——BUG-20261002-01 档案主假设为 `statusJson` 在 `resolveTab` 返回 null 时返回 null、调用点未兜底（WebEndpoints.java:522-526），先实钉「为何已连接 tab 会拿不到 tab 上下文」与调用点 null 处理；与假设不符先改档案再动手。②**修复**——tab 缺席返回结构化空态（而非 null/断连），请求级异常一律落日志（对照 file-complete handler 的 error-log+500 兜底形态），状态面五区块（上下文/后台任务/连接器/插件六态/工具）恢复真实数据。③**回归锁**——档案「回归测试」候选：六鉴权形态（无 token/错 token/伪 Origin-POST/伪 Host/SSE 无 token/篡改）下 /api/status 行为正确（403 或 200 结构化，绝无空响应）。spec 见 [spec.md](../spec.md)。

## Blocked by

None (can start immediately)。

## Status

done（2026-10-02 修复 + 回归锁两用例绿 + 全库测试绿；发版归工单 06）

## Checklist

- [x] 根因实钉回写 BUG-20261002-01 档案（与原假设不同：非 tab 缺席，系 optionalInject 未声明 connectorStatus + route 无兜底三层耦合；假设 1/2 排除过程在档）
- [x] 修复落地：optionalInject 补声明（根因）+ route() 异常兜底（log.error + 500，防复发）
- [x] 回归锁入库：WebPluginAssemblyTest 两用例（声明断言 + 端到端连接器快照；修前精确复现空响应 `header parser received no bytes`，修后绿）
- [x] 档案 Status 流转 reported → done + 防复发节填写（含「hasService 真 ≠ 可读」家族第三次出现的提交前核对建议）
- [x] CHANGELOG 记账（未发布段 Fixed 条目）
- [x] 提交前核对：调试残留 grep 零命中（exit 1）+ web 模块 102 测试绿 + 全库测试绿（exit 0）

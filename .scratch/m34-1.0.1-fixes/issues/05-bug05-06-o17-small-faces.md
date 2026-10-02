# 05: BUG-05 + BUG-06 + O17 小面收尾——todo 面板 / slash 直调 / auth 横幅

## What to build

三小面一并收尾。**BUG-05**（todo 侧栏面板首帧冻结）：档案假设面板未消费 todo/update 后续事件——修复口径：面板消费 update 事件重渲染进度；回归锁 = 三步清单推进断言面板计数同步。**BUG-06**（占位符承诺「/技能名 直调」未兑现）：工单内二选一裁定并回写档案——①WebPlugin 补技能命令注册（对齐 CliPlugin 先例，推荐：承诺兑现且两呈现位对称）；②改占位符文案去掉承诺；回归锁 = 已发现技能 /技能名 直调断言（按裁定形态）。**O17**（auth:none 页面内横幅缺位）：auth 关闭时页面顶部呈现「鉴权已关闭」警示条（启动日志横幅保留，ADR-0026 口径升级为页面可见）；回归锁 = auth:none 装配断言横幅元素在场。spec 见 [spec.md](../spec.md)。

## Blocked by

None——可并行。

## Status

ready-for-agent

## Checklist

- [ ] BUG-05 根因实钉回写档案（订阅面 or 渲染面）+ 修复 + 面板同步回归锁
- [ ] BUG-06 路线裁定回写档案 + 落地 + slash 直调（或文案）回归锁
- [ ] O17 横幅落地 + auth:none 断言
- [ ] 三档案/记录 Status 流转 + 防复发填写
- [ ] CHANGELOG 记账
- [ ] 提交前核对：调试残留 grep 零命中 + 测试绿

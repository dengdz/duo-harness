# 05: BUG-05 + BUG-06 + O17 小面收尾——todo 面板 / slash 直调 / auth 横幅

## What to build

三小面一并收尾。**BUG-05**（todo 侧栏面板首帧冻结）：档案假设面板未消费 todo/update 后续事件——修复口径：面板消费 update 事件重渲染进度；回归锁 = 三步清单推进断言面板计数同步。**BUG-06**（占位符承诺「/技能名 直调」未兑现）：工单内二选一裁定并回写档案——①WebPlugin 补技能命令注册（对齐 CliPlugin 先例，推荐：承诺兑现且两呈现位对称）；②改占位符文案去掉承诺；回归锁 = 已发现技能 /技能名 直调断言（按裁定形态）。**O17**（auth:none 页面内横幅缺位）：auth 关闭时页面顶部呈现「鉴权已关闭」警示条（启动日志横幅保留，ADR-0026 口径升级为页面可见）；回归锁 = auth:none 装配断言横幅元素在场。spec 见 [spec.md](../spec.md)。

## Blocked by

None——可并行。

## Status

done（2026-10-02：BUG-06 修复 + 浏览器验证 / O17 修复 + 浏览器验证 / BUG-05 复现尝试不可复现关闭（偶发先例）；发版归工单 06）

## Checklist

- [x] BUG-05 处置：两步 todo 清单三采样——面板正确同步「2/2 已完成」，不可复现关闭（偶发先例，再现重开）；无代码改动
- [x] BUG-06 根因实钉回写档案（修正两候选：既非缺注册也非不该承诺，系声明闸门拒读被 skillsOrNull 吞成 null——「hasService 真 ≠ 可读」家族第四犯）+ 修复（optionalInject 补 skills 声明）+ 声明断言锁入库 + 浏览器验证 /greet 直调命中
- [x] O17 落地：statusJson 加 auth 字段（数据面）+ 前端 refreshStatus 渲染页面顶部「鉴权已关闭」横幅 + WebFaceTest auth 字段断言 + auth:none 二实例浏览器验证横幅可见
- [x] CHANGELOG 记账（slash 直调 + auth 横幅合并条目）
- [x] 提交前核对：node --check 语法绿 + web 模块 103 测试绿 + 三锁浏览器验证证据入库

## Comments

- 2026-10-02 执行记录：三小面两种处置形态——BUG-06/O17 修复落地（各带锁），BUG-05 偶发关闭（与 BUG-03 同日第二例不可复现，批 3 观察的选择器混判风险已记入档案）。
- 「hasService 真 ≠ 可读」家族第四犯：WebPluginAssemblyTest 现有声明断言锁组三连（workspace/connectorStatus/skills），新可选消费方照此加锁（经验条目已在本单前更新至第四犯变体）。
- BUG-06 语义注：直调后用户消息显示注入的技能内容（非 /greet 原文）为 M12-03 既定语义（斜杠文本不透传）。
- 执行环境小坑自省：清场 rm -rf 连带删掉了 authnone yml（重建后恢复）——/tmp 夹具与已提交资产的边界意识（重建程序在工单 01/06 Comments）。

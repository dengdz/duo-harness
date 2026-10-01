# 10: 参考新增——CLI 参考

## What to build

一篇 CLI 参考：启动参数全表（常驻/headless `--json` 及全部旗标）、REPL 快捷键表（中断/暂停/steer 等交互键位）、斜杠命令清单一张表（与指南篇互链：参考查参数，指南学场景）。

## Blocked by

01（大纲确认）。

## Status

done（2026-10-01 用户验收通过）

## Checklist

- [x] 启动参数全表（锚 HeadlessArgs#53-90：positional yml 三态/--json/--session-id/任务文本必需/usage 退出码 2）+ 常驻行序契约两道防线
- [x] REPL 键位表（Ctrl+C 三态/Ctrl+D//stop 兜底——锚 CliPlugin#1107-1145；无方向键历史如实说明）+ 运行中输入分流表（插队/合并/应答闸门）
- [x] headless 事件流速查表（五帧形态/退出码契约/stderr 诊断分离/禁交互/截断）
- [x] 斜杠命令一句话指向工单 06 指南篇（不重复）；内链自查 + vitepress build 通过；口吻自查零命中
- [x] 用户验收通过（2026-10-01）；CHANGELOG 0.27.0 段同 diff 记账

## Comments

- 2026-10-01 审查销账：What to build 中「斜杠命令清单一张表」与 Checklist「一句话指向」的自相矛盾，按用户确认的 outline 终态裁定——清单由《斜杠命令》指南四表承接，CLI 参考保留一句话指针（spec US17 字面缺口就此销账）。

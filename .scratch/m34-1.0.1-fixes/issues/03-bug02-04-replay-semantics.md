# 03: BUG-02 + BUG-04 回放语义并修——翻页节点保留 + 提问卡回放态

## What to build

同属回放语义的两档并修（共享 replaying/render 路径，一并改一并锁）。**BUG-02**（翻页进度被新事件重置）：新事件 append 前保留 prependEvents 已加载的更早节点——档案假设 setTailWindow 整窗替换只应发生在尾窗基线态；注意批 4 实测收窄：user/message 轮不触发重置、/export 命令卡路径触发——修复批以命令卡路径为主嫌疑实钉。**BUG-04**（提问卡回放态不完整）：replaying 门卫放行 question/requested 与作答状态行，仅跳过会重复的 tool/call（BUG-20260929-01「回放保留出卡」修复的补全）。回归锁：①滚顶态新事件后首条仍在 DOM；②ask_user 作答后刷新断言 [提问]+✓已回答 仍在。浏览器面验证走 M33 口径（原生点击、行为类判据）。spec 见 [spec.md](../spec.md)。

## Blocked by

None——与 01/02 无代码交叠，可并行。

## Status

ready-for-agent

## Checklist

- [ ] 两处根因实钉回写 BUG-20261002-02/04 档案（命令卡触发帧 / 门卫分支）
- [ ] 修复落地：翻页节点保留 + 提问卡回放态完整
- [ ] 回归锁入库：滚顶态新事件断言 + 提问卡刷新断言（前端行为，M33 口径验证）
- [ ] 两档案 Status 流转 + 防复发节填写
- [ ] CHANGELOG 记账
- [ ] 提交前核对：调试残留 grep 零命中 + 测试绿

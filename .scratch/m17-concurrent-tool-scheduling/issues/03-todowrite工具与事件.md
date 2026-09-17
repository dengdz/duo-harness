# 03: todo_write 分解抓手——工具与事件

## What to build

模型接手多步任务时先拆成结构化清单（术语：todo 清单，ADR-0018 决策 6，DSH 同款）：全量整表替换写入、事件落日志可恢复、模型只收计数回显（上下文不被重复清单撑大）、工具 description 携带分解纪律（多步先拆 / 平凡不拆 / 全量发送 / 完成即刻标记不攒批）。引擎层完整闭环——事件日志与投影可验证、可测试；呈现面归工单 04。

## Status

ready-for-agent

## Blocked by

无（与 01/02 可并行——todo_write 是独占工具，不依赖并发机制）

## Checklist

- [ ] todo_write 工具：单一必填 `todos` 数组，每项 content（非空祈使句）+ status（pending / in_progress / completed），无 id 无 priority；校验内容非空且不重复；多 in_progress 不校验不配置
- [ ] `todo/write` 会话事件落日志：log-only、重放 latest-wins、重开会话清单恢复
- [ ] 计数回显：工具结果为一句"已更新：X 待办 / Y 进行中 / Z 已完成"，完整清单不作为第二条模型消息
- [ ] description 纪律引导（多步先拆、平凡单步不拆、全量发送、完成即刻标记）
- [ ] 投影生命周期：新 user/message 清空投影、终版 assistant/message 后保留（用户读完答案还能看到完成的清单）
- [ ] todo_write 声明为独占工具
- [ ] 测试（Session seam + agent 循环 seam）：事件落盘、latest-wins 投影、清空 / 保留边界、回显文本形态

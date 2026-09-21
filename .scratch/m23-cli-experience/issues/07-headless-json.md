# 07: headless --json

## What to build

自动化脚本以 `--json` 跑任务：stdout 输出逐行 JSON 事件流（NDJSON 七类：session/status/thinking/text/tool_call/tool_result/error/final），text/thinking 仅在 assistant/message 提交点发射（commit-point 投影），final 帧承载无损答案豁免截断；进程退出码即成败契约（completed→0 否则 1；SIGTERM→0、SIGINT→130）。`--session-id` 恢复既有会话续跑；headless 流内禁交互——审批/提问请求自动 deny 并发显式 error 帧，流程永不静默挂死；诊断信息只走 stderr。入口：DuoMain 参数扩展（`--json` 标志 + positional 任务文本 + `--session-id`），yml 路径参数兼容现状；无任务文本且非 TTY 时 usage error。

决策依据：ADR-0025（grill Q9 七类全量 + 可恢复）；探测文档 DSH 终端呈现.md 的 NDJSON 投影词汇/bounding 降级链/退出码契约（本项主参考）。单值 8KB、单行 32KB 截断降级链照落。

## Blocked by

无（可与主线并行；呈现位换 NDJSON 投影器，复用既有装配链）

## Status
ready-for-agent

## Checklist
- [ ] 入口参数：--json + positional prompt + --session-id + yml 兼容 + usage error
- [ ] NDJSON 七类词汇与 commit-point 投影、final 无损豁免、8K/32K 截断降级链
- [ ] 退出码契约：completed→0 否则 1、SIGTERM→0、SIGINT→130（测试覆盖）
- [ ] 流内禁交互：审批/提问自动 deny + 显式 error 帧；诊断走 stderr
- [ ] --session-id 恢复：会话续跑且事件流连续
- [ ] 测试（先例 BootTest / CliPluginAssemblyTest 装配级 seam）：假 LLM + stdout 捕获断言帧序列与退出码
- [ ] 工单级验收件：脚本管道消费 NDJSON（grep/解析 final 帧）演示，用户手动确认
- [ ] CHANGELOG 未发布段记账

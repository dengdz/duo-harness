# 03 — CLI REPL demo 与验收

## What to build

REPL 聊天 demo（duo-harness-llm 或 example 模块的 exec 入口）+ 验收三件套。交互形态：

```
duo-chat（REPL，每轮独立无记忆——M4 接会话）
你> 用一句话介绍你自己
AI> （流式逐段打印回答）
你> /exit
```

- system prompt：yml 固定字符串起步（"你是一个简洁可靠的助手"之类，可配）。
- 流式打印：chunk 逐个输出到终端（不整段等待）。
- `/exit` 与 Ctrl+C 干净退出；输入错误（如未配 apiKey）给出生动的重配指引。

## Blocked by

01, 02

## Status

ready-for-agent

## Checklist

- [ ] REPL 循环：提示符 / 流式打印 / /exit 与 Ctrl+C
- [ ] 未配置 apiKey 时的重配指引输出
- [ ] 验收三件套：可运行演示 + 两路日志（demo 叙述 / 测试套件）+ 预期输出对照表（含预期日志原文段）
- [ ] 用户手动验收：真实 key 下逐行对照通过（done 的定义）

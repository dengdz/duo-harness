# 05: llm 多部件与视觉闸门

## What to build

llm 模块消息 content 从纯文本扩展为多部件（text + image base64 data URI，OpenAI 兼容形态）；请求组装时附件引用解析为请求变体并发给视觉模型。`llm.vision`（缺省 false）为总闸门——false 时多部件不可达。至此用户在 Web 面发图，视觉模型真正"看见"。

## Blocked by

04

## Status

ready-for-agent

## Checklist

- [ ] 消息 content 多部件建模（text/image 部件；附件引用 → 变体解析 → base64 data URI）
- [ ] `llm.vision` 配置（缺省 false）作为总闸门：Web 收图与 read_image 之外的第三道保险
- [ ] 请求组装：引用块 → 请求变体（02 管线）→ data URI 部件；非文本部件的治理计量盲区按 limitation 记账不处理
- [ ] 测试：vision=true 时多部件请求序列化正确（mock 端点断言请求体）、vision=false 时多部件不可达、变体解析接线

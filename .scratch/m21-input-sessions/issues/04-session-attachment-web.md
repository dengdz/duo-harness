# 04: 会话附件引用块 + Web 上传/渲染

## What to build

用户在 Web 输入框拖拽/粘贴图片 → 上传端点准入入库 → 消息携带**附件引用块**（日志零字节）→ Web 面把图片渲染出来（授权读取端点）。子代理上下文过滤附件引用块。

## Blocked by

01

## Status

in-progress

- 2026-09-20（实现轮）：会话词汇/上传与授权读取端点/Web 渲染与拖拽粘贴入口/子代理过滤全部落地；WebAttachmentEndpointTest 6 条 + SessionAttachmentTest 4 条绿；全仓 SUCCESS。库级+端点级：随 05 端到端里程碑演示一并手动验收（vision=true 全链）。
## Checklist

- [ ] 会话词汇扩展：user 消息附件引用块（attachmentId/mediaType/bytes/name，向前兼容新块类型；字节不进日志）
- [ ] Web 上传端点：base64 → 准入 → 附件库 → 消息引用块；vision=false 时即拒（模型不支持图片）
- [ ] 授权读取端点：先验证"该会话日志确实引用了此 attachmentId"再回字节；伪造引用 404
- [ ] Web 面图片消息渲染（拖拽/粘贴入口 + 对话流图片显示）
- [ ] 子代理上下文过滤附件引用块（框架过滤，照交互工具先例）+ 过滤测试
- [ ] WebFace HTTP 级测试：上传成功流 / vision=false 拒 / 伪造引用 404

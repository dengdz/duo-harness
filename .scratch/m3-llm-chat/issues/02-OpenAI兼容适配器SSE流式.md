# 02 — OpenAI 兼容适配器（SSE 流式）

## What to build

`LlmAdapter` 的首个实现：OpenAI chat/completions 兼容适配器，流式调用。约束：JDK 内置 HttpClient + 手写 SSE 行解析，零新依赖；M3 不做重试（失败原样呈现）。

要点：

1. **请求构造**：POST `{baseUrl}/chat/completions`，`stream: true`，消息列表 + model 头与 `Authorization: Bearer <apiKey>`。M3 只有 system + user 消息（无历史、无 tools 字段——M4/M5 接线）。
2. **SSE 解析**：响应体逐行读——`data: {JSON}` 剥前缀反序列化为 chunk（`delta.content` 增量文本）；`data: [DONE]` 结束；空行跳过。每个 chunk 经回调交给调用方（REPL 打印 / 将来写会话事件）。
3. **错误呈现**：HTTP 非 200（401/429/5xx）→ 把状态码与响应体里的错误消息组装成明确异常原样抛出（不重试、不吞）。
4. **可测性**：测试用 JDK 内置 `com.sun.net.httpserver` 起 mock SSE 端点（本仓库先例：`MiniFileSystemServer` 同款思路），验证多 chunk 聚合顺序、`[DONE]` 终止、非 200 错误呈现三类路径。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] OpenAiCompatAdapter：请求构造（stream:true + Bearer 头）
- [ ] SSE 行解析：`data:` 前缀 / `[DONE]` / 空行；chunk 回调逐个交付
- [ ] 非 200 错误原样呈现（状态码 + 服务端错误消息）
- [ ] mock SSE 端点测试：多 chunk 顺序聚合 / [DONE] 终止 / 错误呈现

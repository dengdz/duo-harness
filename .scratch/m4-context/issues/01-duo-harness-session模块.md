# 01 — duo-harness-session 模块（会话事件溯源基座）

## What to build

新模块 `duo-harness-session`（依赖仅 core + Jackson）：会话事件溯源的完整数据域闭环——事件、会话、投影、JSONL 读写。纯单测即可完整验证：append 后事件可见且文件逐行落盘、load 读回与写入序列一致、投影规则正确、空会话边界。

要点：

1. **SessionEvent**：单一 record `SessionEvent(type, at, text)`——type 字符串判别（`user/message` / `assistant/chunk` / `assistant/message` 三种常量）、at 为 epoch millis、text 为载荷。类型常量随 record 定义。
2. **Message**：投影产物 record `Message(Role role, String content)`，`Role` enum（USER / ASSISTANT）。
3. **Session**：`id()`（如 `20260912-080000-x7f3`：启动时间 + 短随机后缀）/ `events()` 只读视图 / `append(event)` 唯一写入原语（内存追加 + JSONL 同步追加落盘，每事件一行）/ `deriveMessages()` 投影（`user/message` → USER、`assistant/message` → ASSISTANT、`assistant/chunk` 不投影）/ `jsonl()` 文件路径（会话身份在文件名：`~/.duo/sessions/<id>.jsonl`）。
4. **JSONL 读写**：写=append 内同步追加（每事件一行 JSON，type/at/text）；读=静态工厂按行重放回 Session（按 type 判别还原事件）；`Session.latest(sessionsDir)` 取目录内文件名最大的会话（无则 null）。
5. 会话文件落 `DuoHome.resolveDir("sessions")`（DuoHome 属 core，工单 01/llm 已引入）。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] 根 pom 注册模块；新模块 pom（依赖仅 core + jackson-databind + jackson-dataformat-yaml）
- [ ] SessionEvent / Message / Session 契约类型与实现
- [ ] JSONL 同步追加落盘 + 按行重放读回
- [ ] `Session.latest` 取最新会话
- [ ] 单测：append 落盘逐行可见 / load 重放一致 / 投影规则（message 入列、chunk 不入列）/ 空会话投影为空 / latest 选取 / DUO_HOME 重定向（@TempDir）

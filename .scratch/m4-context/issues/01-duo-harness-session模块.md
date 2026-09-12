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

done（2026-09-12 code-review 双轴通过；DuoHome 连接在工单 02 的 REPL 落地）

## Checklist

- [x] 根 pom 注册模块；新模块 pom（依赖 core + jackson-databind；无需 dataformat-yaml——JSONL 行是手写模板 + Jackson writeValueAsString/readTree，非 YAML 解析）
- [x] SessionEvent / Message / Session 契约类型与实现
- [x] JSONL 同步追加落盘 + 按行重放读回
- [x] `Session.latest` 取最新会话
- [x] 单测 8 个：append 落盘逐行可见 / load 重放一致 / 投影规则 / 空会话投影为空 / 特殊字符载荷往返 / latest 选取（手工文件名确定性验证）/ 空目录 null / 损坏行点名
- [x] DUO_HOME 重定向测试：由 core 的 DuoHomeTest 覆盖（DuoHome 层职责，session 层不重复）

## Comments

### Code review 跟进（双轴，已修复）

- 写路径改用 Jackson `writeValueAsString` 统一读写对称（原手写 escape 转义正确但与读路径的 ObjectMapper 不对称，维护两套序列化有漂移风险）；特殊字符载荷往返测试保证行为不变。
- 会话 id 随机后缀补零（`%04x`）：无补零时同秒内后缀字典序与生成序可能不一致（如 `0x1000` < `abc`），影响 `latest` 判定。
- 线程约定补 JavaDoc：Session 非线程安全，单会话内串行使用（REPL / agent 循环均为串行消费）。
- SessionEvent JavaDoc 的里程碑引用改为 ADR-0007 路径（duo-trim-cot-leakage：引用可解析载体）。
- DuoHome 连接（`resolveDir("sessions")`）由工单 02 的 REPL 落地——Session 的目录参数留给调用方，便于测试注入与 M8 的目录定制。

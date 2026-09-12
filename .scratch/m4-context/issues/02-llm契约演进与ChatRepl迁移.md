# 02 — llm 契约演进与 ChatRepl 迁移（多轮记忆 + 自动继续）

## What to build

llm 契约从"单条用户消息"演进为"消息列表"（multi-turn），ChatRepl 迁移到会话域——同一 REPL 里多轮对话有记忆、启动自动继续最新会话、`/new` 开新会话、每轮事件同步落 `~/.duo/sessions`。本票完成时全量 CI 保持绿（契约改签名与消费方迁移同票交付）。

要点：

1. **llm 契约演进**：新契约类型 `ChatMessage(role, content)`（role: USER / ASSISTANT）；`ChatRequest` 签名演进为 `(systemPrompt, List<ChatMessage> messages)`——M3 的单消息形态废弃，唯一消费方（ChatReplMain）随本票迁移。OpenAiCompatAdapter 把 messages 数组序列化为协议的 multi-turn 消息。
2. **ChatRepl 接会话**：每轮——用户输入 `append(user/message)` → 投影 `deriveMessages()` → 转换为 `ChatMessage` 列表 → `adapter.stream` → chunk 逐个 `append(assistant/chunk)` → 流结束 `append(assistant/message)`（完整回复入日志）。
3. **自动继续**：启动时 `Session.latest(sessionsDir)`——有则加载并横幅显示 `继续会话 <id>（已有 N 条消息）`，无则新建；`/new` 命令开新会话（旧文件保留，横幅显示新 id）。
4. **错误呈现**：LLM 调用失败原样呈现（不写失败进会话日志），循环继续。
5. **冒烟测试**：mock 适配器捕获请求——断言第二轮请求的 messages 含第一轮内容（多轮记忆端到端）；`/new` 后上下文清空；横幅叙述。真实 LLM 的验收走对照表（路径 B）。

## Blocked by

01（会话域的 Session/Message 类型）

## Status

ready-for-agent

## Checklist

- [ ] ChatMessage（role enum + content）新契约类型；ChatRequest 演进为 (systemPrompt, List<ChatMessage>)
- [ ] OpenAiCompatAdapter 适配消息列表序列化；既有 mock 测试迁移到 multi-turn 形态
- [ ] ChatRepl 接会话：user/message / assistant/chunk / assistant/message 三种事件写入
- [ ] 自动继续最新会话（横幅显示 id 与消息数）+ /new 开新会话
- [ ] 冒烟测试：第二轮请求含第一轮内容（多轮记忆）、/new 后清空
- [ ] 验收对照表（本工单 Comments）：路径 B 真实对话预期输出

## Comments

### 验收对照表（M4 端到端）

**前置**：`~/.duo/config.yml` 配置 llm 段（baseUrl/apiKey/model，systemPrompt 可选）。

**演示路径**：

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.chat.ChatReplMain
```

第一进程（建立记忆）：

| 输入 | 预期输出 |
|---|---|
| （启动） | `=== duo-harness Chat Demo（M4：多轮对话 + 会话记忆）===` + `新会话 <id>。…` |
| `我叫小红` | `AI> 收到：我叫小红` |
| `我养了一只猫` | `AI> 收到：我养了一只猫` |
| `/exit` | `=== 对话结束 ===` |

第二进程（自动继续，验证多轮记忆与指代理解）：

| 输入 | 预期输出 |
|---|---|
| （启动） | `继续会话 <同一 id>（已有 4 条消息）。…` |
| `我的猫叫什么名字` | `AI> 收到：<猫的名字>`——**引用"我的猫"能被理解 = 多轮记忆生效** |
| `/new` | `已开新会话 <新 id>` |
| `我的猫叫什么名字` | `AI> 收到：我的猫叫什么`——上下文已清空，模型无从得知 |
| `/exit` | `=== 对话结束 ===` |

**测试路径**：`mvn -pl duo-harness-llm -am test`（LlmConfigTest 5 + OpenAiCompatAdapterTest 5，mock SSE 端点）与 `mvn -pl duo-harness-session -am test`（SessionTest 8）。

### 状态

待用户手动验收（对照表逐行核对）。通过后置 done。

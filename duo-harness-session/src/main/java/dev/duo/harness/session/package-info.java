/**
 * duo-harness-session 模块根包：会话事件溯源——`Session.append()` 单写原语、
 * 事件词汇（user/message、assistant/chunk、assistant/message）、deriveMessages
 * 投影（多轮记忆）与 JSONL 持久化（主要类型：SessionEvent / Session / Message）。
 * 会话是中立数据基座：llm / tools / agent / Web 都是消费方，本模块不依赖
 * 任何功能模块。契约平铺在本包（小域平铺形态）。
 */
package dev.duo.harness.session;

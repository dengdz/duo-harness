/**
 * duo-harness-llm 模块根包：LLM 调用契约——provider 中立的适配器接口与
 * 请求/chunk 类型（主要类型：LlmAdapter / ChatRequest / ChatChunk）。
 * 契约平铺在本包（小域平铺形态），实现（OpenAI 兼容适配器、配置加载）
 * 在 {@code llm.internal}，不对外暴露——例外：同仓示例模块（example）
 * 的装配入口直接使用内部实现类型，属框架自举场景；外部消费者一律走
 * {@link LlmAdapter} 契约。
 */
package dev.duo.harness.llm;

/**
 * duo-harness-llm 模块根包：LLM 调用契约——provider 中立的适配器接口与
 * 请求/chunk 类型（主要类型：LlmAdapter / ChatRequest / ChatChunk）。
 * 契约平铺在本包（小域平铺形态），实现（OpenAI 兼容适配器、配置加载）
 * 在 {@code llm.internal}，不对外暴露。
 */
package dev.duo.harness.llm;

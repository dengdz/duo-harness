/**
 * 插件容器内核的核心契约：插件形状（Plugin）、Context 句柄、可逆副作用
 * （Disposable）、插件实例句柄与状态（PluginHandle / PluginState / PluginStatus）、
 * 服务基类（Service）与异常类型。
 *
 * <p>事件契约在 {@code core.api.events}、引导契约在 {@code core.api.boot}；
 * 实现位于 {@code dev.duo.harness.core.internal}，不对外暴露。
 * API 为同步阻塞签名（ADR-0002），插件代码按顺序书写，并发由内核消化。</p>
 */
package dev.duo.harness.core.api;

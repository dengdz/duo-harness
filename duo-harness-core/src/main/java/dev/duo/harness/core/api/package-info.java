/**
 * 插件容器内核的公共 API：插件形状、Context 句柄、可逆副作用与插件实例句柄。
 *
 * <p>本包是内核对插件作者暴露的全部契约；实现位于 {@code dev.duo.harness.core.internal}，
 * 不对外可见。API 为同步阻塞签名（ADR-0002），插件代码按顺序书写，
 * 并发由内核消化。</p>
 */
package dev.duo.harness.core.api;

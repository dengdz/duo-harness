package dev.duo.harness.core.api;

/**
 * 作用域已销毁（C2 工单 13 类型化）：目标插件树已 dispose 后的加载/注册请求被
 * 拒绝——消费方（如 MCP 工具同步在树停止期间收到的清单更新）按类型识别「停止
 * 期间的失败可忽略」，不再依赖异常消息文案匹配（此前 MCP 侧以
 * {@code contains("作用域已销毁")} 判别——文案演进即静默失效，且反向锁死本侧
 * 文案不可改）。
 */
public final class ScopeDestroyedException extends PluginException {

    public ScopeDestroyedException(String message) {
        super(message);
    }
}

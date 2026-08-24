package dev.duo.harness.core.api;

/**
 * 插件容器内核异常基类。聚合多个回滚错误时，首个错误作为本异常抛出、
 * 其余挂 suppressed，保证兄弟副作用的失败互不掩盖。
 */
public class PluginException extends RuntimeException {

    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}

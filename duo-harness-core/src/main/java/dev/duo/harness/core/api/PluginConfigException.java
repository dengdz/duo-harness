package dev.duo.harness.core.api;

/**
 * 配置绑定失败：原始配置无法绑定到插件声明的 config 类型。
 * 消息包含插件标识与出错字段路径，供点名式报错直接展示。
 */
public class PluginConfigException extends PluginException {

    public PluginConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

package dev.duo.harness.tools;

import dev.duo.harness.core.api.PluginException;

/**
 * 工具未注册：执行点名报错。
 */
public class ToolNotFoundException extends PluginException {

    public ToolNotFoundException(String message) {
        super(message);
    }
}

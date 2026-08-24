package dev.duo.harness.core.api;

import java.util.List;

/**
 * boot 失败：携带阶段标签与逐行点名的问题清单，全部问题聚合呈现
 * （首个为主异常语义不适用——boot 审计一次报全，suppressed 挂各问题原因）。
 *
 * <p>boot 失败时整棵插件树已回滚，进程内无残留副作用。</p>
 */
public class BootException extends PluginException {

    /** boot 阶段标签：定位失败发生在哪一步。 */
    public enum Stage {
        /** 读取配置文件失败（不存在/不可读）。 */
        READ_CONFIG,
        /** 配置文件解析失败（非法 YAML / 结构不符）。 */
        PARSE_CONFIG,
        /** 插件行加载或审计失败（逐行问题见消息）。 */
        ACTIVATE
    }

    private final Stage stage;

    public BootException(Stage stage, String message) {
        super(message);
        this.stage = stage;
    }

    public BootException(Stage stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    /** 失败阶段。 */
    public Stage stage() {
        return stage;
    }

    /** 供聚合构造：主消息 + 各行问题作为 suppressed。 */
    public BootException(Stage stage, String summary, List<String> problems, List<Throwable> causes) {
        super(summary + "\n" + String.join("\n", problems));
        this.stage = stage;
        for (Throwable cause : causes) {
            addSuppressed(cause);
        }
    }
}

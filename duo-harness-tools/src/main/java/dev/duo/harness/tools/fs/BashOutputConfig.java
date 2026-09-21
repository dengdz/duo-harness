package dev.duo.harness.tools.fs;

/**
 * bash 输出三层预算（M23 工单 05，ADR-0025 决策二）：inline 尾窗（返回给模型的最近
 * 输出）、spill 帽（落盘文件上限，超帽停写并告警）、task-output 尾窗（后台任务读取
 * 的窗口）。fs-tools 行 config 的 {@code output} 段可配，缺席取缺省。
 *
 * @param inlineTailChars      返回文本的尾部窗口字符数（超窗部分落 spill）
 * @param spillMaxChars        spill 文件字符数上限（超帽停写并告警——不静默，修 DSH 坑）
 * @param taskOutputTailChars  task-output 读取的尾部窗口字符数
 */
public record BashOutputConfig(int inlineTailChars, long spillMaxChars, int taskOutputTailChars) {

    public static final int DEFAULT_INLINE_TAIL_CHARS = 30_000;
    public static final long DEFAULT_SPILL_MAX_CHARS = 64L * 1024 * 1024;
    public static final int DEFAULT_TASK_OUTPUT_TAIL_CHARS = 32_000;

    public static final BashOutputConfig DEFAULTS =
            new BashOutputConfig(DEFAULT_INLINE_TAIL_CHARS, DEFAULT_SPILL_MAX_CHARS,
                    DEFAULT_TASK_OUTPUT_TAIL_CHARS);

    /** 值域校验（正数）——非法即抛，调用方（插件装配）转 PluginException 点名。 */
    public BashOutputConfig {
        if (inlineTailChars < 1) {
            throw new IllegalArgumentException("inlineTailChars 至少为 1: " + inlineTailChars);
        }
        if (spillMaxChars < 1) {
            throw new IllegalArgumentException("spillMaxChars 至少为 1: " + spillMaxChars);
        }
        if (taskOutputTailChars < 1) {
            throw new IllegalArgumentException("taskOutputTailChars 至少为 1: " + taskOutputTailChars);
        }
    }
}

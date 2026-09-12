package dev.duo.harness.agent;

import java.util.Objects;

/**
 * 提示片段：注册进 prompt 注册表的最小提示单元。
 *
 * @param source  贡献者来源标识（审计与点名用，如 "safety"、"m7-skill:code-review"）
 * @param content 片段文本
 */
public record PromptFragment(String source, String content) {

    /** 构造时校验非空——错误前移到构造点。 */
    public PromptFragment {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("片段内容不能为空（source=" + source + "）");
        }
    }
}

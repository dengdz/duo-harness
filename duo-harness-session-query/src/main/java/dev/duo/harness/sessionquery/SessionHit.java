package dev.duo.harness.sessionquery;

/**
 * 单条检索命中：一个会话 + 该会话内最强匹配事件。
 *
 * @param sessionId      会话 id（JSONL 文件名去后缀）
 * @param title          会话标题（无标题事件为 null——呈现方回退 id）
 * @param lastModifiedMs 会话文件最近修改时间（epoch millis，并列排序与呈现用）
 * @param eventIndex     最强匹配事件的日志下标（0 起）
 * @param eventType      最强匹配事件类型（SessionEvent.* 常量）
 * @param eventAt        最强匹配事件时间戳（epoch millis）
 * @param snippet        就近窗口摘录，命中词以 {@code 【】} 包裹（截断处以 … 标注）
 * @param score          匹配强度（词元出现总次数 + 查询汉字原词整词加分；仅排序语义，非稳定承诺）
 */
public record SessionHit(String sessionId, String title, long lastModifiedMs,
                         int eventIndex, String eventType, long eventAt,
                         String snippet, int score) {

    /** 构造时校验非空——错误前移到构造点。 */
    public SessionHit {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(eventType, "eventType");
        java.util.Objects.requireNonNull(snippet, "snippet");
        if (score < 1) {
            throw new IllegalArgumentException("score 必须为正: " + score);
        }
    }
}

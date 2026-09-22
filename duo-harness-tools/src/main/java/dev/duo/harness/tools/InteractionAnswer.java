package dev.duo.harness.tools;

import java.util.List;
import java.util.Objects;

/**
 * 交互回答：回答者对一次交互请求的作答。
 *
 * <p>三类形态：</p>
 * <ul>
 *   <li>{@link #allow(String)}：审批放行，source 署名回答者；</li>
 *   <li>{@link #deny(String)}：审批拒绝，reason 呈现给模型的错误结果携带 source；</li>
 *   <li>{@link #allowAlways(String, String)}：放行并生成「总是允许」规则（M24 工单 02，
 *       {@code alwaysScope} 携作用域）——生成动作由呈现位包装层兑现，本记录只携带语义；</li>
 *   <li>{@link #answered(List, String)}：提问回答，values = 用户给出的答案
 *       （自由文本时为单元素）。</li>
 * </ul>
 *
 * @param approved    审批语义：true = 放行（提问回答恒为 true）
 * @param values      提问回答的答案列表（审批语义为空）
 * @param source      回答者来源标识（fail-closed 时为 {@link #SOURCE_FAIL_CLOSED}）
 * @param alwaysScope 「总是允许」作用域（approval 专用：project / session；其余形态 null）
 */
public record InteractionAnswer(boolean approved, List<String> values, String source, String alwaysScope) {

    /** fail-closed 来源标识：无回答者或人未作答时的署名。 */
    public static final String SOURCE_FAIL_CLOSED = "fail-closed";

    /** alwaysScope 取值：项目级（持久 settings.json）。 */
    public static final String SCOPE_PROJECT = "project";
    /** alwaysScope 取值：会话级（随会话事件流）。 */
    public static final String SCOPE_SESSION = "session";

    /** 构造时校验非空与防御性拷贝——错误前移到构造点；空白 scope 归一为 null。 */
    public InteractionAnswer {
        Objects.requireNonNull(source, "source");
        values = values == null ? List.of() : List.copyOf(values);
        alwaysScope = alwaysScope == null || alwaysScope.isBlank() ? null : alwaysScope;
    }

    /** 审批放行。 */
    public static InteractionAnswer allow(String source) {
        return new InteractionAnswer(true, List.of(), source, null);
    }

    /** 审批拒绝。 */
    public static InteractionAnswer deny(String source) {
        return new InteractionAnswer(false, List.of(), source, null);
    }

    /** 放行并生成「总是允许」规则（scope = {@link #SCOPE_PROJECT} / {@link #SCOPE_SESSION}）。 */
    public static InteractionAnswer allowAlways(String source, String scope) {
        return new InteractionAnswer(true, List.of(), source, scope);
    }

    /** 提问回答（values 为用户给出的答案；至少一项）。 */
    public static InteractionAnswer answered(List<String> values, String source) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("提问回答至少一项（values）");
        }
        return new InteractionAnswer(true, values, source, null);
    }

    /** fail-closed：无回答者在场或人未作答——一律按拒绝处理。 */
    public static InteractionAnswer failClosed() {
        return deny(SOURCE_FAIL_CLOSED);
    }
}

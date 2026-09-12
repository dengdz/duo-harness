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
 *   <li>{@link #answered(List, String)}：提问回答，values = 用户给出的答案
 *       （自由文本时为单元素）。</li>
 * </ul>
 *
 * @param approved 审批语义：true = 放行（提问回答恒为 true）
 * @param values   提问回答的答案列表（审批语义为空）
 * @param source   回答者来源标识（fail-closed 时为 {@link #SOURCE_FAIL_CLOSED}）
 */
public record InteractionAnswer(boolean approved, List<String> values, String source) {

    /** fail-closed 来源标识：无回答者或人未作答时的署名。 */
    public static final String SOURCE_FAIL_CLOSED = "fail-closed";

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public InteractionAnswer {
        Objects.requireNonNull(source, "source");
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** 审批放行。 */
    public static InteractionAnswer allow(String source) {
        return new InteractionAnswer(true, List.of(), source);
    }

    /** 审批拒绝。 */
    public static InteractionAnswer deny(String source) {
        return new InteractionAnswer(false, List.of(), source);
    }

    /** 提问回答（values 为用户给出的答案；至少一项）。 */
    public static InteractionAnswer answered(List<String> values, String source) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("提问回答至少一项（values）");
        }
        return new InteractionAnswer(true, values, source);
    }

    /** fail-closed：无回答者在场或人未作答——一律按拒绝处理。 */
    public static InteractionAnswer failClosed() {
        return deny(SOURCE_FAIL_CLOSED);
    }
}

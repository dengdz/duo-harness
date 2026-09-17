package dev.duo.harness.agent.subagent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 子代理的框架级基线提示（M15）：每个子代理的 system 提示 = 本基线 + 模板专属提示
 * （可选）。分工对齐同类实现的形态——**基线承载框架通用纪律，模板只写角色**：
 *
 * <ul>
 *   <li>基线（本类，框架维护）：子代理的身份与约束——一次性执行、无跨任务记忆、
 *       任务范围外不深挖、结果被截断时改用更精确查询而非重复读取、结论即交付物。
 *       这些是委派形态固有的，不该让每个部署者在每个模板里重写一遍；</li>
 *   <li>模板专属提示（部署者，可省）：本模板的角色与领域约束（如"你是调研助手，
 *       只做信息收集"）；</li>
 *   <li>任务描述（父 agent 每次现场生成）：目标、范围、期望产出——自包含，是子代理
 *       掌握的全部任务背景。</li>
 * </ul>
 *
 * <p>基线条文来自本期的真实任务实测：子代理在长任务上出现的重复读取（43 次调用中
 * 26 次重复）、范围蔓延、只描述过程不给结论，都由下列条款对症约束。</p>
 */
final class SubagentBaseline {

    /** 基线正文（精炼、通用、与具体任务无关——避免与模板/任务描述冗余）。 */
    static final String TEXT = """
            你是被主 agent 委派执行单一子任务的子代理。遵守以下约束：
            - 你看不到主对话的后续进展，也没有跨任务记忆：任务描述（及可能附带的历史背景快照）\
            就是你的全部背景，不要假设还有别处可查的上下文。
            - 只做交接给你的这件事：任务范围外的内容不必深挖，不要无谓扩大调研或改动面。
            - 工具结果被截断或过长时，改用更精确的 pattern/path 缩小范围重新查询——\
            不要重复读取同一个目标。
            - 达成任务目标后直接给出结论；结论就是你的交付物，不要只描述过程。""";

    private SubagentBaseline() {
    }

    /**
     * 组装子代理 system 提示：基线在前（通用纪律）→ 运行环境段 → 模板专属提示
     * （角色与领域约束）。模板提示为空则只有基线 + 环境段——不给 provider 的缺省
     * 提示兜底（子代理需要自己的身份约束）。
     */
    static String compose(String templatePrompt) {
        return compose(templatePrompt, environmentBlock());
    }

    /** 环境块显式版（测试注入固定环境，断言不受机器差异影响）。 */
    static String compose(String templatePrompt, String environment) {
        String composed = TEXT;
        if (environment != null && !environment.isBlank()) {
            composed += "\n\n" + environment;
        }
        if (templatePrompt == null || templatePrompt.isBlank()) {
            return composed;
        }
        return composed + "\n\n" + templatePrompt;
    }

    /**
     * 运行环境段（M16 工单 04，ZCode env 块对照）：工作目录/平台/当前时间——
     * 子代理 spawn 时现场生成（时间新鲜）。没有它，模型对运行环境两眼一抹黑
     * （不知道自己在哪、时间靠猜）。数据取进程属性，与 bash 工具缺省工作目录同源。
     */
    static String environmentBlock() {
        return "## 运行环境\n"
                + "- 工作目录：" + System.getProperty("user.dir") + "\n"
                + "- 操作系统：" + System.getProperty("os.name") + "\n"
                + "- 当前时间：" + LocalDateTime.now().format(TIME_FORMATTER);
    }

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}

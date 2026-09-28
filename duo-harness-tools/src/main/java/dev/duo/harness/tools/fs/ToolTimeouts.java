package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具等待超时单点（C2 工单 09）：bash 前台与 task-output 共用的等待上限、
 * clamp 逻辑与管线放宽模式——此前 clamp 双份实现（bash 的 timeoutFor 与
 * task-output 的内联）、600s 上限双份常量，且 task-output 漏套「自带协作式
 * 超时 → 管线放宽」模式：默认部署（管线缺省 120s）下模型按工具契约传
 * {@code timeoutMs > 115000} 的长等待必被管线超时腰斩——参数面承诺 600s 是
 * 一句假话。单点化后两工具同口径。
 *
 * <p>各工具的<b>缺省</b>等待值语义不同（bash 120s / task-output 30s），由调用方
 * 传入；上限与余量全局一致。</p>
 */
final class ToolTimeouts {

    /** 等待上限（bash 前台与 task-output 同值同义：模型可请求的最大等待）。 */
    static final long MAX_WAIT_MS = 600_000;

    /** 自带协作式超时的工具：管线上限放宽到本次等待之上 5s——终止归协作式，管线只兜挂死（ADR-0018）。 */
    static final long PIPELINE_HEADROOM_MS = 5_000;

    private ToolTimeouts() {
    }

    /** 超时 clamp：正数取 min(值, 上限)，非数值/非正数按调用方缺省。 */
    static long clamp(JsonNode args, long defaultMs) {
        JsonNode node = args.path("timeoutMs");
        return node.isNumber() && node.asLong() > 0
                ? Math.min(node.asLong(), MAX_WAIT_MS)
                : defaultMs;
    }

    /** 管线超时覆写（协作式超时工具的放宽形态）：clamp 值 + 余量。 */
    static long pipelineTimeout(JsonNode args, long defaultMs) {
        return clamp(args, defaultMs) + PIPELINE_HEADROOM_MS;
    }
}

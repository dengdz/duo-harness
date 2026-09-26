package dev.duo.harness.llm;

/**
 * cacheControl 三级断点（M25 工单 06，探测工单 10 依据）：对每轮重复携带的稳定前缀
 * 打缓存断点，provider 命中缓存即省 token 提速；断点失效自然回源（provider 语义，
 * 无隐性成本）。
 *
 * <p>三级划分（duo 的 system 为单列字符串，以片段边界切分）：</p>
 * <ul>
 *   <li><b>身份前缀</b>（identity prefix）：system 的首段（yml {@code llm.systemPrompt}
 *       用户指令——装配期固定，跨会话不变）——断点 1；</li>
 *   <li><b>稳定身份</b>（stable body）：其余静态片段（AGENTS.md / memory-guide /
 *       技能清单——注册表不变则逐轮逐字节相同）——断点 2；</li>
 *   <li><b>动态段</b>（dynamic）：会话消息 + reminder（每轮变化）——不打 system
 *       断点，由 anthropic 适配器在最后一条消息上打消息级断点（断点 3）。</li>
 * </ul>
 *
 * <p>provider 映射（四行策略）：anthropic = system 块数组 + {@code cache_control:
 * ephemeral}（三级全打）；openai-compat / glm = provider 自动隐式前缀缓存（无断点
 * 参数，静默降级零报错，usage 的 cached 计数照常透出）；deepseek = 走 openai-compat
 * 适配器，缓存命中字段名异构（{@code prompt_cache_hit_tokens}）——适配器双字段
 * 兼容解析，同样静默透出。</p>
 */
public final class CacheControl {

    private CacheControl() {
    }

    /**
     * 三级划分纯函数：首个「双换行」边界为身份前缀/稳定身份的切分线。
     *
     * <p>边界假设（与 {@code PromptRegistry.compose} 组装序一致）：userPrompt 在最前、
     * 片段以空行拼接——首边界即用户指令与静态片段的分界。yml systemPrompt 自含
     * 空行时边界会落在其内部（prefix = systemPrompt 前半）：两段仍跨轮稳定，断点
     * 语义不受影响。切分线本身的双换行归属 body 侧（组块拼接时以 \n\n 还原）。</p>
     *
     * @param system 完整 system 提示（userPrompt 已由 PromptRegistry 拼在最前）
     * @return 划分结果（stableBody 无第二段时为 null——单断点形态；identityPrefix
     *         亦可为 null——空前缀防御归并，不产出空文本块）
     */
    public static Segments split(String system) {
        if (system == null || system.isBlank()) {
            return new Segments(null, null);
        }
        int boundary = system.indexOf("\n\n");
        if (boundary < 0) {
            return new Segments(system, null);
        }
        String prefix = system.substring(0, boundary);
        String body = system.substring(boundary + 2);
        if (body.isBlank()) {
            return new Segments(system, null);
        }
        return new Segments(prefix.isBlank() ? null : prefix, body);
    }

    /**
     * 划分产物：身份前缀（断点 1）+ 稳定身份（断点 2）；动态段在消息侧（断点 3，
     * 适配器打在最后一条消息）。
     *
     * @param identityPrefix 身份前缀（userPrompt 固定头；null = 无前缀防御形态，
     *                       调用方不产出该块）
     * @param stableBody     稳定身份段（静态片段拼接；null = 无第二段单断点形态）
     */
    public record Segments(String identityPrefix, String stableBody) {
    }
}

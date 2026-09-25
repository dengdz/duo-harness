package dev.duo.harness.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.agent.prompt.PromptFragment;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.agent.prompt.PromptsView;

import java.nio.file.Path;
import java.util.Set;

/**
 * 记忆本插件（M25 工单 02）：发布记忆本为 "memory" 服务；记忆本在场时把规范段
 * （memory-guide 片段）注册进 prompt 注册表——缺席不注册（无约定即无注入，
 * AgentsMdPlugin 同款静默降级）。读路径注入与预算见 {@link MemoryBook}；
 * 写协议随 M25 工单 03。
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   budgetChars: 16384   # 注入预算字符数（省略即 16KB）}</pre>
 *
 * <p>inject prompts：规范段是 prompt 注册表的消费者（标准服务注入模式）；
 * prompts 缺位时本插件 PENDING 点名可见。</p>
 */
public final class MemoryPlugin implements Plugin<JsonNode> {

    /** 规范段来源标识（prompt 注册表 source）。 */
    public static final String GUIDE_SOURCE = "memory-guide";

    /** 配置键：注入预算字符数。 */
    private static final String BUDGET_CHARS_CONFIG = "budgetChars";

    /** 规范段文本（读路径语义；写协议文本随工单 03 增补）。 */
    static final String GUIDE_TEXT = """
            ## memory 记忆本（.duo/MEMORY.md）

            本项目维护跨会话记忆本：项目根 `.duo/MEMORY.md`（个人记忆，不入 git）。其当前内容每轮请求以 <memory> 段注入——条目为用户与既往会话沉淀的持久记忆，可能过时；与用户当前指令冲突时，以用户当前指令为准。""";

    @Override
    public Set<String> inject() {
        return Set.of(PromptRegistry.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        long budget = config != null && config.hasNonNull(BUDGET_CHARS_CONFIG)
                && config.get(BUDGET_CHARS_CONFIG).asLong() > 0
                ? config.get(BUDGET_CHARS_CONFIG).asLong() : MemoryBook.DEFAULT_BUDGET_CHARS;
        MemoryBook memory = MemoryBook.load(Path.of(System.getProperty("user.dir")), budget);
        Disposable published = ctx.provide(MemoryBook.SERVICE_NAME, memory);
        PromptRegistry prompts = ctx.as(PromptsView.class).prompts();
        // 规范段以装配时刻的在场性判定（启动快照，不随后续文件增删重建）；读路径
        // 注入则是每请求现读——装配后新建 MEMORY.md 会有注入无规范段（写协议随
        // 工单 03 落地时一并收敛时机）。缺席（含空白）零注册——未启用用户零感知
        Disposable guide = memory.read() == null
                ? null : prompts.register(ctx, new PromptFragment(GUIDE_SOURCE, GUIDE_TEXT));
        return () -> {
            published.dispose();
            if (guide != null) {
                guide.dispose();
            }
        };
    }
}

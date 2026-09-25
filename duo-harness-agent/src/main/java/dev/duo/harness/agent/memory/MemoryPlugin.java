package dev.duo.harness.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.agent.prompt.PromptFragment;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.agent.prompt.PromptsView;
import dev.duo.harness.tools.ToolsService;

import java.nio.file.Path;
import java.util.Set;

/**
 * 记忆本插件（M25）：发布记忆本为 "memory" 服务 + 规范段（memory-guide 片段，
 * 读路径语义与写协议）注册进 prompt 注册表 + 写通道接入——记忆本读路径可用
 * （文件在场）或写通道可用（tools 行在场，注册 memory_write 追加工具）任一成立
 * 即注册规范段；两者皆不可用（纯对话装配且无记忆本）零注入零注册。读路径注入
 * 与预算见 {@link MemoryBook}，写工具见 {@link MemoryWriteTool}。
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   budgetChars: 16384   # 注入预算字符数（省略即 16KB）}</pre>
 *
 * <p>inject prompts + optionalInject tools：规范段是 prompt 注册表的必需消费者
 * （prompts 缺位 PENDING 点名可见）；工具域为可选依赖（纯对话装配只保留读路径）。</p>
 */
public final class MemoryPlugin implements Plugin<JsonNode> {

    /** 规范段来源标识（prompt 注册表 source）。 */
    public static final String GUIDE_SOURCE = "memory-guide";

    /** 配置键：注入预算字符数。 */
    private static final String BUDGET_CHARS_CONFIG = "budgetChars";

    /** 规范段文本（读路径 + 写协议；指令形态——BUG-20260925-02 经验）。 */
    static final String GUIDE_TEXT = """
            ## memory 记忆本（.duo/MEMORY.md）

            本项目维护跨会话记忆本：项目根 `.duo/MEMORY.md`（个人记忆，不入 git）。其内容每轮请求以 <memory> 段注入——回答与记忆本相关的问题时，直接引用本轮 <memory> 段作答：它是当前最新内容，无需再读取该文件（除非用户明确要求查看文件本身）。条目为用户与既往会话沉淀的持久记忆，可能滞后于项目现状；与用户当前指令冲突时，以用户当前指令为准。

            写路径：用户让你"记住 X"或交代需要跨会话延续的偏好/约定/事实时，调用 memory_write 追加一条（写成独立完整的一句话，已记过的不重复写）；没有 <memory> 段表示记忆本当前为空或未启用——用户让你记住时照常调用 memory_write（首次写入会创建文件）。用户要修改或删除记忆条目时，告知其直接编辑 .duo/MEMORY.md（每行一条）。若你的工具清单中没有 memory_write，则告知用户当前装配未启用记忆写入。""";

    @Override
    public Set<String> inject() {
        return Set.of(PromptRegistry.SERVICE_NAME);
    }

    /** 写通道可选接入：tools 行缺席（纯对话装配）只保留读路径，不 PENDING。 */
    @Override
    public Set<String> optionalInject() {
        return Set.of(ToolsService.SERVICE_NAME);
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
        // 写通道：tools 行在场注册 memory_write（追加式独占工具，事件痕走工具
        // 管线的 tool/call + tool/result），缺席零感——读路径不受牵连
        ToolsService tools = ctx.hasService(ToolsService.SERVICE_NAME)
                ? ctx.as(MemoryToolsView.class).tools() : null;
        Disposable tool = tools == null ? null : tools.register(ctx, new MemoryWriteTool(memory));
        // 规范段注册条件：功能有任一可用面（读路径=记忆本在场；写通道=tools 在场）
        // 即有指南——写路径让"首次记住即创建文件"成为正常入口，不能以文件缺席为由
        // 缺席；两者皆不可用才是真正的未启用（零注入零注册）。指南为装配时注册，
        // 不随后续文件增删重建
        boolean guideDue = memory.read() != null || tool != null;
        Disposable guide = guideDue
                ? prompts.register(ctx, new PromptFragment(GUIDE_SOURCE, GUIDE_TEXT)) : null;
        return () -> {
            published.dispose();
            if (guide != null) {
                guide.dispose();
            }
            if (tool != null) {
                tool.dispose();
            }
        };
    }

    /** tools 服务的视图接口（方法名即服务名 "tools"）。 */
    interface MemoryToolsView {

        ToolsService tools();
    }
}

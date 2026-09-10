package dev.duo.harness.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工具同步器：把单个 MCP 服务器的远端工具转换为本地工具并注册进工具域。
 *
 * <p>两阶段换新：阶段一全量拉取 + 转换 + 命名校验（此阶段失败旧代原样保留）；
 * 阶段二注销旧代、注册新代（注册中途失败 best-effort 恢复旧代）。</p>
 *
 * <p>同步来源有两个：连接建立后的全量同步、远端 tools/list_changed 通知触发的
 * 重同步。两者都经 {@code syncLock} 串行，且通知线程与 supervisor 线程不会交错。
 * 锁用 {@link ReentrantLock} 而非 {@code synchronized}：阻塞 IO 在 monitor 内
 * 会 pin 虚拟线程（ADR-0002）。</p>
 */
final class McpToolSync {

    private static final Logger log = LoggerFactory.getLogger(McpToolSync.class);

    private final String serverName;
    private final Context pluginContext;
    private final ToolsService tools;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 同步串行锁：supervisor 线程的连接同步与 list_changed 通知线程不交错。 */
    private final ReentrantLock syncLock = new ReentrantLock();
    /** 当前代工具定义（旧代恢复用）。 */
    private final List<ToolDefinition> currentDefs = new ArrayList<>();
    /** 当前代注册器（dispose 即注销对应工具）。 */
    private final List<Disposable> currentGen = new ArrayList<>();
    /** 当前连接的客户端（execute 映射用）；断连/预算耗尽后为 null。 */
    private volatile McpSyncClient activeClient;

    McpToolSync(String serverName, Context pluginContext, ToolsService tools) {
        this.serverName = serverName;
        this.pluginContext = pluginContext;
        this.tools = tools;
    }

    /** 连接建立后的全量同步（异常即本次连接尝试失败，由 supervisor 重连预算裁决）。 */
    void sync(McpSyncClient client) {
        syncLock.lock();
        try {
            apply(client, fetchAndConvert(client));
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * 远端 tools/list_changed：按通知载荷重同步。
     *
     * <p>通知是异步的，可能在断连/停止后抵达。命名冲突在此点名（warn，
     * 与 {@link #sync} 的抛错行为一致——spec 的"清洗冲突即失败点名"）；
     * 作用域已销毁（插件停止中）静默跳过。工具名集合未变时跳过——
     * MCP 的 list_changed 语义是清单变更（增/删），不是单个工具定义变更，
     * 同名集合意味着无实质变更。</p>
     */
    void onToolsChanged(List<McpSchema.Tool> remoteTools) {
        syncLock.lock();
        try {
            McpSyncClient active = activeClient;
            if (active == null) {
                log.debug("MCP 服务器 [{}] 的 tools/list_changed 在断连期间抵达，忽略", serverName);
                return;
            }
            Set<String> newNames = new HashSet<>();
            for (McpSchema.Tool tool : remoteTools) {
                newNames.add(publicToolName(serverName, tool.name()));
            }
            Set<String> currentNames = new HashSet<>();
            for (ToolDefinition def : currentDefs) {
                currentNames.add(def.name());
            }
            if (newNames.equals(currentNames)) {
                log.debug("MCP 服务器 [{}] 的 tools/list_changed 抵达，工具清单未变，跳过",
                        serverName);
                return;
            }
            try {
                apply(active, convertAll(remoteTools));
            } catch (PluginException e) {
                if (e.getMessage().contains("作用域已销毁")) {
                    // 插件停止中：通知自然无效，下次连接的全量同步会带上最新清单
                    log.debug("MCP 服务器 [{}] 的 tools/list_changed 在停止期间抵达，忽略",
                            serverName);
                } else {
                    // 命名冲突等应点名的失败：warn 呈现，不静默
                    log.warn("MCP 服务器 [{}] 的 tools/list_changed 重同步失败", serverName, e);
                }
            }
        } finally {
            syncLock.unlock();
        }
    }

    /** 预算耗尽：注销当前代全部工具。 */
    void unregisterAll() {
        syncLock.lock();
        try {
            disposeCurrentGen();
            currentDefs.clear();
            activeClient = null;
            log.warn("MCP 服务器 [{}] 的全部工具已注销", serverName);
        } finally {
            syncLock.unlock();
        }
    }

    /** 阶段二：注销旧代 → 注册新代（注册中途失败 best-effort 恢复旧代）。 */
    private void apply(McpSyncClient client, List<ToolDefinition> defs) {
        List<ToolDefinition> oldDefs = List.copyOf(currentDefs);
        disposeCurrentGen();
        currentDefs.clear();
        List<Disposable> registered = new ArrayList<>();
        try {
            for (ToolDefinition def : defs) {
                registered.add(tools.register(pluginContext, def));
            }
        } catch (RuntimeException e) {
            // 注册中途失败（如外部工具占了清洗后的名字）：丢弃半新代
            registered.forEach(McpToolSync::disposeQuietly);
            // best-effort 恢复旧代（重名残留会被下次同步覆盖）
            restoreOldGen(oldDefs);
            throw e;
        }
        currentGen.addAll(registered);
        currentDefs.addAll(defs);
        this.activeClient = client;
        log.info("MCP 服务器 [{}] 工具同步完成：{} 个工具已注册", serverName, defs.size());
    }

    /** best-effort 恢复旧代：逐个重新注册，重名跳过（残留被下次同步覆盖）。 */
    private void restoreOldGen(List<ToolDefinition> oldDefs) {
        for (ToolDefinition def : oldDefs) {
            try {
                currentGen.add(tools.register(pluginContext, def));
                currentDefs.add(def);
            } catch (RuntimeException e) {
                log.debug("MCP 服务器 [{}] 旧代工具 [{}] 恢复失败（忽略，随下次同步覆盖）",
                        serverName, def.name(), e);
            }
        }
    }

    private void disposeCurrentGen() {
        currentGen.forEach(McpToolSync::disposeQuietly);
        currentGen.clear();
    }

    private static void disposeQuietly(Disposable disposable) {
        try {
            disposable.dispose();
        } catch (Exception e) {
            log.warn("工具注销失败（忽略，随重同步覆盖）", e);
        }
    }

    /** 阶段一：拉取远端清单并转换；命名清洗冲突在此点名失败（不动注册表）。 */
    private List<ToolDefinition> fetchAndConvert(McpSyncClient client) {
        return convertAll(client.listTools().tools());
    }

    private List<ToolDefinition> convertAll(List<McpSchema.Tool> remoteTools) {
        List<ToolDefinition> defs = new ArrayList<>(remoteTools.size());
        Set<String> names = new HashSet<>();
        for (McpSchema.Tool tool : remoteTools) {
            ToolDefinition def = convert(tool);
            if (!names.add(def.name())) {
                throw new PluginException("MCP 服务器 [" + serverName + "] 存在命名清洗后重名的工具: "
                        + def.name());
            }
            defs.add(def);
        }
        return defs;
    }

    /**
     * 远端 Tool → 本地 ToolDefinition。
     *
     * <p>双轨制：远端声明了 outputSchema 则带入本地输出契约（与本地工具同标准，
     * 违约由工具域点名）；未声明则宽松透传。调用映射优先 structuredContent
     * （outputSchema 校验的对象），无则回退 text content。</p>
     */
    private ToolDefinition convert(McpSchema.Tool tool) {
        String publicName = publicToolName(serverName, tool.name());
        String description = (tool.description() == null ? "" : tool.description())
                + "（MCP: " + serverName + "）";
        JsonNode parameters = mapper.valueToTree(tool.inputSchema());
        JsonNode output = tool.outputSchema() == null
                ? null
                : mapper.valueToTree(tool.outputSchema());
        return new ToolDefinition() {
            @Override
            public String name() {
                return publicName;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonNode parameters() {
                return parameters;
            }

            @Override
            public JsonNode output() {
                return output;
            }

            @Override
            public Object execute(ToolExecution execution) {
                Map<String, Object> args = execution.args().isNull()
                        ? Map.of()
                        : mapper.convertValue(execution.args(), Map.class);
                McpSchema.CallToolResult result =
                        activeClient.callTool(new McpSchema.CallToolRequest(tool.name(), args));
                // isError 先于 structuredContent：错误形态可能携带结构化载荷，不能当成功返回
                String text = extractText(result);
                if (Boolean.TRUE.equals(result.isError())) {
                    // 抛错：三段管线把它收敛为 error 结果（工具错误是业务结果不是系统故障）
                    throw new PluginException("MCP 工具返回错误: " + text);
                }
                if (result.structuredContent() != null) {
                    return result.structuredContent();
                }
                return text;
            }
        };
    }

    /** 拼接全部 text content（非文本块跳过）。 */
    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /** 命名清洗：非法字符替换为下划线；清洗冲突由本类的转换阶段点名。 */
    static String publicToolName(String serverName, String rawToolName) {
        return "mcp__" + serverName + "__"
                + rawToolName.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}

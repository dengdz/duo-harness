package dev.duo.harness.hooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * hooks 插件（ADR-0019）：复用 Claude Code/Codex hooks 配置格式的外部命令钩子，
 * 挂工具三段管线的准入/结果治理段。配置在 {@code ~/.duo/hooks.json}（与 boot yml
 * 行解耦）；boot yml 装本插件行即 opt-in——不装行零感知，装行而配置缺失/为空 =
 * ACTIVE 空转。
 *
 * <p>失败语义 fail-open（ADR-0019 决策 2）：钩子起不来、超时、非零退出（非 2）
 * 一律放行 + WARN——外部脚本不承担执法边界，duo 的硬闸门由 guard/审批承担。
 * 阻断通道：PreToolUse 钩子 exit 2 → 调用否决，stderr 回给模型；与审批同段
 * 先到先决，deny 占先（管线既有约定），且钩子只收不放（无"跳过审批放行"能力，
 * 与 guard 单调否决同哲学）。</p>
 *
 * <p>并发零改动（ADR-0019 决策 6）：钩子在每次调用的管线内部执行（工作线程内），
 * 不参与 M17 分组判定与屏障；慢钩子只拖慢自己所在组，成对有序提交不受影响。</p>
 */
public final class HooksPlugin implements Plugin<Void> {

    private static final Logger log = LoggerFactory.getLogger(HooksPlugin.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** hooks 配置文件名（DuoHome 根下，ADR-0019 决策 1）。 */
    public static final String CONFIG_FILE = "hooks.json";

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        // hooks 配置在独立文件，插件行不携带 config（yml 行无需 config 块）
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) throws Exception {
        Path file = DuoHome.resolve().root().resolve(CONFIG_FILE);
        HooksConfig hooks = HooksConfig.load(file);
        if (!hooks.skippedEvents().isEmpty()) {
            log.warn("hooks 配置含暂不支持的事件（已跳过）: {}", hooks.skippedEvents());
        }
        List<HookRule> preRules = hooks.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE);
        List<HookRule> postRules = hooks.rulesFor(HooksConfig.EVENT_POST_TOOL_USE);
        if (preRules.isEmpty() && postRules.isEmpty()) {
            log.info("hooks 配置为空或无受支持事件规则（{}），插件空转", file);
            return null;
        }
        // 两段监听器注册即作用域 effect（随插件停止自动摘除）；返回值仅为手动摘除器
        if (preRules.isEmpty()) {
            return mountPostToolUse(ctx, postRules);
        }
        if (postRules.isEmpty()) {
            return mountPreToolUse(ctx, preRules);
        }
        mountPostToolUse(ctx, postRules);
        return mountPreToolUse(ctx, preRules);
    }

    /**
     * PreToolUse → {@code tools/pre-execute}（ADR-0019 决策 3）：命中 matcher 的
     * 钩子按配置序逐个执行——exit 2 即否决（stderr 回给模型）；exit 0 时 stdout
     * JSON 裁定（{@code permissionDecision} / legacy {@code decision} 双形，
     * reason 优先呈现；allow 等价放行）；其余放行。监听器不调 next 即否决，
     * 内层监听器与工具本体不执行。
     */
    private Disposable mountPreToolUse(Context ctx, List<HookRule> rules) {
        return ctx.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    for (HookHandler handler : HookMatcher.matched(rules, exec.toolName())) {
                        HookRunner.Outcome outcome = HookRunner.run(handler,
                                payload(HooksConfig.EVENT_PRE_TOOL_USE, exec.toolName(),
                                        exec.args(), null, exec.presenterId()));
                        if (!outcome.produced()) {
                            log.warn("PreToolUse 钩子未产生裁定，放行（fail-open）: {}",
                                    outcome.diagnosis(handler.command()));
                            continue;
                        }
                        if (outcome.exitCode() == 2) {
                            String stderr = outcome.stderr().strip();
                            return deny(exec, handler,
                                    "被 PreToolUse 钩子阻断" + (stderr.isEmpty() ? "" : ": " + stderr));
                        }
                        if (outcome.exitCode() != 0) {
                            log.warn("PreToolUse 钩子非零退出（非阻断，放行）: exit={} stderr={}",
                                    outcome.exitCode(), outcome.stderr().strip());
                            continue;
                        }
                        PreDecision decision = parseDecision(outcome.stdout());
                        if (decision.unparseable()) {
                            // Claude Code 同款：schema 非法 JSON = 非阻断错误，调用继续
                            log.warn("PreToolUse 钩子 stdout 非法 JSON（非阻断，放行）: 钩子={} stdout={}",
                                    handler.command(), outcome.stdout().strip());
                            continue;
                        }
                        if (decision.denied()) {
                            String detail = decision.reason() != null && !decision.reason().isBlank()
                                    ? decision.reason() : outcome.stderr().strip();
                            return deny(exec, handler,
                                    "被 PreToolUse 钩子阻断" + (detail.isEmpty() ? "" : ": " + detail));
                        }
                        if (decision.asksApproval()) {
                            // Claude Code 三值语义的 ask：声明需审批而非自行裁决——交审批段
                            // 解析者（解析在 pre 瀑布结束后，注册序无关），无人解析按
                            // "未配置即拒"，仍是只收不放
                            log.info("PreToolUse 钩子转审批: 工具={} 钩子={}",
                                    exec.toolName(), handler.command());
                            exec.requestApproval();
                            return next.invoke(exec);
                        }
                        // allow 与无裁定等价放行（不带改写，updatedInput 不在一期基线）
                    }
                    return next.invoke(exec);
                });
    }

    /**
     * PostToolUse → {@code tools/post-execute}（ADR-0019 决策 4）：工具已执行，
     * exit 2 的"阻断" = 结果改写为错误形态（stderr/reason 回给模型）——不假装
     * 撤销副作用（audit-only）。改写后不调 next：结果治理到此定局（拒绝不可翻回，
     * 与 guard 单调否决同哲学）。
     */
    private Disposable mountPostToolUse(Context ctx, List<HookRule> rules) {
        return ctx.on(ToolsService.POST_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    for (HookHandler handler : HookMatcher.matched(rules, exec.toolName())) {
                        HookRunner.Outcome outcome = HookRunner.run(handler,
                                payload(HooksConfig.EVENT_POST_TOOL_USE, exec.toolName(),
                                        exec.args(), exec.result(), exec.presenterId()));
                        if (!outcome.produced()) {
                            log.warn("PostToolUse 钩子未产生裁定，结果原样（fail-open）: {}",
                                    outcome.diagnosis(handler.command()));
                            continue;
                        }
                        if (outcome.exitCode() == 2) {
                            String stderr = outcome.stderr().strip();
                            log.info("钩子标记工具结果: 工具={} 钩子={} 理由={}",
                                    exec.toolName(), handler.command(), stderr);
                            exec.markError("被 PostToolUse 钩子阻断"
                                    + (stderr.isEmpty() ? "" : ": " + stderr));
                            return Boolean.TRUE;
                        }
                        if (outcome.exitCode() != 0) {
                            log.warn("PostToolUse 钩子非零退出（非阻断，结果原样）: exit={} stderr={}",
                                    outcome.exitCode(), outcome.stderr().strip());
                            continue;
                        }
                        // exit 0 stdout JSON：deny/block（legacy）在 Post 段与 exit 2 同义——结果改写
                        PreDecision decision = parseDecision(outcome.stdout());
                        if (decision.unparseable()) {
                            log.warn("PostToolUse 钩子 stdout 非法 JSON（非阻断，结果原样）: 钩子={} stdout={}",
                                    handler.command(), outcome.stdout().strip());
                            continue;
                        }
                        if (decision.denied()) {
                            String detail = decision.reason() != null && !decision.reason().isBlank()
                                    ? decision.reason() : outcome.stderr().strip();
                            log.info("钩子标记工具结果（JSON）: 工具={} 钩子={} 理由={}",
                                    exec.toolName(), handler.command(), detail);
                            exec.markError("被 PostToolUse 钩子阻断"
                                    + (detail.isEmpty() ? "" : ": " + detail));
                            return Boolean.TRUE;
                        }
                    }
                    return next.invoke(exec);
                });
    }

    /** PreToolUse 否决：deny + 不调 next（审批段不执行——管线既有"deny 占先"约定）。 */
    private Boolean deny(ToolExecution exec, HookHandler handler, String reason) {
        log.info("钩子阻断工具调用: 工具={} 钩子={} 理由={}", exec.toolName(), handler.command(), reason);
        exec.deny(reason);
        return Boolean.FALSE;
    }

    /**
     * exit 0 stdout 的 JSON 裁定，三形兼容：Claude Code
     * {@code hookSpecificOutput.permissionDecision}、扁平
     * {@code permissionDecision}、legacy {@code {decision, reason}}（block=deny）。
     * stdout 不以 {@code {} 开头 = 无裁定（普通输出不解析）。
     */
    private PreDecision parseDecision(String stdout) {
        String text = stdout == null ? "" : stdout.strip();
        if (!text.startsWith("{")) {
            return new PreDecision(null, null, false);
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            JsonNode specific = node.path("hookSpecificOutput").path("permissionDecision");
            String decision = specific.isTextual() ? specific.asText()
                    : node.path("permissionDecision").isTextual()
                            ? node.path("permissionDecision").asText() : null;
            if (decision == null) {
                // legacy 形态：decision=block/allow + reason
                JsonNode legacy = node.path("decision");
                if (legacy.isTextual()) {
                    return new PreDecision(legacy.asText(), textReason(node.path("reason")), false);
                }
                return new PreDecision(null, null, false);
            }
            JsonNode reasonNode = node.path("hookSpecificOutput").path("permissionDecisionReason");
            if (!reasonNode.isTextual()) {
                reasonNode = node.path("permissionDecisionReason");
            }
            return new PreDecision(decision, reasonNode.isTextual() ? reasonNode.asText() : null, false);
        } catch (JsonProcessingException e) {
            return new PreDecision(null, null, true);
        }
    }

    private static String textReason(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    /** PreToolUse 的 stdout 裁定（denied 含 deny 与 legacy block）。 */
    private record PreDecision(String decision, String reason, boolean unparseable) {

        boolean denied() {
            return "deny".equals(decision) || "block".equals(decision);
        }

        /** Claude Code 三值语义的 ask：声明需审批而非自行裁决。 */
        boolean asksApproval() {
            return "ask".equals(decision);
        }
    }

    /**
     * stdin 载荷（字段名与 Claude Code 同名）：一期为 hook_event_name、tool_name、
     * tool_input、cwd，PostToolUse 增 tool_response（审计面需要看到工具结果）；
     * M19 增 presenter_id（发起呈现位标记，agent 循环执行时携带——载荷上下文透传
     * 的部分消化，session_id/transcript_path 仍缺席）。
     * session_id / transcript_path / tool_use_id 一期缺席——管线载荷无会话与调用
     * 标识，透传需执行入口携带上下文，与"tools 域零改动"冲突（backlog 记档）。
     */
    private JsonNode payload(String event, String toolName, JsonNode toolInput, Object toolResponse,
                             String presenterId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("hook_event_name", event);
        payload.put("tool_name", toolName);
        payload.set("tool_input", toolInput);
        payload.put("cwd", System.getProperty("user.dir"));
        if (presenterId != null) {
            payload.put("presenter_id", presenterId);
        }
        if (HooksConfig.EVENT_POST_TOOL_USE.equals(event)) {
            payload.set("tool_response", MAPPER.valueToTree(toolResponse));
        }
        return payload;
    }
}

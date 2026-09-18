package dev.duo.harness.hooks;

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
        List<HookRule> rules = hooks.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE);
        if (rules.isEmpty()) {
            log.info("hooks 配置为空或无 PreToolUse 规则（{}），插件空转", file);
            return null;
        }
        return mountPreToolUse(ctx, rules);
    }

    /**
     * PreToolUse → {@code tools/pre-execute}（ADR-0019 决策 3）：命中 matcher 的
     * 钩子按配置序逐个执行，exit 2 即否决（stderr 回给模型）；监听器不调 next
     * 即否决，内层监听器与工具本体不执行。
     */
    private Disposable mountPreToolUse(Context ctx, List<HookRule> rules) {
        return ctx.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    for (HookHandler handler : HookMatcher.matched(rules, exec.toolName())) {
                        HookRunner.Outcome outcome = HookRunner.run(handler, payload(exec));
                        if (!outcome.produced()) {
                            log.warn("PreToolUse 钩子未产生裁定，放行（fail-open）: {}",
                                    outcome.diagnosis(handler.command()));
                            continue;
                        }
                        if (outcome.exitCode() == 2) {
                            String stderr = outcome.stderr().strip();
                            String reason = "被 PreToolUse 钩子阻断"
                                    + (stderr.isEmpty() ? "" : ": " + stderr);
                            log.info("钩子阻断工具调用: 工具={} 钩子={} 理由={}",
                                    exec.toolName(), handler.command(), reason);
                            exec.deny(reason);
                            return Boolean.FALSE;
                        }
                        if (outcome.exitCode() != 0) {
                            log.warn("PreToolUse 钩子非零退出（非阻断，放行）: exit={} stderr={}",
                                    outcome.exitCode(), outcome.stderr().strip());
                        }
                        // exit 0 的 stdout JSON 裁定（permissionDecision）随工单 04 接入，本期放行
                    }
                    return next.invoke(exec);
                });
    }

    /**
     * stdin 载荷（一期最小面，字段名与 Claude Code 同名）：{@code tool_use_id} 管线
     * 暂不携带（执行入口签名变更超出一期"tools 域零改动"边界，backlog 记档），
     * session_id/transcript_path/cwd 随工单 04 补全。
     */
    private JsonNode payload(ToolExecution exec) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("hook_event_name", HooksConfig.EVENT_PRE_TOOL_USE);
        payload.put("tool_name", exec.toolName());
        payload.set("tool_input", exec.args());
        return payload;
    }
}

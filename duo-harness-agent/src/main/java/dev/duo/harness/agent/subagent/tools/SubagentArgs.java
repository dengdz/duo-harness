package dev.duo.harness.agent.subagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.subagent.SubagentManager;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;

import java.util.function.Supplier;

/**
 * 子代理工具族参数校验单点（C2 工单 10）：必填文本与会话归属校验——五件工具
 * 同批 copy-paste 起家（M15），四份 requireText 与两份 requireCurrentSession
 * 逐字重复，校验规则或错误文案调整漏改一处即行为/话术不一致；收敛后调用方
 * 传各自工具名，文案口径单点。
 */
final class SubagentArgs {

    private SubagentArgs() {
    }

    /** 必填文本参数：缺失/null/空白即点名异常。 */
    static String requireText(String toolName, JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new PluginException(toolName + " 缺少必填参数 " + field);
        }
        return node.asText();
    }

    /** 会话归属校验：长驻呈现位换绑后，旧会话的子代理对新会话不可达（治理边界）。 */
    static void requireCurrentSession(String toolName, SubagentManager manager,
                                      Supplier<Session> currentSession, String agentId) {
        Session session = currentSession.get();
        if (session == null) {
            throw new PluginException(toolName + ": 无可用父会话（装配不完整）");
        }
        var entry = manager.byId(agentId)
                .orElseThrow(() -> new PluginException("子代理不存在: " + agentId));
        if (!entry.parentSessionId().equals(session.id())) {
            throw new PluginException("子代理 " + agentId + " 属于会话 " + entry.parentSessionId()
                    + "，不属于当前会话 " + session.id());
        }
    }
}

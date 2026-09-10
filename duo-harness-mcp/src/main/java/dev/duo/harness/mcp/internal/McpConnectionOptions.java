package dev.duo.harness.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 归一化后的连接配置：JsonNode 原始配置 + 本模块缺省值（可选密集型配置，
 * 由本模块自绑定——内核的严格绑定服务于"必填契约"，此处缺省是文档化行为）。
 *
 * @param serverName          服务器名（全局唯一，注册标记与命名清洗的来源）
 * @param command             启动命令（stdio）
 * @param args                命令参数
 * @param env                 额外环境变量
 * @param failOnStartupError  首连失败是否导致插件启动失败（默认 false）
 * @param reconnectEnabled    是否自动重连（默认 true）
 * @param reconnectInitialMs  退避起始延迟（默认 500ms）
 * @param reconnectMaxMs      退避上限（默认 30_000ms）
 * @param reconnectMaxAttempts 重连预算（默认 10 次）
 * @param stableWindowMs      稳定窗口（默认 30_000ms，存活满此时长清零失败计数）
 * @param requestTimeoutMs    initialize/call 请求超时（默认 20_000ms）
 */
record McpConnectionOptions(
        String serverName,
        String command,
        List<String> args,
        Map<String, String> env,
        boolean failOnStartupError,
        boolean reconnectEnabled,
        long reconnectInitialMs,
        long reconnectMaxMs,
        int reconnectMaxAttempts,
        long stableWindowMs,
        long requestTimeoutMs) {

    /** serverName 允许的字符：字母/数字/下划线/连字符（命名清洗前的源头约束）。 */
    private static final Pattern SERVER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** 从 yml 的 config 节点归一化；缺省字段取文档化默认值。 */
    static McpConnectionOptions from(JsonNode config) {
        String serverName = text(config, "serverName");
        if (serverName == null || !SERVER_NAME_PATTERN.matcher(serverName).matches()) {
            throw new IllegalArgumentException(
                    "serverName 必填且仅允许字母/数字/下划线/连字符（1~32 位）: " + serverName);
        }
        String command = text(config, "command");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 必填（MCP 服务器启动命令）: " + serverName);
        }
        List<String> args = new ArrayList<>();
        JsonNode argsNode = config.get("args");
        if (argsNode != null && argsNode.isArray()) {
            argsNode.forEach(n -> args.add(n.asText()));
        }
        Map<String, String> env = Map.of();
        JsonNode envNode = config.get("env");
        if (envNode != null && envNode.isObject()) {
            var builder = new java.util.HashMap<String, String>();
            envNode.properties().forEach(e -> builder.put(e.getKey(), e.getValue().asText()));
            env = Map.copyOf(builder);
        }
        boolean failOnStartupError = config.path("failOnStartupError").asBoolean(false);
        boolean reconnectEnabled = config.path("reconnectEnabled").asBoolean(true);
        JsonNode reconnect = config.get("reconnect");
        long initial = reconnect == null ? 500 : reconnect.path("initialDelayMs").asLong(500);
        long max = reconnect == null ? 30_000 : reconnect.path("maxDelayMs").asLong(30_000);
        int attempts = reconnect == null ? 10 : reconnect.path("maxAttempts").asInt(10);
        long stableWindow = 30_000;
        long requestTimeoutMs = config.path("requestTimeoutMs").asLong(20_000);
        return new McpConnectionOptions(serverName, command, List.copyOf(args), env,
                failOnStartupError, reconnectEnabled, initial, max, attempts, stableWindow,
                requestTimeoutMs);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

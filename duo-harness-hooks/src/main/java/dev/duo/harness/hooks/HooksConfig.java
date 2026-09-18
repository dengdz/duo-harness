package dev.duo.harness.hooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * hooks 配置（{@code ~/.duo/hooks.json}，ADR-0019 决策 1）：顶层
 * {@code {"hooks": {事件名: [条目...]}}}，与 Claude Code/Codex 同形；条目兼容两种
 * 形态——Claude Code 的 matcher 组（{@code matcher} + {@code hooks} 处理器数组）与
 * Codex 的扁平处理器（直接 {@code command}）。未知顶层键宽容忽略（settings.json
 * 整文件粘贴即用）。
 *
 * <p>解析宽容度分两级：结构错误（非法 JSON、顶层/"hooks" 键/事件值形状不符）抛
 * {@link PluginException} 点名文件——插件 FAILED，不连坐 boot 树；条目级问题
 * （事件暂不支持、处理器非 command、缺 command、timeout 非法）跳过该条并记 WARN
 * （fail-open，ADR-0019 决策 2/3）。</p>
 */
public final class HooksConfig {

    private static final Logger log = LoggerFactory.getLogger(HooksConfig.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 钩子事件名（Claude Code 词汇）。 */
    public static final String EVENT_PRE_TOOL_USE = "PreToolUse";
    public static final String EVENT_POST_TOOL_USE = "PostToolUse";

    /** 本期受支持的事件集（其余事件跳过 + WARN 点名）。 */
    private static final Set<String> SUPPORTED_EVENTS = Set.of(EVENT_PRE_TOOL_USE, EVENT_POST_TOOL_USE);

    private final Map<String, List<HookRule>> rules;
    private final List<String> skippedEvents;

    private HooksConfig(Map<String, List<HookRule>> rules, List<String> skippedEvents) {
        this.rules = rules;
        this.skippedEvents = skippedEvents;
    }

    /** 从 DuoHome 下的 hooks.json 装载：文件缺失或空白 = 空配置（空转合法）。 */
    public static HooksConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new HooksConfig(Map.of(), List.of());
        }
        String text = Files.readString(file);
        if (text.isBlank()) {
            return new HooksConfig(Map.of(), List.of());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new PluginException("hooks 配置解析失败（非法 JSON）: " + file, e);
        }
        return parse(root, file);
    }

    /** 指定事件的 matcher 组（无规则返回空清单）。 */
    List<HookRule> rulesFor(String event) {
        return rules.getOrDefault(event, List.of());
    }

    /** 是否无任何规则（空转合法：装行而配置缺失/为空）。 */
    boolean isEmpty() {
        return rules.isEmpty();
    }

    /** 暂不支持而跳过的事件名（点名用）。 */
    List<String> skippedEvents() {
        return skippedEvents;
    }

    private static HooksConfig parse(JsonNode root, Path source) {
        if (!root.isObject()) {
            throw new PluginException("hooks 配置顶层须为 JSON 对象: " + source);
        }
        JsonNode hooks = root.get("hooks");
        if (hooks == null || hooks.isNull()) {
            return new HooksConfig(Map.of(), List.of());
        }
        if (!hooks.isObject()) {
            throw new PluginException("hooks 配置的 \"hooks\" 键须为对象: " + source);
        }
        Map<String, List<HookRule>> parsed = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = hooks.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String event = field.getKey();
            JsonNode groups = field.getValue();
            if (!SUPPORTED_EVENTS.contains(event)) {
                skipped.add(event);
                continue;
            }
            if (!groups.isArray()) {
                throw new PluginException(
                        "hooks 配置事件 \"" + event + "\" 的值须为数组: " + source);
            }
            List<HookRule> rules = new ArrayList<>();
            for (JsonNode group : groups) {
                HookRule rule = parseGroup(event, group, source);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            parsed.put(event, List.copyOf(rules));
        }
        return new HooksConfig(Map.copyOf(parsed), List.copyOf(skipped));
    }

    /** 条目级问题跳过（WARN 点名）；整组不可用返回 null。 */
    private static HookRule parseGroup(String event, JsonNode group, Path source) {
        if (group == null || !group.isObject()) {
            log.warn("hooks 配置 [{}] 含非对象条目，跳过: {}", event, source);
            return null;
        }
        JsonNode matcherNode = group.get("matcher");
        String matcher = matcherNode != null && matcherNode.isTextual()
                ? matcherNode.asText() : null;
        if (matcherNode != null && !matcherNode.isNull() && !matcherNode.isTextual()) {
            log.warn("hooks 配置 [{}] 的 matcher 须为字符串，按全匹配处理: {}", event, source);
        }
        // 正则形态的 matcher 做编译校验（全匹配与精确形态除外）：非法正则条目级跳过（WARN），
        // 不留"永不命中"的死规则
        if (HookMatcher.isRegexForm(matcher)) {
            try {
                java.util.regex.Pattern.compile(matcher);
            } catch (java.util.regex.PatternSyntaxException e) {
                log.warn("hooks 配置 [{}] matcher 正则非法（{}），跳过条目: {}",
                        event, e.getMessage(), source);
                return null;
            }
        }
        JsonNode handlers = group.get("hooks");
        if (handlers == null && group.has("command")) {
            // Codex 扁平形态：条目直接是 command 处理器（无 matcher 组、无 hooks 数组）
            HookHandler handler = parseHandler(event, group, source);
            return handler == null ? null : new HookRule(matcher, List.of(handler));
        }
        if (handlers == null || !handlers.isArray()) {
            log.warn("hooks 配置 [{}] 条目缺 \"hooks\" 处理器数组，跳过（matcher={}）: {}",
                    event, matcher, source);
            return null;
        }
        List<HookHandler> parsed = new ArrayList<>();
        for (JsonNode handler : handlers) {
            HookHandler parsedHandler = parseHandler(event, handler, source);
            if (parsedHandler != null) {
                parsed.add(parsedHandler);
            }
        }
        return new HookRule(matcher, List.copyOf(parsed));
    }

    private static HookHandler parseHandler(String event, JsonNode handler, Path source) {
        if (handler == null || !handler.isObject()) {
            log.warn("hooks 配置 [{}] 含非对象处理器，跳过: {}", event, source);
            return null;
        }
        String type = handler.path("type").asText("command");
        if (!"command".equals(type)) {
            log.warn("hooks 配置 [{}] 暂不支持处理器类型 \"{}\"（仅 command），跳过: {}",
                    event, type, source);
            return null;
        }
        String command = handler.path("command").asText("");
        if (command.isBlank()) {
            log.warn("hooks 配置 [{}] 处理器缺 command，跳过: {}", event, source);
            return null;
        }
        List<String> args = List.of();
        JsonNode argsNode = handler.get("args");
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isArray()) {
                log.warn("hooks 配置 [{}] 处理器 args 须为字符串数组，跳过: {}", event, source);
                return null;
            }
            List<String> parsed = new ArrayList<>();
            for (JsonNode arg : argsNode) {
                if (!arg.isTextual()) {
                    log.warn("hooks 配置 [{}] 处理器 args 含非字符串项，跳过: {}", event, source);
                    return null;
                }
                parsed.add(arg.asText());
            }
            args = List.copyOf(parsed);
        }
        Duration timeout = HookHandler.DEFAULT_TIMEOUT;
        JsonNode timeoutNode = handler.get("timeout");
        if (timeoutNode != null && !timeoutNode.isNull()) {
            if (!timeoutNode.isNumber() || timeoutNode.asDouble() <= 0) {
                log.warn("hooks 配置 [{}] 处理器 timeout 须为正数（秒），跳过: {}", event, source);
                return null;
            }
            timeout = Duration.ofMillis((long) (timeoutNode.asDouble() * 1000));
        }
        return new HookHandler(command, args, timeout);
    }
}

/**
 * matcher 组：同一 matcher 表达式下的处理器集合（配置形状载体，包内可见）。
 *
 * @param matcher  工具名匹配表达式（null/空白/`*` = 全匹配；语义见 HookMatcher）
 * @param handlers 该组下的 command 处理器
 */
record HookRule(String matcher, List<HookHandler> handlers) {
}

/**
 * command 处理器（包内可见）。
 *
 * @param command 命令（args 在场 = 与 args 一起 exec 直启；缺省 sh -c 执行）
 * @param args    exec 形态参数（空 = shell 形态）
 * @param timeout 执行上限（条目级 timeout 秒，缺省 600s 跟 Claude Code）
 */
record HookHandler(String command, List<String> args, Duration timeout) {

    /** 缺省执行上限（跟 Claude Code 的 command 钩子缺省，ADR-0019 决策 3）。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);
}

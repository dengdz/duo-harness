package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.tools.ApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 权限规则（M24，ADR-0026 决策一）：两级作用域的持久审批规则——项目级持久于项目根
 * {@code .duo/settings.json} 的 {@code permissions} 段（duo 首个项目级设置文件写入器，
 * 重写保留文件内其他键），会话级随会话事件流持久（{@code permission/rules} 事件由
 * 调用方落盘、呈现位经投影恢复，本类持 volatile 运行时态——与权限档同机制）。
 *
 * <p>匹配语义：规则先比工具名；bash 规则携 {@code Bash(prefix:*)} 记法的前缀，按
 * <b>词边界</b>匹配——命令与前缀全等，或前缀之后紧跟空白（{@code ls:*} 吞不掉
 * {@code lsof}）。无前缀即工具级规则（该工具任意参数）。裁决序：deny 恒优先
 * （查全部命令，与 guard 单调否决同构），allow 次之（只读判定器接入工单 03 后
 * 只查非只读命令，接入前对 bash 全量生效的过渡态记档于工单），未命中交内层链。
 * deny 规则仅手写文件，allow 规则产自审批卡「总是允许」（工单 02）。</p>
 *
 * <p>线程约定：规则表 volatile 整表替换（读者免锁）；项目文件写操作 synchronized
 * 串行。读取对环境宽容：文件/段缺席即空、坏 JSON 记 warn 按空处理不崩；写入失败
 * 抛 {@link UncheckedIOException} 由调用方降级提示（不静默）。</p>
 */
public final class PermissionRules {

    /**
     * 服务名（harness 保留裸名）。camelCase——视图接口按方法名解析服务（M23 记档：
     * 连字符服务名无法直取，experience 2026-09-22 条）。
     */
    public static final String SERVICE_NAME = "permissionRules";

    /** 规则裁决的审计署名（决策日志可指认"规则放行/规则拒绝"）。 */
    public static final String SOURCE = "permission-rules";

    private static final Logger log = LoggerFactory.getLogger(PermissionRules.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 规则决策：allow 产自审批卡「总是允许」，deny 仅手写且恒优先。 */
    public enum Decision { ALLOW, DENY }

    /** 规则作用域：项目级持久于 settings.json，会话级随会话事件流（ADR-0026 决策一）。 */
    public enum Scope {
        PROJECT, SESSION;

        /** 人读标签（清单与拒绝理由共用）。 */
        public String label() {
            return this == SESSION ? "会话级" : "项目级";
        }
    }

    /**
     * 单条规则：工具名 + 可选 bash 词边界前缀 + 决策 + 作用域。
     *
     * @param tool     工具名（如 {@code bash}）
     * @param prefix   bash 命令前缀（{@code null} = 工具级规则，任意参数）
     * @param decision 规则决策
     * @param scope    作用域（序列化不携带——由存储位置/事件通道隐含）
     */
    public record Rule(String tool, String prefix, Decision decision, Scope scope) {

        public Rule {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(scope, "scope");
        }

        /** 人读描述（清单与拒绝理由共用）：{@code deny bash sudo*（项目级）}。 */
        public String describe() {
            String match = prefix == null ? "(工具级)" : prefix + "*";
            return decision.name().toLowerCase() + " " + tool + " " + match + "（" + scope.label() + "）";
        }
    }

    /** 项目规则文件（构造定位）：{@code <项目根>/.duo/settings.json}。 */
    private final Path settingsFile;
    /** 项目级规则（load 读入，removeProjectRule 整表替换）。 */
    private volatile List<Rule> projectRules;
    /** 会话级规则（volatile 整表替换——事件投影恢复与命令面变更的运行时态）。 */
    private volatile List<Rule> sessionRules = List.of();

    /**
     * 高危根命令表（M24，ADR-0026 决策一：提权或不可逆破坏——「永不放行」）：
     * ① 审批卡不出现「总是允许」键（生成拦，{@link #alwaysAllowCandidate}）；
     * ② allow 规则（含手写）运行时也不生效，命中走 ask/档位（{@link #allowVerdict}
     * 截获）。判定与只读判定器同口径——只认裸命令名，路径前缀不识别。
     */
    public static final Set<String> HIGH_RISK_ROOT_COMMANDS = Set.of(
            "sudo", "su", "doas", "rm", "dd", "mkfs", "chmod", "chown", "shutdown", "reboot");

    private PermissionRules(Path settingsFile, List<Rule> projectRules) {
        this.settingsFile = settingsFile;
        this.projectRules = projectRules;
    }

    /**
     * 从项目根加载：读 {@code .duo/settings.json} 的 permissions 段（缺席即空规则、
     * 坏 JSON 记 warn 按空处理——读侧对环境宽容，不阻断装配）。
     */
    public static PermissionRules load(Path projectRoot) {
        Path file = projectRoot.resolve(".duo").resolve("settings.json");
        return new PermissionRules(file, readFileRules(file));
    }

    /** 从 cwd 向上定位项目根（.git 标记；未找到即起点）——与技能发现根同口径。 */
    public static Path findProjectRoot(Path start) {
        Path current = start.toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return start.toAbsolutePath();
    }

    /** bash 词边界前缀匹配：命令与前缀全等，或前缀之后紧跟空白。 */
    static boolean prefixMatches(String prefix, String command) {
        return command.equals(prefix)
                || command.length() > prefix.length()
                && command.startsWith(prefix)
                && Character.isWhitespace(command.charAt(prefix.length()));
    }

    /**
     * deny 段裁决（ADR-0026 决策一：查全部命令，恒优先）：命中返回拒绝（署名规则
     * 来源），未命中返回 empty。
     */
    public Optional<ApprovalDecision> denyVerdict(String toolName, JsonNode args) {
        for (Rule rule : allRules()) {
            if (rule.decision() == Decision.DENY
                    && matches(rule, toolName, ReadOnlyBashDetector.bashCommand(toolName, args))) {
                return Optional.of(ApprovalDecision.deny("权限规则拒绝: " + rule.describe(), SOURCE));
            }
        }
        return Optional.empty();
    }

    /**
     * allow 段裁决（ADR-0026 决策一：只查非只读命令——只读命令在裁决序上游已被
     * 只读层截获，本段天然只达非只读面；高危根命令运行时双拦——allow 规则对高危
     * 命令不生效，命中走 ask/档位并 debug 日志说明，M24 工单 02）：命中返回放行，
     * 未命中返回 empty。
     */
    public Optional<ApprovalDecision> allowVerdict(String toolName, JsonNode args) {
        String command = ReadOnlyBashDetector.bashCommand(toolName, args);
        if (command != null && isHighRiskRoot(command)) {
            log.debug("高危根命令不受 allow 规则放行（运行时拦）: {}", command);
            return Optional.empty();
        }
        for (Rule rule : allRules()) {
            if (rule.decision() == Decision.ALLOW
                    && matches(rule, toolName, command)) {
                return Optional.of(ApprovalDecision.allow(SOURCE));
            }
        }
        return Optional.empty();
    }

    /** 项目级规则（只读视图，下标即 P 编号序）。 */
    public List<Rule> projectRules() {
        return projectRules;
    }

    /** 会话级规则（只读视图，下标即 S 编号序）。 */
    public List<Rule> sessionRules() {
        return sessionRules;
    }

    /** 会话级规则整表替换（事件投影恢复与命令面变更的写入点）。 */
    public void setSessionRules(List<Rule> rules) {
        this.sessionRules = List.copyOf(rules);
    }

    /**
     * 删除项目级规则并重写 {@code .duo/settings.json}（保留文件内其他键；目录缺席
     * 自动创建）。写入失败抛 {@link UncheckedIOException}——调用方降级提示不静默。
     *
     * @param oneBasedIndex P 编号（1 起）
     */
    public synchronized void removeProjectRule(int oneBasedIndex) {
        List<Rule> updated = new ArrayList<>(projectRules);
        if (oneBasedIndex < 1 || oneBasedIndex > updated.size()) {
            throw new IllegalArgumentException("项目级规则编号不存在: P" + oneBasedIndex);
        }
        updated.remove(oneBasedIndex - 1);
        writeProjectRules(updated);
        projectRules = List.copyOf(updated);
    }

    /**
     * 删除会话级规则并返回更新后的全量列表（调用方落 {@code permission/rules} 事件
     * 快照——latest-wins 投影、resume 恢复）。
     *
     * @param oneBasedIndex S 编号（1 起）
     * @return 删除后的会话级规则全量快照
     */
    public synchronized List<Rule> removeSessionRule(int oneBasedIndex) {
        List<Rule> updated = new ArrayList<>(sessionRules);
        if (oneBasedIndex < 1 || oneBasedIndex > updated.size()) {
            throw new IllegalArgumentException("会话级规则编号不存在: S" + oneBasedIndex);
        }
        updated.remove(oneBasedIndex - 1);
        sessionRules = List.copyOf(updated);
        return updated;
    }

    /**
     * 追加一条项目级 allow 规则并重写 {@code .duo/settings.json}（审批卡「总是允许
     * （项目）」的生成入口）。写入失败抛 {@link UncheckedIOException}——调用方降级
     * 提示不静默。
     */
    public synchronized void addProjectRule(Rule rule) {
        List<Rule> updated = new ArrayList<>(projectRules);
        updated.add(rule);
        writeProjectRules(updated);
        projectRules = List.copyOf(updated);
    }

    /**
     * 追加一条会话级 allow 规则并返回更新后的全量快照 JSON（调用方落
     * {@code permission/rules} 事件——latest-wins 投影、resume 恢复）。
     */
    public synchronized String addSessionRule(Rule rule) {
        List<Rule> updated = new ArrayList<>(sessionRules);
        updated.add(rule);
        sessionRules = List.copyOf(updated);
        return rulesToJson(updated);
    }

    /**
     * 「总是允许」候选判定（审批卡是否出现 a/s 键）：候选 ≡ 可生成规则（allowRuleFor
     * 非 null）——非 bash 工具恒候选（生成工具级 allow）；bash 需有 {@code command}
     * 参数、首词为裸名且不在高危根命令表（{@link #HIGH_RISK_ROOT_COMMANDS} 生成拦）。
     */
    public static boolean alwaysAllowCandidate(String toolName, JsonNode args) {
        return allowRuleFor(toolName, args, Scope.PROJECT) != null;
    }

    /**
     * 由工具调用生成 allow 规则（审批卡「总是允许」的规则构造）：bash 取命令首词为
     * 前缀（词边界匹配，用户裁定粒度=首词，grill Q-02-1）；非 bash 为工具级规则
     * （无前缀）。高危/无法提取首词返回 null（调用方不生成）。
     */
    public static Rule allowRuleFor(String toolName, JsonNode args, Scope scope) {
        String command = ReadOnlyBashDetector.bashCommand(toolName, args);
        if (command == null) {
            return "bash".equals(toolName) ? null : new Rule(toolName, null, Decision.ALLOW, scope);
        }
        String root = firstWord(command);
        if (root.isEmpty() || root.indexOf('/') >= 0 || isHighRiskRoot(command)) {
            return null;
        }
        return new Rule(toolName, root, Decision.ALLOW, scope);
    }

    /** 命令首词是否高危根命令（裸名口径，路径前缀不识别）。 */
    private static boolean isHighRiskRoot(String command) {
        return HIGH_RISK_ROOT_COMMANDS.contains(firstWord(command.strip()));
    }

    /** 命令首词（空白切分；空串安全）。 */
    private static String firstWord(String command) {
        String trimmed = command.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private List<Rule> allRules() {
        List<Rule> merged = new ArrayList<>(projectRules);
        merged.addAll(sessionRules);
        return merged;
    }

    private static boolean matches(Rule rule, String toolName, String bashCommand) {
        if (!rule.tool().equals(toolName)) {
            return false;
        }
        return rule.prefix() == null || (bashCommand != null && prefixMatches(rule.prefix(), bashCommand));
    }

    /** 读项目规则文件：缺席/缺段/坏 JSON/条目非法一律降级（warn + 已解析部分/空）。 */
    private static List<Rule> readFileRules(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonNode rulesNode = JSON.readTree(Files.readString(file)).path("permissions").path("rules");
            if (!rulesNode.isArray()) {
                return List.of();
            }
            List<Rule> out = new ArrayList<>();
            for (JsonNode node : rulesNode) {
                Rule rule = parseRuleNode(node, Scope.PROJECT);
                if (rule != null) {
                    out.add(rule);
                } else {
                    log.warn("权限规则文件含非法条目，已跳过: {}", file);
                }
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            log.warn("权限规则文件读取失败，按无规则处理: {}（{}）", file, e.toString());
            return List.of();
        }
    }

    /** 重写项目规则文件：保留根对象其他键（settings 是项目级设置的共同载体）。 */
    private void writeProjectRules(List<Rule> rules) {
        try {
            ObjectNode root = JSON.createObjectNode();
            if (Files.exists(settingsFile)) {
                JsonNode existing = JSON.readTree(Files.readString(settingsFile));
                if (existing instanceof ObjectNode node) {
                    root = node;
                }
            }
            ObjectNode permissions;
            if (root.get("permissions") instanceof ObjectNode node) {
                permissions = node;
            } else {
                permissions = root.putObject("permissions");
            }
            ArrayNode arr = JSON.createArrayNode();
            for (Rule rule : rules) {
                arr.add(toJsonNode(rule));
            }
            permissions.set("rules", arr);
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("权限规则写入失败: " + settingsFile, e);
        }
    }

    /** 单条规则序列化（共享形态：tool 必备、prefix 可选、decision 小写）。 */
    private static ObjectNode toJsonNode(Rule rule) {
        ObjectNode node = JSON.createObjectNode();
        node.put("tool", rule.tool());
        if (rule.prefix() != null) {
            node.put("prefix", rule.prefix());
        }
        node.put("decision", rule.decision().name().toLowerCase());
        return node;
    }

    /** 会话级规则序列化（事件 text 载荷：变更后全量快照数组）。 */
    public static String rulesToJson(List<Rule> rules) {
        ArrayNode arr = JSON.createArrayNode();
        for (Rule rule : rules) {
            arr.add(toJsonNode(rule));
        }
        return arr.toString();
    }

    /**
     * 会话级规则反序列化（投影恢复用）：null/空串按空表、坏 JSON 记 warn 按空表、
     * 单条非法跳过——恢复是尽力而为的还账，不阻断呈现位启动。
     */
    public static List<Rule> parseRulesJson(String json, Scope scope) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = JSON.readTree(json);
            if (!arr.isArray()) {
                return List.of();
            }
            List<Rule> out = new ArrayList<>();
            for (JsonNode node : arr) {
                Rule rule = parseRuleNode(node, scope);
                if (rule != null) {
                    out.add(rule);
                }
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            log.warn("会话级权限规则 JSON 非法，按空规则处理（{}）", e.toString());
            return List.of();
        }
    }

    /** 单条规则解析（tool/decision 必备，prefix 可选，decision 限 allow/deny）；非法返回 null。 */
    private static Rule parseRuleNode(JsonNode node, Scope scope) {
        if (node == null || !node.hasNonNull("tool") || !node.hasNonNull("decision")) {
            return null;
        }
        Decision decision;
        try {
            decision = Decision.valueOf(node.get("decision").asText().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String prefix = node.hasNonNull("prefix") ? node.get("prefix").asText() : null;
        return new Rule(node.get("tool").asText(), prefix, decision, scope);
    }
}

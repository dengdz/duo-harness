package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * bash 只读命令三态判定器（M24 工单 03，ADR-0026 决策二）：true=只读可免审批，
 * false 一律按非只读（三态 true/false/undefined 聚合为两态的 fail-closed 出口——
 * 未知命令、不支持语法、解析失败同归 false）。
 *
 * <p>首期口径 = 38 个 allowAnyArg 只读命令（探测建议「约 30」的落地清单，逐条核对
 * 无写旗标）+ git 四件套（status/log/diff/show），先不碰 ZCode 式 safeFlags 值类型
 * 体系与多词前缀表（探测文档 docs/research/ZCode/交互与呈现/只读命令识别与规则.md
 * 的「对 duo 的启示」）。allowAnyArg 前提是命令自身无任何可写旗标（sort -o、
 * sed -i、find -delete 一类直接不入表）；重定向与管道等复合结构在语法层先行拒绝。</p>
 *
 * <p>复合/动态语法（管道、命令替换、变量展开、重定向、后台等）一律按 unsupported
 * → 非只读；命令词只认裸名——含路径前缀（/bin/ls、./cat）一律非只读（防同名本地
 * 二进制借 basename 命中白名单，疑罪从有）；git 四件套必叠信任分类——trust root 下
 * {@code .git} 不存在即非只读（防 {@code git -C} 逃逸：第二词非四件套本就被解析
 * 拒绝，此处兜运行时上下文缺失）。判定是判定时刻的同步文件探测，只保证判定时刻
 * 状态。</p>
 *
 * <p>线程约定：全只读不可变依赖（命令表 Set 常量 + trust root 路径）——可并发。</p>
 */
public final class ReadOnlyBashDetector {

    /** 复合/动态语法字符集：出现即非只读（管道、后台、顺序、重定向、命令替换、变量/算术展开、子 shell）。 */
    private static final String METACHARS = "|&;<>`$()\n\r";

    /**
     * allowAnyArg 只读命令表（38 条）：任意参数保持只读——每条人工核对过无写旗标、
     * 无执行面、无网络发送面（sort -o / sed -i / find -delete / awk system /
     * hostname 设名 / date -s 等一律不入表；env / command / sudo 等包装命令不剥，
     * 包装形态落在首词非表内，自然 fail-closed）。
     */
    private static final Set<String> ALLOW_ANY_ARG = Set.of(
            "basename", "cat", "cksum", "cmp", "cut", "df", "diff", "dirname", "du",
            "file", "grep", "head", "id", "ls", "lsblk", "lsof", "md5", "md5sum",
            "pgrep", "printenv", "printf", "ps", "pwd", "readlink", "realpath",
            "seq", "shasum", "sha256sum", "stat", "tail", "tty", "uname", "uniq",
            "uptime", "wc", "whereis", "which", "whoami");

    /** git 只读四件套（探测文档「git 子命令 core+history 组」的 duo 首期子集）。 */
    private static final Set<String> GIT_READ_SUBCOMMANDS = Set.of("status", "log", "diff", "show");

    /** 信任分类根（git 命令的运行时上下文锚点）：bash 执行 cwd = workspace root。 */
    private final Path trustRoot;

    public ReadOnlyBashDetector(Path trustRoot) {
        this.trustRoot = java.util.Objects.requireNonNull(trustRoot, "trustRoot");
    }

    /**
     * 三态聚合判定：true=只读（免审批放行）；false=非只读（含 unsupported，fail-closed）。
     *
     * @param command bash 命令全文（{@code command} 参数原文）
     */
    public boolean isReadOnlyBash(String command) {
        return verdict(command) == Verdict.READONLY;
    }

    /** 三态聚合两态判定（探测口径 true/false/undefined：write 与 unsupported 同归 NOT_READONLY）。 */
    public Verdict verdict(String command) {
        if (command == null || command.isBlank()) {
            return Verdict.NOT_READONLY;
        }
        String trimmed = command.strip();
        for (int i = 0; i < METACHARS.length(); i++) {
            if (trimmed.indexOf(METACHARS.charAt(i)) >= 0) {
                return Verdict.NOT_READONLY; // 复合/动态语法：不支持判定，fail-closed
            }
        }
        List<String> tokens = List.of(trimmed.split("\\s+"));
        String word = tokens.get(0);
        if (word.indexOf('/') >= 0) {
            return Verdict.NOT_READONLY; // 路径前缀命令：不认（防同名二进制借 basename 入白名单）
        }
        if (ALLOW_ANY_ARG.contains(word)) {
            return Verdict.READONLY;
        }
        if ("git".equals(word) && tokens.size() >= 2 && GIT_READ_SUBCOMMANDS.contains(tokens.get(1))) {
            return Files.exists(trustRoot.resolve(".git")) ? Verdict.READONLY : Verdict.NOT_READONLY;
        }
        return Verdict.NOT_READONLY; // 未知命令：undefined → 非只读
    }

    /** 判定结果。 */
    public enum Verdict { READONLY, NOT_READONLY }

    /**
     * 裁决序 ② 段的共享实现（{@link ReadOnlyBashPolicy} 与 {@link PermissionRulePolicy}
     * 共用）：bash 只读命中即返回放行决策（署名 read-only），否则 empty。
     */
    static java.util.Optional<ApprovalDecision> allowIfReadonly(
            ReadOnlyBashDetector detector, String toolName, JsonNode args) {
        String command = bashCommand(toolName, args);
        return command != null && detector.isReadOnlyBash(command)
                ? java.util.Optional.of(ApprovalDecision.allow(ReadOnlyBashPolicy.SOURCE))
                : java.util.Optional.empty();
    }

    /** 工具调用参数中的 bash 命令提取：非 bash 工具或无 command 参数返回 null（前缀/只读判定均不命中）。 */
    static String bashCommand(String toolName, JsonNode args) {
        if (!"bash".equals(toolName) || args == null || !args.hasNonNull("command")) {
            return null;
        }
        return args.get("command").asText("");
    }

    /** allowAnyArg 命令表只读视图（测试全表覆盖用，与判定同源免双处同步）。 */
    static Set<String> allowAnyArgCommands() {
        return ALLOW_ANY_ARG;
    }
}

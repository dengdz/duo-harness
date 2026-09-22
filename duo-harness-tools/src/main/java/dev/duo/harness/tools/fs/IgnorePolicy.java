package dev.duo.harness.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工作区忽略判定器（M23 工单 08，ADR-0025 决策三）：glob/grep/@file 三消费点
 * 共用的自研 .gitignore 判定——不捆绑 rg（红线 4）。
 *
 * <p>判定并集三源：逐级堆叠的 {@code .gitignore}（含根 {@code .git/info/exclude}，
 * workspace 外不回溯）∪ 硬编码产物目录 ∪ VCS 元数据目录——防仓库没写 .gitignore
 * 就全量扫。git 同语义：{@code !} 反选（last-match-wins，深层 .gitignore 覆盖
 * 浅层）、{@code **} 跨层、{@code *}/{@code ?}/{@code [...]} 字符类、尾 {@code /}
 * 目录限定、前导 {@code /} 锚定、{@code \\} 转义（含转义行尾空格）。祖先目录被忽略
 * 即整树忽略（与 git 剪枝同义——被忽略目录内的 {@code !} 反选救不回，且不再
 * 解析其下更深层规则）。全局 core.excludesFile 不做。</p>
 *
 * <p>已知姿态差异（记档 limitations）：未闭合 {@code [} 的行按坏行静默跳过
 * （git wildmatch 作字面 {@code [} 匹配）；路径段以 {@code /} 切分（Windows 目标
 * 未支持）。</p>
 *
 * <p>线程安全：规则按目录惰性解析并缓存（ConcurrentHashMap），缓存式判定
 * O(路径深度)，无需消费点预遍历。</p>
 */
public final class IgnorePolicy {

    /** 硬编码忽略段（并集源二、三）：VCS 元数据目录 + 常见产物目录。 */
    private static final Set<String> ALWAYS_IGNORED = Set.of(
            ".git", ".svn", ".hg", ".bzr", ".jj",
            "node_modules", "target", "dist", "build", "out", "coverage",
            ".next", ".nuxt", ".turbo", ".venv", "__pycache__",
            ".pytest_cache", ".mypy_cache", ".gradle", ".idea");

    private final Path root;
    private final ConcurrentHashMap<Path, List<Rule>> cache = new ConcurrentHashMap<>();

    private IgnorePolicy(Path workspaceRoot) {
        this.root = workspaceRoot.toAbsolutePath().normalize();
    }

    /** 以 workspace 根装载判定器（根下 .git/info/exclude 一并计入）。 */
    public static IgnorePolicy load(Path workspaceRoot) {
        return new IgnorePolicy(workspaceRoot);
    }

    /**
     * 判定一个路径是否被忽略。
     *
     * @param absolutePath workspace 内绝对路径（外部路径恒 false——不越界判定）
     * @param isDirectory  目标是否目录（目录限定规则只对目录命中）
     */
    public boolean ignored(Path absolutePath, boolean isDirectory) {
        Path abs = absolutePath.toAbsolutePath().normalize();
        Path rel;
        try {
            rel = root.relativize(abs);
        } catch (IllegalArgumentException e) {
            return false; // 不同根：不在 workspace 内，不判
        }
        if (rel.isAbsolute() || rel.startsWith("..")) {
            return false;
        }
        String[] parts = rel.toString().split("/");
        for (String part : parts) {
            if (ALWAYS_IGNORED.contains(part)) return true;
        }
        // 前缀目录剪枝模拟（git 同义）：任一祖先前缀目录被忽略 → 整树忽略，
        // 且不再深入（目录内反选救不回）
        for (int k = 0; k < parts.length - 1; k++) {
            if (prefixIgnored(parts, k)) return true;
        }
        Boolean result = null;
        Path dirAbs = root;
        for (int d = 0; d < parts.length; d++) {
            String subRel = String.join("/", java.util.Arrays.asList(parts).subList(d, parts.length));
            Boolean matched = lastMatch(rulesFor(dirAbs), subRel, isDirectory);
            if (matched != null) result = matched; // 越深的规则基越后赋值——近者赢
            dirAbs = dirAbs.resolve(parts[d]);
        }
        return Boolean.TRUE.equals(result);
    }

    /** 前缀目录 {@code parts[0..k]} 是否被忽略（所有祖先规则基逐级判定，近者赢）。 */
    private boolean prefixIgnored(String[] parts, int k) {
        Boolean result = null;
        Path dirAbs = root;
        for (int d = 0; d <= k; d++) {
            String subRel = String.join("/", java.util.Arrays.asList(parts).subList(d, k + 1));
            Boolean matched = lastMatch(rulesFor(dirAbs), subRel, true);
            if (matched != null) result = matched;
            dirAbs = dirAbs.resolve(parts[d]);
        }
        return Boolean.TRUE.equals(result);
    }

    /** 该目录生效的规则集（.gitignore + 根目录另加 .git/info/exclude），惰性解析缓存。 */
    private List<Rule> rulesFor(Path dir) {
        return cache.computeIfAbsent(dir, this::parseDirRules);
    }

    private List<Rule> parseDirRules(Path dir) {
        List<Rule> rules = new ArrayList<>();
        collectFile(dir.resolve(".gitignore"), rules);
        if (dir.equals(root)) {
            collectFile(root.resolve(".git").resolve("info").resolve("exclude"), rules);
        }
        return rules;
    }

    private void collectFile(Path file, List<Rule> out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return; // 规则文件缺席/不可读贡献 0 规则（.gitignore 本就可选）
        }
        for (String line : lines) {
            Rule rule = parseLine(line);
            if (rule != null) out.add(rule);
        }
    }

    /** 去行尾空白，{@code \\ } 转义的尾空格保留（git 同义——文件名可含尾空格）。 */
    private static String stripUnescapedTrailingBlank(String line) {
        String t = line;
        while (!t.isEmpty() && (t.charAt(t.length() - 1) == ' ' || t.charAt(t.length() - 1) == '\t')) {
            if (t.length() >= 2 && t.charAt(t.length() - 2) == '\\') break; // 转义的尾空白
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** 单行解析：坏行（空/注释/语法编译失败）返回 null 静默跳过——git 同语义。 */
    private static Rule parseLine(String line) {
        String t = stripUnescapedTrailingBlank(line);
        if (t.isEmpty() || t.startsWith("#")) return null;
        boolean negate = false;
        if (t.startsWith("\\!") || t.startsWith("\\#")) {
            t = t.substring(1); // 转义的字面 !/#
        } else if (t.startsWith("!")) {
            negate = true;
            t = t.substring(1);
        }
        boolean dirOnly = t.endsWith("/");
        if (dirOnly) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return null;
        boolean anchored = t.indexOf('/') >= 0;
        if (t.startsWith("/")) t = t.substring(1); // 前导 / 只表锚定，不进匹配
        if (t.isEmpty()) return null;
        String body = compile(t);
        if (body == null) return null;
        String regex = (anchored ? "^" : "^(?:[^/]+/)*") + body + "$";
        try {
            return new Rule(negate, dirOnly, Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * gitignore 模式片段 → 正则片段（不带锚定）：{@code **} 三形态（前导/中段/尾随）
     * 对齐 git 语义，{@code *} 不跨段、{@code ?} 单字符、{@code [...]} 字符类
     * （{@code [!...]} 取反），其余字符字面；无法编译（如未闭合字符类）返回 null。
     */
    private static String compile(String pattern) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        int len = pattern.length();
        while (i < len) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < len) {
                re.append(Pattern.quote(String.valueOf(pattern.charAt(i + 1))));
                i += 2;
            } else if (c == '*' && i + 1 < len && pattern.charAt(i + 1) == '*') {
                boolean leadingSlashBefore = i > 0 && pattern.charAt(i - 1) == '/';
                if (i == 0 && i + 2 < len && pattern.charAt(i + 2) == '/') {
                    re.append("(?:[^/]+/)*"); // 前导 **/ ：任意层含根下直接命中
                    i += 3;
                } else if (leadingSlashBefore && i + 2 < len && pattern.charAt(i + 2) == '/') {
                    re.append("(?:[^/]+/)*"); // 中段 /**/ ：零或多段
                    i += 3;
                } else if (leadingSlashBefore) {
                    re.append(".*"); // 尾 /** ：目录内一切
                    i += 2;
                } else {
                    re.append("[^/]*"); // 非法位置的 ** 退化按 * 处理
                    i += 2;
                }
            } else if (c == '*') {
                re.append("[^/]*");
                i += 1;
            } else if (c == '?') {
                re.append("[^/]");
                i += 1;
            } else if (c == '[') {
                int close = findClassClose(pattern, i);
                if (close < 0) return null; // 未闭合字符类：坏行
                String body = pattern.substring(i + 1, close);
                if (body.startsWith("!")) body = "^" + body.substring(1);
                // git 字符类是字面集合：转义 Java 交集保留字（&&）防语义漂移；
                // \\ 不翻倍——字符类内 \\] 等转义序列原样保留（Java 同语义）
                body = body.replace("&&", "\\&\\&");
                re.append('[').append(body).append(']');
                i = close + 1;
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
                i += 1;
            }
        }
        return re.toString();
    }

    /** 字符类闭合下标（{@code ]} 紧随 {@code [} 或 {@code [!} 时作字面），未闭合 -1。 */
    private static int findClassClose(String pattern, int open) {
        int i = open + 1;
        if (i < pattern.length() && (pattern.charAt(i) == '!' || pattern.charAt(i) == '^')) i++;
        if (i < pattern.length() && pattern.charAt(i) == ']') i++;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == ']') return i;
            if (pattern.charAt(i) == '\\' && i + 1 < pattern.length()) i++;
            i++;
        }
        return -1;
    }

    private static Boolean lastMatch(List<Rule> rules, String subRel, boolean isDir) {
        Boolean matched = null;
        for (Rule rule : rules) {
            if (rule.dirOnly() && !isDir) continue;
            if (rule.pattern().matcher(subRel).matches()) {
                matched = !rule.negate();
            }
        }
        return matched;
    }

    private record Rule(boolean negate, boolean dirOnly, Pattern pattern) { }
}

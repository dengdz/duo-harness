package dev.duo.harness.agent;

import dev.duo.harness.core.api.boot.DuoHome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AGENTS.md 注入（M7）：加载用户全局（~/.duo/AGENTS.md，可缺）与项目根
 * AGENTS.md（.git 定根），按"用户全局 → 项目根"拼接，总预算超限截断尾部。
 *
 * <p>注入产物为 prompt 注册表的一个片段（source = agents-md，排在用户配置
 * 片段之后）；无任何文件时返回 null（不注册空片段）。</p>
 */
public final class AgentsMd {

    /** 默认总预算：64KB（按字符计）。 */
    public static final int DEFAULT_BUDGET_CHARS = 64 * 1024;

    /** 片段来源标识（prompt 注册表 source）。 */
    public static final String FRAGMENT_SOURCE = "agents-md";

    private AgentsMd() {
    }

    /** 生产装载：用户全局取 DuoHome 根下 AGENTS.md，项目根从 cwd 向上以 .git 定位。 */
    public static String load(Path cwd, long budgetChars) {
        return load(cwd, DuoHome.resolve().root().resolve("AGENTS.md"), budgetChars);
    }

    /**
     * 装载：两个候选文件按序拼接（用户全局 → 项目根），总预算超限截断尾部。
     *
     * @param cwd        当前工作目录（向上找 .git 定项目根）
     * @param userGlobal 用户全局 AGENTS.md 路径（不存在则跳过）
     * @param budgetChars 总预算字符数（≥ 0）
     * @return 拼接后的注入文本；两个文件都不存在时为 null
     */
    public static String load(Path cwd, Path userGlobal, long budgetChars) {
        List<String> parts = new ArrayList<>();
        for (Path file : candidates(cwd, userGlobal)) {
            String content = readQuietly(file);
            if (content != null && !content.isBlank()) {
                parts.add(content.strip());
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        String joined = String.join("\n\n", parts);
        if (joined.length() <= budgetChars) {
            return joined;
        }
        return joined.substring(0, (int) budgetChars) + "\n\n（AGENTS.md 内容超预算，已截断）";
    }

    /** 候选文件（按拼接顺序）：用户全局 → 项目根。 */
    static List<Path> candidates(Path cwd, Path userGlobal) {
        List<Path> files = new ArrayList<>();
        if (userGlobal != null) {
            files.add(userGlobal);
        }
        files.add(SkillRegistry.findProjectRoot(cwd).resolve("AGENTS.md"));
        return List.copyOf(files);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

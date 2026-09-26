package dev.duo.harness.agent.prompt;

import dev.duo.harness.agent.skills.SkillRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AGENTS.md 注入（M7 最小链 → M25 工单 07 嵌套链 + meta_user 通道）：按"用户全局
 * （~/.duo/AGENTS.md）→ 项目根 → 项目根到 cwd 路径上的嵌套子目录链（浅到深、由泛
 * 到专）"顺序拼接，总预算超限截断尾部。每请求**现发现现读**（fs 增量发现的最强
 * 形态——会话中新建目录/文件下一轮即入链）。
 *
 * <p>注入通道为 meta_user（M25 工单 07）：内容包 {@code <agents-md>} 标签以 user
 * 角色置于请求消息序列最前（先于 {@code <memory>} 段），附"可能不全面相关、以
 * 用户当前指令为准"免责语（ZCode request-user-context 同构）；请求视图专用不落
 * 会话日志。子代理不注入（M7#4 口径不变）。
 */
public final class AgentsMd {

    /** 默认总预算：64KB（按字符计）。 */
    public static final int DEFAULT_BUDGET_CHARS = 64 * 1024;

    /** 链上每层目录的约定文件名。 */
    public static final String FILE_NAME = "AGENTS.md";

    private AgentsMd() {
    }

    /**
     * 装载：候选链按序拼接（用户全局 → 项目根 → 嵌套子目录链浅到深），总预算超限
     * 截断尾部。
     *
     * @param cwd        当前工作目录（向上找 .git 定项目根；其与项目根之间的每层
     *                   目录的 AGENTS.md 均入链）
     * @param userGlobal 用户全局 AGENTS.md 路径（不存在则跳过）
     * @param budgetChars 总预算字符数（≥ 0）
     * @return 拼接后的注入文本；链上无任何文件时为 null
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

    /**
     * 候选链（按拼接顺序）：用户全局 → 项目根 → 嵌套子目录链（项目根下一层到 cwd，
     * 浅到深——靠近根的是全局约定、靠近 cwd 的是局部细化，与"用户全局 → 项目根"
     * 的由泛到专一致）。每请求重扫（fs 增量发现：会话中目录/文件变更下一轮即见）。
     */
    private static List<Path> candidates(Path cwd, Path userGlobal) {
        List<Path> files = new ArrayList<>();
        if (userGlobal != null) {
            files.add(userGlobal);
        }
        Path root = SkillRegistry.findProjectRoot(cwd.toAbsolutePath()).toAbsolutePath().normalize();
        files.add(root.resolve(FILE_NAME));
        // 嵌套链：root 的子目录起逐层下探到 cwd（cwd 在 root 外或即 root 时空链）
        Path current = cwd.toAbsolutePath().normalize();
        if (current.startsWith(root) && !current.equals(root)) {
            Path relative = root.relativize(current);
            Path walk = root;
            for (int i = 0; i < relative.getNameCount(); i++) {
                walk = walk.resolve(relative.getName(i));
                files.add(walk.resolve(FILE_NAME));
            }
        }
        return List.copyOf(files);
    }

    /**
     * 读链上文件：缺席/不可读/IO 异常一律 null（该层跳过）。每请求现读形态下
     * IO 故障必须降级为缺层——链故障不破坏对话轮（与 MemoryBook.read 同款原则）。
     */
    private static String readQuietly(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * meta_user 注入段：链内容包 {@code <agents-md>} 标签 + 免责语；链上无文件
     * （{@link #load} 为 null）→ null，调用方零注入。现读现发现（每请求调用）。
     */
    public static String metaUserSection(Path cwd, Path userGlobal, long budgetChars) {
        String content = load(cwd, userGlobal, budgetChars);
        if (content == null) {
            return null;
        }
        return "<agents-md>\n（项目约定（AGENTS.md 链：用户全局 → 项目根 → 当前目录链）："
                + "跨层级累积、可能不全面相关，与用户当前指令冲突时以用户为准）\n"
                + content + "\n</agents-md>";
    }
}

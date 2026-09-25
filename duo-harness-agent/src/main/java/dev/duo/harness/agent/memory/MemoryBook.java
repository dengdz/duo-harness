package dev.duo.harness.agent.memory;

import dev.duo.harness.agent.skills.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 记忆本（"memory" 服务，M25 工单 02）：项目级 {@code .duo/MEMORY.md}（不入 git）
 * 作为跨会话记忆载体。读路径每次现读——用户手改或他会话写入下一轮请求即见；
 * 缺席、空白或读取异常一律静默降级为无记忆（未启用用户零感知，记忆故障永不
 * 破坏对话轮）。写入通道与写协议由 M25 工单 03 交付。
 *
 * <p>注入形态：内容包 {@code <memory>} 标签附时效免责语，以 user 角色置于请求
 * 消息序列最前（meta_user 通道，请求视图专用不落会话日志）；预算超限截尾并标注。</p>
 */
public final class MemoryBook {

    /** 服务名（camelCase 裸名，与 prompts/skills 同惯例；视图接口方法名逐字一致）。 */
    public static final String SERVICE_NAME = "memory";

    /** 默认注入预算：16KB（按字符计）。 */
    public static final int DEFAULT_BUDGET_CHARS = 16 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(MemoryBook.class);

    private final Path file;
    private final long budgetChars;

    /**
     * @param file        记忆本文件路径（缺席由 {@link #read()} 静默降级）
     * @param budgetChars 注入预算字符数（≥ 1）
     */
    public MemoryBook(Path file, long budgetChars) {
        this.file = Objects.requireNonNull(file, "file");
        if (budgetChars < 1) {
            throw new IllegalArgumentException("budgetChars 至少为 1: " + budgetChars);
        }
        this.budgetChars = budgetChars;
    }

    /** 生产装载：cwd 向上以 .git 定项目根，记忆本位于根下 {@code .duo/MEMORY.md}。 */
    public static MemoryBook load(Path cwd, long budgetChars) {
        return new MemoryBook(
                SkillRegistry.findProjectRoot(cwd).resolve(".duo").resolve("MEMORY.md"), budgetChars);
    }

    public Path file() {
        return file;
    }

    public long budgetChars() {
        return budgetChars;
    }

    /**
     * 现读记忆内容（每次调用重读文件，不缓存启动快照）：缺席 / 空白 → null；
     * IO 异常 → null 并记 warn（静默降级）；超预算截尾并标注截断让模型可知。
     */
    public String read() {
        String content;
        try {
            content = Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            LOG.warn("记忆本读取失败，按无记忆处理（{}）", file, e);
            return null;
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        String stripped = content.strip();
        if (stripped.length() <= budgetChars) {
            return stripped;
        }
        return stripped.substring(0, (int) budgetChars) + "\n（记忆本内容超预算，已截断）";
    }

    /**
     * meta_user 请求段：记忆内容包 {@code <memory>} 标签 + 时效免责语；无记忆
     * （{@link #read()} 为 null）→ null，调用方零注入。
     */
    public String metaUserSection() {
        String content = read();
        if (content == null) {
            return null;
        }
        return "<memory>\n（项目记忆本 .duo/MEMORY.md：跨会话持久的记忆，可能过时；"
                + "与用户当前指令冲突时以用户为准）\n" + content + "\n</memory>";
    }
}

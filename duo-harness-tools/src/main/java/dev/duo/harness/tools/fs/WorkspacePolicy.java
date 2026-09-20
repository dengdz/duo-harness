package dev.duo.harness.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * workspace 策略（ADR-0012）：agent 操作根目录绑定 + 三档权限模式 + 路径感知的
 * 审批判定（ALLOW = 免审批放行，ASK = 经审批 seam 由人作答）。
 *
 * <p>路径包含性判定是**可信代码内的策略检查，不是 OS 内核边界**——bash 子进程
 * 的写范围不受 workspace 约束（无 OS 级沙箱，文档明示）。判定两级：先规范化，
 * 现存路径再 realpath 解析符号链接，最后前缀+分隔符比较。</p>
 *
 * <p>线程约定：{@link #setMode} 可被 REPL 命令线程并发调用（volatile 发布），
 * 判定只读其余不可变字段——并发安全。</p>
 */
public final class WorkspacePolicy {

    public static final String SERVICE_NAME = "workspace";

    /** 权限三档（ADR-0012）。 */
    public enum Mode {
        READ_ONLY, WORKSPACE_WRITE, DANGER_FULL_ACCESS;

        /** 配置/命令字符串解析：未知值抛出并列出合法档位。 */
        public static Mode parse(String text) {
            for (Mode mode : values()) {
                if (mode.configName().equalsIgnoreCase(text.strip())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "未知档位: " + text + "（可选: read-only, workspace-write, danger-full-access）");
        }

        public String configName() {
            return switch (this) {
                case READ_ONLY -> "read-only";
                case WORKSPACE_WRITE -> "workspace-write";
                case DANGER_FULL_ACCESS -> "danger-full-access";
            };
        }
    }

    /** 审批判定：ALLOW = 免审批放行，ASK = 交审批 seam 由人作答。 */
    public enum Decision {
        ALLOW, ASK
    }

    private static final Set<String> READ_TOOLS = Set.of("read", "glob", "grep");
    private static final Set<String> WRITE_TOOLS = Set.of("write", "edit");
    /** 网络读（M20，ADR-0021 决策 8）：出网拉取类工具——独立于本地读集合分档。 */
    private static final Set<String> NETWORK_READ_TOOLS = Set.of("web_fetch", "web_search");

    private final Path root;
    /** 装配档（fs 插件 config 的 yml 缺省）：会话无切档记录时恢复逻辑的重置目标。 */
    private final Mode initialMode;
    private volatile Mode mode;

    public WorkspacePolicy(Path root, Mode mode) {
        this.root = realPathOrNormalize(root.toAbsolutePath().normalize());
        this.initialMode = mode;
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    /** 装配档（yml 缺省）：权限档持久化恢复时，无切档记录的会话重置回此档（ADR-0020 决策 10）。 */
    public Mode initialMode() {
        return initialMode;
    }

    /**
     * 运行时切档（REPL /permission 命令）。持久化由调用方落 {@code permission/mode}
     * 会话事件（M19，ADR-0020 决策 10）——本类保持中立不感知会话；重启后由呈现位
     * 按会话投影恢复（档位跟对话走），不在此处做任何文件持久化。
     */
    public void setMode(Mode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
    }

    public Path root() {
        return root;
    }

    /**
     * 路径包含性判定：目标规范化（现存路径 realpath 解析符号链接）后必须落在
     * workspace 根之内。相对路径按根解析；`..` 穿越在规范化后自然落到根外。
     */
    public boolean contains(Path target) {
        // 统一符号链接基准：现存文件 toRealPath（解析符号链接），不存在的路径
        // realpath 最近存在祖先 + 拼接文件名——避免 macOS /var → /private/var 基准漂移
        Path resolved = target.isAbsolute() ? target.normalize() : root.resolve(target).normalize();
        // 现存文件（含符号链接）→ realpath 检测越界
        if (Files.exists(resolved)) {
            try {
                return resolved.toRealPath().startsWith(root);
            } catch (IOException ignored) { }
            return true; // realpath 失败保守放行（不误判已有文件）
        }
        // 不存在 → 逐级向上找最近存在的祖先，realpath 后拼接剩余文件名
        Path suffix = resolved.getFileName();
        Path parent = resolved.getParent();
        while (parent != null) {
            try {
                Path realParent = parent.toRealPath();
                Path full = realParent.resolve(suffix);
                return full.startsWith(root);
            } catch (IOException ignored) {
                suffix = Path.of(parent.getFileName().toString()).resolve(suffix);
                parent = parent.getParent();
            }
        }
        return true; // 整条链都不存在：保守放行
    }

    /**
     * 审批判定（ADR-0012 决策 4 + ADR-0021 决策 8）：danger 档全放行；本地读类
     * 放行；网络读（web_fetch/web_search）read-only 档 ask（"只读"语义不出网边界）、
     * workspace-write 档放行；写类按目标路径包含性分档；bash 非 danger 档一律 ask；
     * 未知工具保守 ask（沿 M6 交互全问现状）。解析失败（无 path 参数）保守 ask。
     */
    public Decision decide(String toolName, Path targetPath) {
        if (mode == Mode.DANGER_FULL_ACCESS) {
            return Decision.ALLOW;
        }
        if (READ_TOOLS.contains(toolName)) {
            return Decision.ALLOW;
        }
        if (NETWORK_READ_TOOLS.contains(toolName)) {
            return mode == Mode.READ_ONLY ? Decision.ASK : Decision.ALLOW;
        }
        if (WRITE_TOOLS.contains(toolName)) {
            // read-only 档：写一律 ask（不区分内外——保守安全）
            // workspace-write 档：包含性判定（内放行/外 ask）
            if (mode == Mode.READ_ONLY) {
                return Decision.ASK;
            }
            if (targetPath == null) {
                return Decision.ASK; // path 解析失败保守 ask
            }
            return contains(targetPath) ? Decision.ALLOW : Decision.ASK;
        }
        return Decision.ASK;
    }

    /**
     * 解析工具参数中的目标路径：相对路径按 workspace 根解析；缺失/空白返回
     * null（调用方保守 ask）。越界路径原样返回——由 contains 判定拒绝。
     */
    public Path resolveInWorkspaceOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path target = Path.of(raw);
        return target.isAbsolute() ? target.normalize() : root.resolve(target).normalize();
    }

    /** realpath 或退化 normalize（macOS /var → /private/var 等符号链接路径必须在同一基准上比较）。 */
    private static Path realPathOrNormalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path;
        }
    }
}

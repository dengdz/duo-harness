package dev.duo.harness.core.api.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * duo-harness 用户级默认目录（{@code ~/.duo}）：会话、配置等运行时数据统一收在其下，
 * 保证密钥与个人配置永不入仓库（红线 2）。环境变量 {@code DUO_HOME} 可整体重定向
 * （测试与多实例隔离用）。
 *
 * <p>目录懒创建：{@link #resolve(String)} 首次解析子目录时按需创建，首次运行零预置。
 */
public final class DuoHome {

    /** home 覆盖的环境变量名。 */
    public static final String ENV_OVERRIDE = "DUO_HOME";

    private final Path root;

    private DuoHome(Path root) {
        this.root = root;
    }

    /**
     * 解析 duo home：{@code DUO_HOME} 环境变量优先，缺省 {@code ~/.duo}。
     *
     * @param envHome 环境变量 {@code DUO_HOME} 的值（可为 null/空白，测试注入用）
     * @return duo home 根目录（绝对路径）
     */
    public static DuoHome resolve(String envHome) {
        String home = envHome != null && !envHome.isBlank()
                ? envHome
                : Path.of(System.getProperty("user.home"), ".duo").toString();
        return new DuoHome(Path.of(home).toAbsolutePath().normalize());
    }

    /** 从当前环境解析（读 {@code DUO_HOME}）。 */
    public static DuoHome resolve() {
        return resolve(System.getenv(ENV_OVERRIDE));
    }

    /** duo home 根目录。 */
    public Path root() {
        return root;
    }

    /**
     * 解析 home 下的子目录并按需创建（首次运行零预置）。
     *
     * @param name 子目录名（如 {@code sessions}）
     * @return 已确保存在的子目录
     * @throws IllegalStateException 子目录创建失败（磁盘/权限）
     */
    public Path resolveDir(String name) {
        try {
            Path dir = root.resolve(name);
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 duo home 子目录: " + name + "（root=" + root + "）", e);
        }
    }
}

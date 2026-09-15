package dev.duo.harness.example.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试夹具：演示 yml 的随机端口副本（BUG-20260915-02）——boot 全量演示装配的
 * 测试（工具目录对账、演示装载冒烟）改用 port=0，避免与本机在跑的 demo 实例
 * 抢 18080 而失败；其余内容与原文件逐字一致（单一事实来源仍是 agent-demo.yml）。
 */
public final class DemoYml {

    private DemoYml() {
    }

    /**
     * 生成临时副本：{@code port: 18080} 行替换为 {@code port: 0}（随机端口）。
     *
     * @param tempDir  写入目录（JUnit @TempDir）
     * @param resource classpath 资源路径（如 {@code /agent-demo.yml}）
     * @throws IllegalStateException 原文件未见固定端口行——替换逻辑需随配置同步
     */
    public static Path ephemeralPortCopy(Path tempDir, String resource) throws IOException {
        String raw;
        try {
            raw = Files.readString(Path.of(DemoYml.class.getResource(resource).toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IOException("资源路径不可解析: " + resource, e);
        }
        String replaced = raw.replace("port: 18080", "port: 0");
        if (replaced.equals(raw)) {
            throw new IllegalStateException(resource + " 中未见 'port: 18080' 行——"
                    + "演示端口若有变更，本夹具的替换需同步");
        }
        Path target = tempDir.resolve("demo-ephemeral-port.yml");
        Files.writeString(target, replaced);
        return target;
    }
}
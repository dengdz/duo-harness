package dev.duo.harness.example.subagent;

import java.nio.file.Files;
import java.nio.file.Path;

/** demo 间共用的小工具（C2 工单 18：消 deleteRecursive 逐字双份——两个 Main 各留一份的迁移残留）。 */
final class DemoCleanup {

    private DemoCleanup() {
    }

    /** 递归删目录（演示目录每次重置）。 */
    static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // 演示目录清理，失败不阻塞
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}

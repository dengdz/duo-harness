package dev.duo.harness.tools.fs;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读前写闸门登记（ADR-0012 决策 5）：read 成功登记目标路径，write 覆盖已有文件
 * 与 edit 前校验——未读即拒。
 *
 * <p>作用域为 {@link FsToolsPlugin} 装配实例："会话已读"以插件生命周期近似，
 * 插件 dispose 后实例不可达、登记随之回收，不跨装配残留。同一进程多装配
 * （Web 多会话）时登记按装配共享，是已知取舍。</p>
 */
final class ReadGate {

    private final Set<String> readFiles = ConcurrentHashMap.newKeySet();

    /** read 工具读取成功后登记（路径规范化为绝对键）。 */
    void markRead(Path path) {
        readFiles.add(key(path));
    }

    /** 目标路径是否已在本装配内被 read 过。 */
    boolean hasRead(Path path) {
        return readFiles.contains(key(path));
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}

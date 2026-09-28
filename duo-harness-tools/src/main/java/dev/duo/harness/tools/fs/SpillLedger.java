package dev.duo.harness.tools.fs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * spill 文件登记簿（装配级实例，C2 工单 01）：统一分配本装配的 bash / 后台任务
 * spill 路径并登记在册，插件停止时只回收自有文件——同进程多装配（Web 多会话 /
 * 子代理树高频启停是一等场景）与跨进程并存下，任一装配停止不再误删他方活 spill
 * （原实现「停止删光共享目录」为 M23 工单 05 的过度清理，破坏运行中会话的
 * 「read 回读全文」承诺）。
 *
 * <p>「进程退出无残渣」承诺由<b>本装配无残渣</b>承载；崩溃残留不主动清扫——
 * 误删活文件（回读承诺破坏、数据丢失）的代价远高于残留临时文件（tmp 区占磁盘）
 * 的代价。文件名带进程号：跨进程不碰撞；同进程多装配靠登记回收，不靠文件名区分。</p>
 */
final class SpillLedger {

    private static final Logger log = LoggerFactory.getLogger(SpillLedger.class);
    /** 路径序号（进程内单调；跨进程由文件名进程号区分）。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private final List<java.nio.file.Path> owned = new CopyOnWriteArrayList<>();
    private final java.nio.file.Path dir =
            dev.duo.harness.core.api.boot.DuoHome.resolve().resolveDir("tmp/bash-spill");

    /**
     * 分配 spill 路径并登记（文件可能懒创建——实际落盘与否由
     * {@link FsBashTool.StreamCapture} 决定，回收走 deleteIfExists 静默）。
     */
    java.nio.file.Path next(String label) {
        java.nio.file.Path path = dir.resolve(
                "bash-" + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet()
                        + "-" + label + ".txt");
        owned.add(path);
        return path;
    }

    /** 回收本装配在册 spill 文件（插件停止路径）：删除失败留日志不静默、不阻断拆树。 */
    void dispose() {
        for (java.nio.file.Path path : owned) {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (java.io.IOException e) {
                log.warn("spill 文件清理失败（残留临时区，不影响拆树）: {} — {}", path, e.toString());
            }
        }
        owned.clear();
    }
}

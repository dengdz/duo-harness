package dev.duo.harness.agent.fileref;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @file 补全服务（M21 工单 07，ADR-0022 决策 7）：工作区路径索引的带缓存门面。
 *
 * <p>索引懒构建（首次补全才扫工作区）。陈旧收敛两条路：① tool/result 后
 * {@link #markStale()} 置脏 + 单线程后台重建（模型经 bash/write 改文件树）；
 * ② **未命中且缓存已旧时同步重建一次再重试**（带防抖）——覆盖带外改文件
 * （用户在 IDE/终端里动文件树，没有事件）。补全请求只读缓存快照，同步重建
 * 只发生在"搜不到"时一次，交互不卡。</p>
 *
 * <p>线程约定：缓存读取任意线程；重建串行（同步走调用线程、后台走单线程池）；
 * 无清理需求（进程级生命周期随插件作用域）。</p>
 */
public final class FileReferenceService {

    /** 服务名（harness 保留裸名；视图接口方法名即服务名）。 */
    public static final String SERVICE_NAME = "fileRefs";

    /** 索引条目上限（工作区文件树爆炸的护栏；截断后新文件不再进入候选）。 */
    static final int DEFAULT_MAX_ENTRIES = 5_000;

    /**
     * 未命中同步重建的最小间隔（防抖）：带外改文件（用户在 IDE/终端里动文件树，
     * 没有 tool/result 事件）时，搜不到就当场重建一次——间隔内不重复重建。
     */
    static final long MIN_REBUILD_INTERVAL_MS = 2_000;

    private final FileReferenceIndex index;
    private final Object lock = new Object();
    private volatile List<FileReferenceIndex.Candidate> cache;
    private volatile long cacheBuiltAtMs;
    private final AtomicBoolean rebuildQueued = new AtomicBoolean(false);
    private final ExecutorService rebuildPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fileref-rebuild");
        t.setDaemon(true);
        return t;
    });

    public FileReferenceService(java.nio.file.Path workspaceRoot) {
        this(workspaceRoot, DEFAULT_MAX_ENTRIES);
    }

    public FileReferenceService(java.nio.file.Path workspaceRoot, int maxEntries) {
        this.index = new FileReferenceIndex(workspaceRoot, maxEntries,
                dev.duo.harness.tools.fs.IgnorePolicy.load(workspaceRoot));
    }

    /** 单条补全候选（路径相对 workspace 根 + 是否目录）。 */
    public record Completion(String path, boolean directory) { }

    /**
     * 按 token 过滤候选（补全端点数据源）：空 token 返回顶层前缀段（自然序前
     * {@code limit} 条）；非空先全路径前缀匹配（大小写不敏感）、不足再按路径段
     * 前缀补齐。**未命中且缓存已旧（≥ 防抖间隔）时同步重建一次再重试**——覆盖
     * 带外改文件（IDE/终端里建删文件，无 tool/result 事件）的可见性。
     */
    public List<Completion> complete(String token, int limit) {
        List<Completion> out = filter(snapshot(), token, limit);
        if (out.isEmpty() && staleEnough()) {
            rebuildNow();
            out = filter(snapshot(), token, limit);
        }
        return out;
    }

    private boolean staleEnough() {
        return System.currentTimeMillis() - cacheBuiltAtMs >= MIN_REBUILD_INTERVAL_MS;
    }

    private void rebuildNow() {
        synchronized (lock) {
            cache = index.buildIndex();
            cacheBuiltAtMs = System.currentTimeMillis();
        }
    }

    private List<Completion> filter(List<FileReferenceIndex.Candidate> snapshot,
                                    String token, int limit) {
        LinkedHashSet<Completion> out = new LinkedHashSet<>();
        if (token == null || token.isBlank()) {
            for (FileReferenceIndex.Candidate c : snapshot) {
                if (out.size() >= limit) break;
                out.add(new Completion(c.path(), c.directory()));
            }
            return List.copyOf(out);
        }
        String q = token.toLowerCase(Locale.ROOT);
        for (FileReferenceIndex.Candidate c : snapshot) {
            if (out.size() >= limit) break;
            if (c.path().toLowerCase(Locale.ROOT).startsWith(q)) {
                out.add(new Completion(c.path(), c.directory()));
            }
        }
        if (out.size() < limit) {
            for (FileReferenceIndex.Candidate c : snapshot) {
                if (out.size() >= limit) break;
                if (segmentStartsWith(c.path(), q)) {
                    out.add(new Completion(c.path(), c.directory()));
                }
            }
        }
        return List.copyOf(out);
    }

    /** 任一路径段（含末段）以 q 开头即命中——"main" 找到 src/Main.java 这类。 */
    private static boolean segmentStartsWith(String path, String q) {
        for (String segment : path.split("/")) {
            if (segment.toLowerCase(Locale.ROOT).startsWith(q)) {
                return true;
            }
        }
        return false;
    }

    /** tool/result 后调用：置脏并在后台重建（已在队列则不重复排队）。 */
    public void markStale() {
        if (cache == null) {
            return; // 懒构建语义：从未建过就没有"陈旧"可言，首次补全自然全量扫
        }
        if (!rebuildQueued.compareAndSet(false, true)) {
            return;
        }
        rebuildPool.submit(() -> {
            try {
                rebuildNow();
            } finally {
                rebuildQueued.set(false);
            }
        });
    }

    private List<FileReferenceIndex.Candidate> snapshot() {
        List<FileReferenceIndex.Candidate> current = cache;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (cache == null) {
                cache = index.buildIndex();
                cacheBuiltAtMs = System.currentTimeMillis();
            }
            return cache;
        }
    }

    /** 当前缓存规模（测试与诊断用；未建过为 0）。 */
    public int cachedSize() {
        List<FileReferenceIndex.Candidate> current = cache;
        return current == null ? 0 : current.size();
    }

    /** 等待后台重建完成（测试用：轮询至队列清空）。 */
    void awaitRebuildForTests() throws InterruptedException {
        for (int i = 0; i < 100 && (rebuildQueued.get() || cache == null); i++) {
            Thread.sleep(20);
        }
        Thread.sleep(50); // 队列标志清零后最后一次写入的可见性余量
    }

    /** 供测试注入完整候选表视图。 */
    List<FileReferenceIndex.Candidate> cacheForTests() {
        return cache;
    }

    /** 测试辅助：停掉后台池（避免 JVM 挂起）。 */
    void shutdownForTests() {
        rebuildPool.shutdownNow();
    }
}

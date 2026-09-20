package dev.duo.harness.agent.fileref;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 补全服务（M21 工单 07）：懒构建、前缀/路径段过滤、tool/result 后台重建。 */
class FileReferenceServiceTest {

    @TempDir
    Path dir;

    private FileReferenceService service;

    @BeforeEach
    void mount() {
        service = new FileReferenceService(dir);
    }

    @AfterEach
    void shutdown() {
        service.shutdownForTests();
    }

    private void writeFixture() throws Exception {
        Files.writeString(dir.resolve("README.md"), "x");
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/App.java"), "x");
        Files.writeString(dir.resolve("src/Main.java"), "x");
    }

    @Test
    void emptyTokenReturnsTopEntries() throws Exception {
        writeFixture();
        List<FileReferenceService.Completion> out = service.complete("", 10);
        assertTrue(out.stream().anyMatch(c -> c.path().equals("README.md")));
        assertTrue(out.stream().anyMatch(c -> c.path().equals("src") && c.directory()));
    }

    @Test
    void fullPathPrefixMatchCaseInsensitive() throws Exception {
        writeFixture();
        List<FileReferenceService.Completion> out = service.complete("src/ma", 10);
        assertTrue(out.stream().anyMatch(c -> c.path().equals("src/main")));
        assertTrue(out.stream().noneMatch(c -> c.path().equals("README.md")));
    }

    @Test
    void segmentPrefixFallsBack() throws Exception {
        writeFixture();
        List<FileReferenceService.Completion> out = service.complete("main", 10);
        assertTrue(out.stream().anyMatch(c -> c.path().equals("src/main"))); // 路径段前缀
        assertTrue(out.stream().anyMatch(c -> c.path().equals("src/Main.java")));
    }

    @Test
    void limitCapsResults() throws Exception {
        for (int i = 0; i < 8; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "x");
        }
        assertEquals(3, service.complete("f", 3).size());
    }

    @Test
    void lazyBuildThenMarkStaleRebuildsInBackground() throws Exception {
        writeFixture();
        assertEquals(0, service.cachedSize()); // 懒构建：构造后未扫
        assertEquals(5, service.complete("", 100).size()); // 首次补全触发构建（README + src + src/main + 2 文件）
        assertEquals(5, service.cachedSize());

        // tool/result 之后新建的文件：先陈旧（缓存仍旧），后台重建后可见
        Files.writeString(dir.resolve("generated.txt"), "x");
        assertTrue(service.complete("generated", 10).isEmpty());
        service.markStale();
        service.awaitRebuildForTests();
        assertEquals(1, service.complete("generated", 10).size());
    }

    @Test
    void markStaleBeforeFirstBuildIsNoop() {
        service.markStale(); // 从未建过无"陈旧"可言——不炸、不提前触发构建
        assertEquals(0, service.cachedSize());
    }

    @Test
    void outOfBandCreationFoundViaMissRebuild() throws Exception {
        writeFixture();
        service.complete("", 100); // 建索引（模拟先玩了场景 1）
        // 带外建文件（IDE/终端里 touch，无 tool/result 事件）
        Files.writeString(dir.resolve("my report draft.txt"), "x");
        assertTrue(service.complete("my", 10).isEmpty()
                || service.complete("my", 10).stream().noneMatch(c -> c.path().contains("report")));
        // 未命中 + 缓存已旧 → 同步重建后立即可见（验收反馈：带外建文件搜不到）
        Thread.sleep(2100); // 越过 2s 防抖
        List<FileReferenceService.Completion> out = service.complete("my report", 10);
        assertTrue(out.stream().anyMatch(c -> c.path().equals("my report draft.txt")),
                "带外新建文件应被未命中重建捞回来: " + out);
    }
}

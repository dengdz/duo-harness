package dev.duo.harness.tools.fs;

import dev.duo.harness.attachment.AdmittedImage;
import dev.duo.harness.attachment.AttachmentConfig;
import dev.duo.harness.attachment.AttachmentStore;
import dev.duo.harness.tools.ToolExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read_image 直调测试（工单 03）：视觉闸门零 I/O、魔数嗅探（无扩展名）、先持久化
 * 再返回（store 可查）、非图片拒绝、路径解析。测试图代码生成，零真实资源。
 */
class ReadImageToolTest {

    @TempDir
    Path tempDir;

    private WorkspacePolicy policy;
    private AttachmentStore store;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ReadImageToolTest —— read_image：闸门/嗅探/先持久化/拒绝路径 ===");
    }

    private void setUp() throws IOException {
        Files.createDirectories(tempDir.resolve("ws"));
        policy = new WorkspacePolicy(tempDir.resolve("ws"), WorkspacePolicy.Mode.WORKSPACE_WRITE);
        store = new AttachmentStore(tempDir.resolve("attachments/v1"), AttachmentConfig.defaults());
    }

    private Path writePng(String name) throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.PINK, 40, 30, Color.BLACK));
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        Path file = tempDir.resolve("ws").resolve(name);
        Files.write(file, out.toByteArray());
        return file;
    }

    private ReadImageTool tool(boolean vision) throws IOException {
        setUp();
        return new ReadImageTool(policy, store, () -> vision);
    }

    private static String run(ReadImageTool tool, String json) throws Exception {
        return (String) tool.execute(new ToolExecution("read_image",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)));
    }

    @Test
    void 视觉闸门关闭时零IO拒绝() throws Exception {
        ReadImageTool tool = tool(false);
        // 路径根本不存在：若闸门放行会报"文件不存在"，闸门生效则报视觉不支持——证明未做路径探测
        String out = run(tool, "{\"file_path\":\"不存在的图.png\"}");
        assertTrue(out.startsWith("[read_image 错误]"), "应结构化拒绝: " + out);
        assertTrue(out.contains("不支持图片"), "应点名视觉闸门: " + out);
        assertFalse(out.contains("文件不存在"), "闸门须先于路径解析（零 I/O）: " + out);
    }

    @Test
    void 正常读图先持久化再返回() throws Exception {
        ReadImageTool tool = tool(true);
        Path png = writePng("chart.png");
        String out = run(tool, "{\"file_path\":\"" + png.getFileName() + "\"}");

        assertTrue(out.contains("(image/png, 40x30"), "应带类型与尺寸: " + out);
        String id = java.util.Arrays.stream(out.split("\n"))
                .filter(l -> l.startsWith("附件已入库: "))
                .map(l -> l.substring("附件已入库: ".length()).split("——")[0].strip())
                .findFirst().orElse("");
        assertFalse(id.isBlank(), "应返回附件引用: " + out);
        assertTrue(store.exists(id), "返回前已持久化（store 可查）");
        assertTrue(Files.exists(store.objectPath(id)));
    }

    @Test
    void 无扩展名按魔数嗅探() throws Exception {
        ReadImageTool tool = tool(true);
        Path png = writePng("chart.png");
        Path noExt = tempDir.resolve("ws").resolve("screenshot");
        Files.copy(png, noExt);
        String out = run(tool, "{\"file_path\":\"screenshot\"}");
        assertTrue(out.contains("(image/png"), "无扩展名应魔数嗅探为 png: " + out);
    }

    @Test
    void 非图片文件拒绝() throws Exception {
        ReadImageTool tool = tool(true);
        Path text = tempDir.resolve("ws").resolve("note.txt");
        Files.writeString(text, "只是文本");
        String out = run(tool, "{\"file_path\":\"note.txt\"}");
        assertTrue(out.startsWith("[read_image 错误]"));
        assertTrue(out.contains("不支持的图片格式"), "非图片应点名: " + out);
    }

    @Test
    void 文件不存在与缺参拒绝() throws Exception {
        ReadImageTool tool = tool(true);
        String missing = run(tool, "{\"file_path\":\"ghost.png\"}");
        assertTrue(missing.contains("文件不存在"));
        String noArg = run(tool, "{}");
        assertTrue(noArg.contains("file_path 不能为空"));
    }

    @Test
    void 三档判定全放行() throws Exception {
        // read_image 归本地读类：read-only / workspace-write / danger 三档一律放行（横切决策 14）
        for (WorkspacePolicy.Mode mode : WorkspacePolicy.Mode.values()) {
            WorkspacePolicy p = new WorkspacePolicy(tempDir.resolve("ws-" + mode), mode);
            assertTrue(p.decide(ReadImageTool.NAME, null) == WorkspacePolicy.Decision.ALLOW,
                    mode + " 档应放行 read_image");
        }
        // 未知工具保守 ask 不回归
        WorkspacePolicy p = new WorkspacePolicy(tempDir.resolve("ws-x"), WorkspacePolicy.Mode.WORKSPACE_WRITE);
        assertTrue(p.decide("未来工具", null) == WorkspacePolicy.Decision.ASK);
    }
}

package dev.duo.harness.agent.presenter;

import dev.duo.harness.attachment.AttachmentConfig;
import dev.duo.harness.attachment.AttachmentStore;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.tools.fs.ReadImageTool;
import dev.duo.harness.tools.fs.WorkspacePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read_image 视觉闸门回填（M21 收口修正）：注册时闸门缺省 false；呈现位装配后
 * 按 llm.vision 回填——true 放行、false 再拒。用户实测缺陷（拖拽发图可用而
 * read_image 恒拒）的回归用例。
 */
class ReadImageVisionGateTest {

    @TempDir
    Path tempDir;

    private Context root;

    @BeforeEach
    void mount() {
        root = Context.root();
    }

    @AfterEach
    void unmount() throws Exception {
        root.dispose();
    }

    interface ToolsView {

        ToolsService tools();
    }

    private byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String executeReadImage(String pngPath) throws Exception {
        ToolResult result = root.as(ToolsView.class).tools().execute("read_image",
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .createObjectNode().put("file_path", pngPath));
        return String.valueOf(result.value());
    }

    @Test
    void gateDefaultsFalseThenBackfilledByPresenter() throws Exception {
        AttachmentConfig cfg = new AttachmentConfig(20_971_520, 20, 104_857_600,
                67_108_864, 8192, 4_194_304, 8192, 4_194_304);
        AttachmentStore store = new AttachmentStore(tempDir.resolve("att/v1"), cfg);
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.as(ToolsView.class).tools().register(root, new ReadImageTool(
                new WorkspacePolicy(tempDir, WorkspacePolicy.Mode.DANGER_FULL_ACCESS),
                store, () -> false));

        Path png = tempDir.resolve("shot.png");
        Files.write(png, png());

        // ① 回填前：闸门缺省 false → 拒（零 I/O，不泄露文件存在性）
        String before = executeReadImage(png.toString());
        assertTrue(before.contains("llm.vision 未启用"), "回填前应拒: " + before);

        // ② 呈现位回填 vision=true → 放行（读图入库返回引用）
        PresenterAssembly.wireReadImageVisionGate(root.as(ToolsView.class).tools(), true);
        String after = executeReadImage(png.toString());
        assertFalse(after.contains("llm.vision 未启用"), "回填后应放行: " + after);
        assertTrue(after.contains("附件已入库"), "读图应入库返回引用: " + after);

        // ③ 非视觉部署回填 false → 再拒
        PresenterAssembly.wireReadImageVisionGate(root.as(ToolsView.class).tools(), false);
        assertTrue(executeReadImage(png.toString()).contains("llm.vision 未启用"));
    }
}

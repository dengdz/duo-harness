package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.attachment.AdmittedImage;
import dev.duo.harness.attachment.AttachmentStore;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * read_image 工具（M21 工单 03，ADR-0022）：读 workspace 内图片文件，先持久化到
 * 附件库再返回引用——模型侧唯一的附件写入口（wire 调用者永远无法引用未上传的附件）。
 *
 * <p>执行前视觉能力闸门（llm.vision，缺省 false）：非视觉部署零 I/O 即拒。归本地
 * 读类三档放行（WorkspacePolicy.READ_TOOLS）；纯只读无共享可变状态（附件库写入
 * 幂等——内容寻址），并发安全。</p>
 */
public final class ReadImageTool implements ToolDefinition {

    public static final String NAME = "read_image";

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final WorkspacePolicy workspace;
    private final AttachmentStore attachments;
    /** 视觉能力闸门（llm.vision，缺省 false——工单 05 接线真实配置）。 */
    private final java.util.function.BooleanSupplier visionGate;

    public ReadImageTool(WorkspacePolicy workspace, AttachmentStore attachments,
                         java.util.function.BooleanSupplier visionGate) {
        this.workspace = workspace;
        this.attachments = attachments;
        this.visionGate = visionGate;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取一张本地图片文件（png/jpeg/gif/webp），让模型直接查看其视觉内容。"
                + "图片会同时存入附件库（内容寻址去重）；需基于截图/照片/图表回答时使用。";
    }

    @Override public JsonNode parameters() {
        try {
            return MAPPER.readTree(
                    "{\"type\":\"object\",\"properties\":{"
                            + "\"file_path\":{\"type\":\"string\",\"description\":\"图片文件路径（workspace 内）\"}"
                            + "},\"required\":[\"file_path\"]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 纯只读（附件库写入内容寻址幂等）——多路读图进并行池（ADR-0018）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return true; }

    @Override public String execute(ToolExecution exec) {
        // 闸门最先：非视觉部署零 I/O（文件不存在也一样拒，不泄露任何探测面）
        if (visionGate == null || !visionGate.getAsBoolean()) {
            return error("当前模型不支持图片（llm.vision 未启用）——请以文字描述图片内容");
        }
        String raw = exec.args().path("file_path").asText("");
        if (raw.isBlank()) {
            return error("参数 file_path 不能为空");
        }
        Path target = workspace.resolveInWorkspaceOrNull(raw);
        if (target == null || !Files.isRegularFile(target)) {
            return error("文件不存在: " + raw);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("read_image 无法读取 " + raw + ": " + e.getMessage(), e);
        }
        String declared = declaredMediaType(target);
        AdmittedImage admitted;
        try {
            admitted = attachments.storeImage(bytes, declared);
        } catch (dev.duo.harness.attachment.AttachmentException e) {
            // 附件准入语义已是"面向模型的结构化拒绝"（超限/格式/不符均带自纠指引）
            return error(e.getMessage());
        }
        return "Read " + raw + " (" + admitted.mediaType() + ", " + admitted.width() + "x"
                + admitted.height() + ", " + admitted.bytes() + " bytes)\n附件已入库: "
                + admitted.attachmentId() + "——视觉内容已随本条结果提供给模型。";
    }

    /** 按扩展名给出声明类型（无扩展名传 null——魔数嗅探兜底判定）。 */
    private static String declaredMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> null; // 未知扩展名：交给魔数嗅探
        };
    }

    private static String error(String msg) {
        return "[read_image 错误] " + msg;
    }
}

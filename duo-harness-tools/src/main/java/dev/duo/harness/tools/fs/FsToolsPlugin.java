package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;

import java.nio.file.Path;
import java.util.Set;

/**
 * fs 工具族插件（ADR-0012）：注册 read / write / edit / glob / grep / bash 六件套
 * 到工具域——agent 获得真实项目文件操作与命令执行能力（workspace 约束 + 三档权限预设）。
 * 同时发布 "workspace" 服务（{@link WorkspacePolicy}）供工具的路径包含性判定与
 * 呈现位的 `/permission` 命令消费。
 */
public final class FsToolsPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    /**
     * attachments 为可选依赖（ADR-0019）：attachment 插件行缺席的部署不注册
     * read_image（DSH 同款——附件服务不在场则工具不存在），其余六件照常。
     */
    @Override
    public Set<String> optionalInject() {
        return Set.of(dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        ToolsService tools = ctx.as(FsToolsView.class).tools();

        WorkspacePolicy policy = parsePolicy(config);
        Disposable published = ctx.provide(WorkspacePolicy.SERVICE_NAME, policy);

        ReadGate readGate = new ReadGate();
        tools.register(ctx, new FsReadTool(policy, readGate));
        // 视觉闸门缺省关：工单 05 接线 llm.vision 真实配置（缺省 false 的保守语义不变）
        if (ctx.hasService(dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME)) {
            var attachments = ctx.as(FsAttachmentsView.class).attachments();
            tools.register(ctx, new ReadImageTool(policy, attachments, () -> false));
        }
        tools.register(ctx, new FsWriteTool(policy, readGate));
        tools.register(ctx, new FsEditTool(policy, readGate));
        tools.register(ctx, new FsGlobTool(policy));
        tools.register(ctx, new FsGrepTool(policy));
        // 后台任务（M23 工单 04，ADR-0025 决策二）：注册表发布为服务（呈现位挂完成
        // 通知路由），bash 转后台 + task-output/task-stop 构造注入同一实例
        BackgroundTaskRegistry backgroundTasks = new BackgroundTaskRegistry();
        Disposable registryPublished = ctx.provide(BackgroundTaskRegistry.SERVICE_NAME, backgroundTasks);
        tools.register(ctx, new FsBashTool(policy, backgroundTasks));
        tools.register(ctx, new TaskOutputTool(backgroundTasks));
        tools.register(ctx, new TaskStopTool(backgroundTasks));

        return () -> {
            try {
                backgroundTasks.clearListeners(); // 先摘通知路由：shutdown 的 KILLED 不再路由进拆树中的呈现位
                backgroundTasks.shutdownAll(); // 插件树停止：杀全部后台进程树（防孤儿）
                registryPublished.dispose();
                published.dispose();
            } catch (Exception ignored) {
                // 服务回收失败无可补救：树正在拆，注册表随 tree 释放，不掩盖拆树主流程
            }
        };
    }

    private static WorkspacePolicy parsePolicy(JsonNode config) {
        WorkspacePolicy.Mode mode = WorkspacePolicy.Mode.WORKSPACE_WRITE;
        String root = null;
        if (config != null) {
            if (config.hasNonNull("mode")) {
                mode = WorkspacePolicy.Mode.parse(config.get("mode").asText());
            }
            if (config.hasNonNull("root") && !config.get("root").asText().isBlank()) {
                root = config.get("root").asText();
            }
        }
        Path workspaceRoot = root != null
                ? Path.of(root) : Path.of(System.getProperty("user.dir"));
        return new WorkspacePolicy(workspaceRoot, mode);
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface FsToolsView {

        ToolsService tools();
    }

    /** attachments 服务的视图接口（方法名即服务名）。 */
    interface FsAttachmentsView {

        dev.duo.harness.attachment.AttachmentStore attachments();
    }
}

package dev.duo.harness.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.boot.DuoHome;

import java.nio.file.Path;

/**
 * 附件域插件（M21，ADR-0022）：发布 "attachments" 服务——内容寻址图片库。
 * 存储根固定 DuoHome 体系（{@code ~/.duo/attachments/v1}，测试经 duo.home 属性重定向）；
 * 无外部网络依赖、无 GC——"永不自动删除"由实现物理保证。
 */
public final class AttachmentPlugin implements Plugin<JsonNode> {

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        AttachmentConfig cfg = AttachmentConfig.parse(config);
        Path root = DuoHome.resolve().root().resolve("attachments").resolve("v1");
        AttachmentStore store = new AttachmentStore(root, cfg);
        Disposable published = ctx.provide(AttachmentStore.SERVICE_NAME, store);
        return () -> {
            try {
                published.dispose();
            } catch (Exception ignored) {
                // 服务回收失败无可补救：树正在拆，注册表随 tree 释放
            }
        };
    }
}

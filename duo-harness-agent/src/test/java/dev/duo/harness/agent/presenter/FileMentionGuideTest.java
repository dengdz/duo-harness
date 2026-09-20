package dev.duo.harness.agent.presenter;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.prompt.PromptFragment;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @file 指南注入条件（M21 工单 07，ADR-0022 决策 7）：仅 read 工具在册时注册；
 * 双呈现位同源去重。
 */
class FileMentionGuideTest {

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

    /** 桩 read 工具（只占名位，注入判定不看实现）。 */
    private static class StubReadTool implements ToolDefinition {

        @Override public String name() { return "read"; }

        @Override public String description() { return "read 桩"; }

        @Override public JsonNode parameters() { return NullNode.getInstance(); }

        @Override public Object execute(ToolExecution execution) { return ""; }
    }

    @Test
    void guideRegisteredOnlyWhenReadPresent() throws Exception {
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        PromptRegistry prompts = new PromptRegistry(null);

        PresenterAssembly.registerFileMentionGuide(ctx(root), tools, prompts);
        assertFalse(prompts.compose().contains("未被 read 过的文件"), "无 read 部署零注入");

        tools.register(root, new StubReadTool());
        PresenterAssembly.registerFileMentionGuide(ctx(root), tools, prompts);
        assertTrue(prompts.compose().contains("未被 read 过的文件"));
        assertTrue(prompts.hasSource(PresenterAssembly.FILE_MENTION_GUIDE_SOURCE));
    }

    @Test
    void duplicateRegistrationDedupedBySource() throws Exception {
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        tools.register(root, new StubReadTool());
        PromptRegistry prompts = new PromptRegistry(null);

        PresenterAssembly.registerFileMentionGuide(ctx(root), tools, prompts);
        PresenterAssembly.registerFileMentionGuide(ctx(root), tools, prompts); // 第二呈现位再调

        long count = prompts.compose().split("未被 read 过的文件", -1).length - 1;
        assertTrue(count == 1, "同源片段只注一份，实际 " + count + " 次: " + prompts.compose());
    }

    private Context ctx(Context scope) {
        return scope;
    }
}

package dev.duo.harness.example.agentrepl;

import dev.duo.harness.agent.PromptFragment;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.example.DuoMain;

import java.nio.file.Path;

/**
 * Agent 演示入口（兼容壳，ADR-0011）：CLI 呈现已插件化（`CliPlugin`，
 * duo-harness-cli 模块）——本类只剩演示专属装配（MCP files 沙箱连接与演示
 * 提示片段），经 {@link DuoMain} 的挂载回调注入。启动命令与终端交互不变。
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am package exec:java
 * -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain}</p>
 */
public final class AgentReplMain {

    private AgentReplMain() {
    }

    public static void main(String[] args) throws Exception {
        Path yml = Path.of(AgentReplMain.class.getResource("/agent-demo.yml").toURI());
        DuoMain.run(new String[]{yml.toString()}, AgentReplMain::mountDemoExtras);
    }

    /** 演示专属装配：MCP files 连接（临时沙箱 + notes.txt）与演示提示片段——插在树启动后、首条输入前。 */
    private static void mountDemoExtras(Context root) throws Exception {
        Path rootDir = java.nio.file.Files.createTempDirectory("duo-agent-demo");
        java.nio.file.Files.writeString(rootDir.resolve("notes.txt"), "agent 演示的文件内容");
        var mcpConfig = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("serverName", "files")
                .put("command", Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .put("requestTimeoutMs", 5_000);
        mcpConfig.putObject("reconnect")
                .put("initialDelayMs", 100).put("maxDelayMs", 500).put("maxAttempts", 3);
        var mcpArgs = mcpConfig.putArray("args")
                .add("-cp").add(dev.duo.harness.example.DemoMain.subprocessClasspath())
                .add("dev.duo.harness.example.mcpfs.MiniFileSystemServer")
                .add(rootDir.toString());
        root.plugin(new dev.duo.harness.mcp.McpClientPlugin(), mcpConfig).awaitStartup();

        PromptRegistry prompts = root.as(DemoPromptsView.class).prompts();
        prompts.register(root, new PromptFragment("demo:platform", "执行文件操作前先确认目标路径。"));
    }

    /** prompt 注册表的视图接口（方法名即服务名 "prompts"）。 */
    interface DemoPromptsView {

        PromptRegistry prompts();
    }
}

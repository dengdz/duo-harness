package dev.duo.harness.example.agentrepl;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.internal.OpenAiCompatAdapter;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.example.tools.ToolsView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * M5 agent 演示入口：REPL 循环——LLM 自主调用工具（Function Calling 经
 * 工具域三段管线与治理链），审批拒绝可见。
 *
 * <p>前置：{@code ~/.duo/config.yml} 配置 llm 段。Boot 装载工具域 + 审批
 * always-deny + 写保护（复用 demo-m2.yml 的治理配置）；MCP files 连接按
 * demo-m2.yml 注释的等价形态以编程挂载（MiniFileSystemServer 指向临时目录）。</p>
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am package exec:java
 * -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain}</p>
 */
public final class AgentReplMain {

    private AgentReplMain() {
    }

    public static void main(String[] args) throws Exception {
        run(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    /** 可测入口（冒烟测试经它注入脚本输入与 mock LLM）。 */
    public static void run(BufferedReader in, PrintStream out) throws Exception {
        LlmConfig config;
        try {
            config = LlmConfig.load();
        } catch (PluginException e) {
            out.println("LLM 未配置：");
            out.println("  " + e.getMessage());
            out.println("示例（~/.duo/config.yml）：");
            out.println("  llm:");
            out.println("    baseUrl: https://api.deepseek.com");
            out.println("    apiKey: <你的 key>");
            out.println("    model: deepseek-chat");
            out.flush();
            return;
        }

        Path yml = Path.of(AgentReplMain.class.getResource("/agent-demo.yml").toURI());
        Context root = Boot.from(yml);

        // MCP files 连接：编程挂载（等价 yml 行：serverName=files，
        // command=java，args=[-cp, <classpath>, MiniFileSystemServer, <rootDir>]）
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

        ToolsService tools = root.as(AgentToolsView.class).tools();
        Session session = Session.latest(DuoHome.resolve().resolveDir("agent-sessions"));
        if (session == null) {
            session = Session.create(DuoHome.resolve().resolveDir("agent-sessions"));
        }
        out.println("会话 " + session.id() + "（工具循环上下文）。/exit 退出。");
        out.flush();

        ChatAgent agent = new dev.duo.harness.agent.internal.ToolCallingAgent(
                new OpenAiCompatAdapter(config), tools, session, config.systemPrompt());

        while (true) {
            out.print("你> ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.strip().equals("/exit")) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                var reply = agent.send(line.strip(), new AgentListener() {
                    @Override
                    public void onChunk(String text) {
                        out.print(text);
                        out.flush();
                    }

                    @Override
                    public void onToolCall(String toolName, String argumentsJson) {
                        out.println();
                        out.println("  [调工具] " + toolName + " " + argumentsJson);
                        out.flush();
                    }

                    @Override
                    public void onToolResult(String toolName, String resultText, boolean isError) {
                        out.println("  [工具" + (isError ? "错误] " : "结果] ") + resultText);
                        out.flush();
                    }
                });
                if (!reply.completed()) {
                    out.println("  [异常终止] " + reply.finalText());
                }
            } catch (PluginException e) {
                out.println("  [错误] " + e.getMessage());
            }
            out.println();
            out.flush();
        }
        out.println("=== 对话结束 ===");
        out.flush();
        root.dispose();
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface AgentToolsView {

        ToolsService tools();
    }
}

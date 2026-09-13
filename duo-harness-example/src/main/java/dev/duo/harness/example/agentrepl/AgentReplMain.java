package dev.duo.harness.example.agentrepl;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.PromptFragment;
import dev.duo.harness.agent.Skill;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.RetryingAdapter;
import dev.duo.harness.llm.internal.OpenAiCompatAdapter;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.AnswersView;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.example.tools.ToolsView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * M6 agent 演示入口：REPL 循环——LLM 自主调用工具（Function Calling 经
 * 工具域三段管线与治理链）；HITL 交互（写操作终端 y/n 审批、ask_user 提问）、
 * 重试与重复调用提醒。ADR-0008 的 CLI 呈现位。
 *
 * <p>前置：{@code ~/.duo/config.yml} 配置 llm 段（retry 子段可选）。Boot 装载
 * 工具域 + 交互服务 + 交互审批 + 写保护 + ask_user + prompt 演示片段；MCP files
 * 连接以编程挂载（MiniFileSystemServer 指向临时目录）。</p>
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
        InteractionService answers = root.as(AgentAnswersView.class).answers();
        dev.duo.harness.agent.SkillRegistry skills = root.as(AgentSkillsView.class).skills();
        tools.register(root, new dev.duo.harness.tools.AskUserTool(answers));

        Path sessionsDir = DuoHome.resolve().resolveDir("agent-sessions");
        Session session = Session.latest(sessionsDir);
        if (session == null) {
            session = Session.create(sessionsDir);
        }
        out.println("会话 " + session.id() + "（工具循环上下文）。/exit 退出，/new 开新话题。");
        out.flush();

        // CLI 回答者（审批 y/n、提问呈现）+ 审计桥（审批事件落会话）——ADR-0008 呈现位
        answers.register(root, new AuditingAnswerer(session, new ConsoleAnswerer(in, out)));

        PromptRegistry prompts = root.as(AgentPromptsView.class).prompts();
        prompts.register(root, new PromptFragment("demo:platform", "执行文件操作前先确认目标路径。"));

        LlmAdapterHolder llm = new LlmAdapterHolder(new RetryingAdapter(new OpenAiCompatAdapter(config),
                config.retryMaxAttempts(), config.retryInitialBackoffMs()));
        SessionHolder sessionHolder = new SessionHolder(session);
        ChatAgent agent = buildAgent(llm.adapter, tools, sessionHolder.session, prompts);

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
            if (line.strip().equals("/new")) {
                sessionHolder.session = Session.create(sessionsDir);
                agent = buildAgent(llm.adapter, tools, sessionHolder.session, prompts);
                out.println("新会话 " + sessionHolder.session.id() + "。");
                out.flush();
                continue;
            }
            String userText = resolveSkillInvocation(line.strip(), skills);
            if (userText == null) {
                List<String> available = skills.all().stream().map(Skill::name).toList();
                out.println("未知命令: " + line.strip().split("\\s+", 2)[0]
                        + (available.isEmpty() ? "" : "（可用技能: " + String.join(", ", available) + "）"));
                out.flush();
                continue;
            }
            try {
                var reply = agent.send(userText, new AgentListener() {
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

    private static ChatAgent buildAgent(dev.duo.harness.llm.LlmAdapter adapter, ToolsService tools,
                                        Session session, PromptRegistry prompts) {
        return new dev.duo.harness.agent.internal.ToolCallingAgent(adapter, tools, session, prompts);
    }

    /** 可变引用：/new 时换会话、重建 agent（ToolCallingAgent 持有 final 会话引用）。 */
    private static final class SessionHolder {
        Session session;

        SessionHolder(Session session) {
            this.session = session;
        }
    }

    /** 可变引用：适配器仅构建一次，/new 重建 agent 时复用。 */
    private static final class LlmAdapterHolder {
        final dev.duo.harness.llm.LlmAdapter adapter;

        LlmAdapterHolder(dev.duo.harness.llm.LlmAdapter adapter) {
            this.adapter = adapter;
        }
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface AgentToolsView {

        ToolsService tools();
    }

    /** 交互服务的视图接口（方法名即服务名 "answers"）。 */
    interface AgentAnswersView {

        InteractionService answers();
    }

    /** prompt 注册表的视图接口（方法名即服务名 "prompts"）。 */
    interface AgentPromptsView {

        PromptRegistry prompts();
    }

    /** 技能注册表的视图接口（方法名即服务名 "skills"）。 */
    interface AgentSkillsView {

        dev.duo.harness.agent.SkillRegistry skills();
    }

    /**
     * 技能直调识别（用户直调路，M7 三路触发之三）：`/技能名 [其余输入]` →
     * 技能指令全文前缀注入（"指令\n\n用户输入：其余"）；未匹配技能名返回 null
     * （内置命令 /exit /new 由调用方先行处理，优先于技能名）。
     */
    static String resolveSkillInvocation(String line, dev.duo.harness.agent.SkillRegistry skills) {
        if (!line.startsWith("/")) {
            return line;
        }
        String[] parts = line.split("\\s+", 2);
        dev.duo.harness.agent.Skill skill = skills.find(parts[0].substring(1));
        if (skill == null) {
            return null;
        }
        String rest = parts.length > 1 ? parts[1].strip() : "";
        return rest.isBlank() ? skill.content() : skill.content() + "\n\n用户输入：" + rest;
    }
}

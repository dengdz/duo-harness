package dev.duo.harness.example.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 输出契约与 guard 演示入口：三幕对照。
 *
 * <ol>
 *   <li>第一幕【输出契约】——同一批工具：声明契约且合规 → 放行；
 *       声明契约但违约 → error 结果点名违约原因；未声明 → 宽松透传。</li>
 *   <li>第二幕【guard】——guard 理由拒绝（署名 guard）、null 放行；
 *       首个拒绝短路，后续 guard 不再执行。</li>
 *   <li>第三幕【治理链全景】——审批、guard、契约同时在场：
 *       审批拒绝的调用到不了 guard（guard 计数为零证明时序）。</li>
 * </ol>
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am package -DskipTests exec:java
 * -Dexec.mainClass=dev.duo.harness.example.contract.ContractGuardDemoMain}</p>
 */
public final class ContractGuardDemoMain {

    private ContractGuardDemoMain() {
    }

    /** 输出面向演示终端，是本类的功能而非调试残留。 */
    public static void main(String[] args) throws Exception {
        Path yml = Path.of(ContractGuardDemoMain.class.getResource("/contract-demo.yml").toURI());
        run(yml, System.out);
    }

    /** 可测入口（冒烟测试经它断言输出叙述）。 */
    public static void run(Path yml, PrintStream out) throws Exception {
        out.println("=== duo-harness 输出契约与 guard Demo ===");

        out.println();
        out.println("[第一幕] 输出契约：声明即校验，未声明宽松透传（配置 " + yml.getFileName() + "）");
        Context root = Boot.from(yml);
        contractAct(out, root);
        root.dispose();

        out.println();
        out.println("[第二幕] guard 单调否决：理由即拒、null 放行、首个拒绝短路");
        guardAct(out);

        out.println();
        out.println("[第三幕] 治理链全景：审批 → guard → 本体 → 契约（谁先拒绝，后面都不跑）");
        pipelineAct(out);

        out.println();
        out.println("=== 三幕结束（整树均已回滚）===");
        out.flush();
    }

    // === 第一幕：输出契约 ===

    private static void contractAct(PrintStream out, Context root) {
        ToolsService tools = root.as(ContractToolsView.class).tools();
        // 契约：{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
        JsonNode textContract = objectSchemaRequiring("text", "string");
        tools.register(root, fixedTool("summarize", Map.of("text", "摘要内容"), textContract));
        tools.register(root, fixedTool("search", Map.of("text", 42), textContract));
        tools.register(root, fixedTool("read_config", "任意形态都行", null));

        report(out, tools, "summarize", "返回 {\"text\":\"摘要内容\"}——符合契约");
        report(out, tools, "search", "返回 {\"text\":42}——text 应为 string，违约");
        report(out, tools, "read_config", "未声明契约——返回什么都是它");
    }

    // === 第二幕：guard ===

    private static void guardAct(PrintStream out) {
        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ContractToolsView.class).tools();
        AtomicInteger secondGuardRuns = new AtomicInteger();
        tools.register(root, fixedTool("read_file", "文件内容", null));
        tools.guard(root, exec -> exec.args().path("target").asText("").contains("机密")
                ? "目标文件含敏感词" : null);
        tools.guard(root, exec -> {
            secondGuardRuns.incrementAndGet();
            return null;
        });

        out.println("调用 read_file(target=公开笔记):");
        report(out, tools, "read_file",
                JsonNodeFactory.instance.objectNode().put("target", "公开笔记"));
        out.println("  第二道 guard 执行次数: " + secondGuardRuns.get() + "（首个放行，链继续）");

        out.println("调用 read_file(target=机密档案):");
        report(out, tools, "read_file",
                JsonNodeFactory.instance.objectNode().put("target", "机密档案"));
        out.println("  第二道 guard 执行次数: " + secondGuardRuns.get() + "（首个拒绝即短路，链终止）");
        root.dispose();
    }

    // === 第三幕：治理链全景 ===

    private static void pipelineAct(PrintStream out) {
        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.plugin(new dev.duo.harness.tools.ApprovalPlugin(),
                JsonNodeFactory.instance.objectNode().put("policy", "always-deny")).awaitStartup();
        ToolsService tools = root.as(ContractToolsView.class).tools();
        AtomicInteger guardRuns = new AtomicInteger();
        tools.register(root, auditedTool("deploy", guardRuns));
        tools.guard(root, exec -> {
            guardRuns.incrementAndGet();
            return null;
        });

        out.println("调用 deploy（工具声明需审批，策略 always-deny）:");
        ToolResult result = tools.execute("deploy", NullNode.getInstance());
        out.println("  -> [" + (result.isError() ? "被拒" : "放行") + "] " + result.value());
        out.println("  guard 执行次数: " + guardRuns.get() + "（审批拒绝在先，调用到不了 guard）");
        root.dispose();
    }

    // === 工具与叙述 ===

    /** 服务视图接口（方法名即服务名）。 */
    interface ContractToolsView {

        ToolsService tools();
    }

    /** 返回固定值的工具；output 为其输出契约（null = 未声明）。 */
    private static ToolDefinition fixedTool(String name, Object returns, JsonNode output) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "演示工具: " + name;
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public JsonNode output() {
                return output;
            }

            @Override
            public Object execute(ToolExecution execution) {
                return returns;
            }
        };
    }

    /** 声明需审批的工具（工具自身 ask，审批策略裁决）。 */
    private static ToolDefinition auditedTool(String name, AtomicInteger executions) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "需审批的演示工具";
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public boolean requiresApproval() {
                return true;
            }

            @Override
            public Object execute(ToolExecution execution) {
                executions.incrementAndGet();
                return name + " 已执行";
            }
        };
    }

    private static void report(PrintStream out, ToolsService tools, String toolName, String note) {
        ToolResult result = tools.execute(toolName, NullNode.getInstance());
        out.println("调用 " + toolName + "（" + note + "）:");
        out.println("  -> [" + (result.isError() ? "被拒" : "放行") + "] " + result.value());
    }

    private static void report(PrintStream out, ToolsService tools, String toolName, JsonNode args) {
        ToolResult result = tools.execute(toolName, args);
        out.println("  -> [" + (result.isError() ? "被拒" : "放行") + "] " + result.value());
    }

    private static JsonNode objectSchemaRequiring(String field, String type) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object");
        root.putObject("properties").putObject(field).put("type", type);
        return root;
    }
}

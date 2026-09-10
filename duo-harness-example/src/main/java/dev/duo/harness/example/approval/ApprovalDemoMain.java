package dev.duo.harness.example.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.example.tools.ToolsView;
import dev.duo.harness.tools.ApprovalPlugin;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * 审批策略演示入口：三幕对照，一条命令看全策略矩阵。
 *
 * <ol>
 *   <li>第一幕（yml 配置路径）auto-approve + 白名单 [read_file]：
 *       白名单内放行、白名单外拒绝、未声明不介入</li>
 *   <li>第二幕（编程 API）always-deny：同一批工具全拒</li>
 *   <li>第三幕（编程 API）无审批插件：声明需审批的调用"未配置即拒"（来源 none）</li>
 * </ol>
 *
 * <p>审批决策另有安全审计日志（结果 + 策略来源 + 工具名），由 SLF4J 打到标准错误。</p>
 *
 * <p>运行：{@code mvn -pl duo-harness-example exec:java}
 * （默认 mainClass 为 M1 的 DemoMain；本入口见 SKILL 记录的
 * {@code -Dexec.mainClass=...ApprovalDemoMain} 命令）。</p>
 */
public final class ApprovalDemoMain {

    private ApprovalDemoMain() {
    }

    /** 输出面向演示终端，是本类的功能而非调试残留。 */
    public static void main(String[] args) throws Exception {
        Path yml = Path.of(ApprovalDemoMain.class.getResource("/approval-demo.yml").toURI());
        run(yml, System.out);
    }

    /** 可测入口（冒烟测试经它断言输出叙述）。 */
    public static void run(Path yml, PrintStream out) throws Exception {
        out.println("=== duo-harness 审批策略 Demo：ask 三态与策略矩阵 ===");

        out.println();
        out.println("[第一幕] yml 配置 auto-approve + 白名单 [read_file]（读 " + yml.getFileName() + "）");
        Context root = Boot.from(yml);
        callAll(out, root);
        root.dispose();

        out.println();
        out.println("[第二幕] 编程 API 换策略为 always-deny：同一批工具全拒");
        Context denyRoot = Context.root();
        denyRoot.plugin(new ToolsPlugin(), null).awaitStartup();
        denyRoot.plugin(new ApprovalPlugin(), autoOrDenyConfig("always-deny", null)).awaitStartup();
        denyRoot.plugin(new ApprovalToolsPlugin(), null).awaitStartup();
        callAll(out, denyRoot);
        denyRoot.dispose();

        out.println();
        out.println("[第三幕] 无审批插件：声明需审批的调用按\"未配置即拒\"处理，未声明的照常");
        Context bare = Context.root();
        bare.plugin(new ToolsPlugin(), null).awaitStartup();
        bare.plugin(new ApprovalToolsPlugin(), null).awaitStartup();
        callAll(out, bare);
        bare.dispose();

        out.println();
        out.println("=== 三幕结束（整树均已回滚）===");
        out.println("说明：上方 [审批决策] 行是安全审计日志（结果 + 策略来源 + 工具名）。");
        out.println("     来源 always-deny / auto-approve 是策略署名；来源 none 表示无策略解析者，管线兜底拒。");
        out.flush();
    }

    /**
     * 逐个调用三个工具并叙述结果（审批行为的观测量就是这三行）。
     *
     * <p>幕末显式刷盘：审计日志走标准错误（立即刷），叙述走标准输出——
     * 标准输出在非终端环境（IDE 控制台）不缓冲到刷新点，不刷则同一幕的
     * 日志与叙述会被拆到输出两端，逐行核对读不出来。</p>
     */
    private static void callAll(PrintStream out, Context root) {
        ToolsService tools = root.as(ToolsView.class).tools();
        report(out, ApprovalToolsPlugin.READ, tools);
        report(out, ApprovalToolsPlugin.DELETE, tools);
        report(out, ApprovalToolsPlugin.ECHO, tools);
        out.flush();
    }

    private static void report(PrintStream out, String toolName, ToolsService tools) {
        ToolResult result = tools.execute(toolName, NullNode.getInstance());
        String flag = result.isError() ? "被拒" : "放行";
        out.println("  " + toolName + " -> [" + flag + "] " + result.value());
    }

    private static JsonNode autoOrDenyConfig(String policy, String whitelistTool) {
        var node = JsonNodeFactory.instance.objectNode().put("policy", policy);
        if (whitelistTool != null) {
            node.putArray("allowedTools").add(whitelistTool);
        }
        return node;
    }
}

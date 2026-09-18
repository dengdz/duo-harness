package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管线缺省超时用例（ADR-0018，工单 M17-02）：超时中断并回流错误、豁免不受限、
 * 覆盖声明优先于缺省（双向）、工具异常收敛路径不被监听器破坏、bash 协作式放宽。
 * 短超时注入（100ms 级）验证，不等真实 120s 缺省。
 */
class PipelineTimeoutTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PipelineTimeoutTest —— 管线缺省超时：中断回流、豁免、覆盖优先、"
                + "双开挂载查重先到先得、摘除后可重挂、注册失败回滚（8 用例） ===");
    }

    /** 服务视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    private Context root;
    private ToolsService tools;

    @BeforeEach
    void setUp() {
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        tools = root.as(ToolsView.class).tools();
    }

    @AfterEach
    void tearDown() {
        root.dispose();
    }

    /** 可配延迟与豁免/覆盖形态的探针工具；interrupt 感知记入 latch。 */
    private static final class ProbeTool implements ToolDefinition {
        private final String name;
        private final long delayMs;
        private final boolean exempt;
        private final Long overrideMs;
        final CountDownLatch interrupted = new CountDownLatch(1);

        ProbeTool(String name, long delayMs) {
            this(name, delayMs, false, null);
        }

        ProbeTool(String name, long delayMs, boolean exempt, Long overrideMs) {
            this.name = name;
            this.delayMs = delayMs;
            this.exempt = exempt;
            this.overrideMs = overrideMs;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "探针 " + name; }

        @Override public JsonNode parameters() {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }

        @Override public boolean exemptFromPipelineTimeout(JsonNode args) { return exempt; }

        @Override public Long pipelineTimeoutMs(JsonNode args) { return overrideMs; }

        @Override public String execute(ToolExecution exec) {
            try {
                Thread.sleep(delayMs);
                return "ok:" + name;
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
    }

    @Test
    void doubleMountDeduplicatedFirstWins() throws Exception {
        // cli + web 双开共享 ToolsService：第二次挂载查重跳过（先到先得，返回空操作器），
        // 不叠挂成嵌套超时——超时仍由首挂承担且恰触发一次（backlog M17 双挂债销账）
        ProbeTool slow = new ProbeTool("slow", 300);
        tools.register(root, slow);
        PipelineTimeout.mount(root, tools, 100);
        PipelineTimeout.mount(root, tools, 100).dispose(); // 空操作器：dispose 不影响首挂

        ToolResult result = tools.execute("slow", JsonNodeFactory.instance.objectNode());
        assertTrue(result.isError() && result.value().toString().contains("执行超时"),
                "重复挂载跳过后超时仍由首挂承担: " + result.value());
        assertTrue(slow.interrupted.await(1, TimeUnit.SECONDS), "执行线程被中断恰一次");
    }

    @Test
    void mountFailureRollsBackMarker() throws Exception {
        // 注册失败路径回滚查重标记：注册方作用域已销毁时 on/effect 抛出——不回滚则该
        // ToolsService 的后续挂载全部被静默跳过、超时保护永久缺席（OCR 审查发现）
        Context dead = Context.root();
        dead.dispose();
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> PipelineTimeout.mount(dead, tools, 100));

        ProbeTool slow = new ProbeTool("slow2", 300);
        tools.register(root, slow);
        PipelineTimeout.mount(root, tools, 100); // 标记已回滚：此处不得被查重跳过
        ToolResult result = tools.execute("slow2", JsonNodeFactory.instance.objectNode());
        assertTrue(result.isError() && result.value().toString().contains("执行超时"),
                "注册失败回滚后重新挂载应生效: " + result.value());
    }

    @Test
    void mountRearmsAfterManualRemoval() throws Exception {
        // 首个挂载手动摘除（标记随摘除清除）后可重新挂载，超时恢复生效
        ProbeTool stuck = new ProbeTool("stuck2", 10_000);
        tools.register(root, stuck);
        Disposable first = PipelineTimeout.mount(root, tools, 100);
        first.dispose();

        PipelineTimeout.mount(root, tools, 100);
        ToolResult result = tools.execute("stuck2", JsonNodeFactory.instance.objectNode());
        assertTrue(result.isError() && result.value().toString().contains("执行超时"),
                "摘除后重挂超时恢复生效: " + result.value());
    }

    @Test
    void timeoutInterruptsExecutionAndReturnsError() throws Exception {
        ProbeTool probe = new ProbeTool("stuck", 10_000);
        tools.register(root, probe);
        PipelineTimeout.mount(root, tools, 100);

        long start = System.nanoTime();
        ToolResult result = tools.execute("stuck", JsonNodeFactory.instance.objectNode());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError(), "超时收敛为 error 结果: " + result.value());
        assertTrue(String.valueOf(result.value()).contains("超时"), "错误消息点名超时: " + result.value());
        assertTrue(elapsedMs < 2_000, "不等工具跑完（10s 延迟被 100ms 上限掐断），实测 " + elapsedMs + "ms");
        assertTrue(probe.interrupted.await(2, TimeUnit.SECONDS), "执行线程已被 interrupt（阻塞等待可被打断）");
    }

    @Test
    void exemptToolWaitsIndefinitely() {
        ProbeTool patient = new ProbeTool("patient", 150, true, null);
        tools.register(root, patient);
        PipelineTimeout.mount(root, tools, 100);

        ToolResult result = tools.execute("patient", JsonNodeFactory.instance.objectNode());

        assertFalse(result.isError(), "豁免工具（ask_user 语义）不受缺省超时限制: " + result.value());
        assertEquals("ok:patient", result.value());
    }

    @Test
    void overrideTakesPrecedenceOverDefault() {
        ProbeTool longOverride = new ProbeTool("relaxed", 150, false, 400L);
        ProbeTool shortOverride = new ProbeTool("tight", 300, false, 50L);
        tools.register(root, longOverride);
        tools.register(root, shortOverride);
        PipelineTimeout.mount(root, tools, 100);

        ToolResult relaxed = tools.execute("relaxed", JsonNodeFactory.instance.objectNode());
        assertFalse(relaxed.isError(), "覆盖长于缺省时不掐（协作式优先）: " + relaxed.value());

        ToolResult tight = tools.execute("tight", JsonNodeFactory.instance.objectNode());
        assertTrue(tight.isError(), "覆盖短于缺省时按覆盖掐断: " + tight.value());
        assertTrue(String.valueOf(tight.value()).contains("超时"),
                "迟到返回值不得覆盖超时错误（冻结语义，ADR-0018）: " + tight.value());
    }

    @Test
    void toolExceptionStillConvergesToErrorResult() {
        ToolDefinition failing = new ToolDefinition() {
            @Override public String name() { return "failing"; }
            @Override public String description() { return "抛错探针"; }
            @Override public JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }
            @Override public String execute(ToolExecution exec) {
                throw new IllegalStateException("工具本体故障");
            }
        };
        tools.register(root, failing);
        PipelineTimeout.mount(root, tools, 100);

        ToolResult result = tools.execute("failing", JsonNodeFactory.instance.objectNode());

        assertTrue(result.isError(), "异常仍收敛为 error 结果（监听器不破坏既有出口）");
        assertTrue(String.valueOf(result.value()).contains("工具本体故障"),
                "异常信息保真: " + result.value());
    }

    @Test
    void bashPipelineTimeoutRelaxesAboveCooperativeTimeout() {
        // bash 协作式（模型可传 timeoutMs，缺省 120s/上限 600s）优先——管线上限放宽其上 5s，
        // 杀进程树归协作式、管线只兜挂死（ADR-0018）
        dev.duo.harness.tools.fs.FsBashTool bash = new dev.duo.harness.tools.fs.FsBashTool(null);

        Long relaxed = bash.pipelineTimeoutMs(JsonNodeFactory.instance.objectNode()
                .put("timeoutMs", 300_000));
        assertEquals(305_000L, relaxed, "协作式 300s 之上放宽 5s");

        Long defaulted = bash.pipelineTimeoutMs(JsonNodeFactory.instance.objectNode());
        assertEquals(125_000L, defaulted, "无参数时协作式缺省 120s + 5s 缓冲");
    }
}

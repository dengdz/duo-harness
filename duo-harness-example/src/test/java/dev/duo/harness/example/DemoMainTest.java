package dev.duo.harness.example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demo 冒烟测试：完整跑一遍 DemoMain.run（M1 + M2 段），
 * 断言输出叙述覆盖全部演示机制——M1 的 boot/服务注入/三段管线/级联停止，
 * M2 的 MCP 连接（真实 stdio 子进程）/远端工具同步/guard/审批/拔除消失。
 */
class DemoMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：DemoMainTest —— Demo 冒烟：一条命令跑通，输出叙述覆盖"
                + " M1（boot/服务注入/三段管线/级联停止/整树回滚）+ M2（MCP 连接/远端工具/审批/guard/拔除消失）（1 用例） ===");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void demoNarratesAllMechanisms() throws Exception {
        Path yml = Path.of(DemoMain.class.getResource("/demo.yml").toURI());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        DemoMain.run(yml, new PrintStream(buffer, true, StandardCharsets.UTF_8));

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[boot] 插件树激活完成"), output);
        // 服务注入（视图寻址）叙述
        assertTrue(output.contains("消费者经视图获得: 你好, 世界!"), output);
        // 工具三段：正常 / 准入否决 / 结果治理
        assertTrue(output.contains("echo:世界 [已治理]"), output);
        assertTrue(output.contains("敏感词"), output);
        assertTrue(output.contains("[错误]"), output);
        // 行序无关：消费者先 PENDING 后激活
        assertTrue(output.contains("GreetingClientPlugin: PENDING -> LOADING"), output);
        // 级联：拔提供者 → 消费者 UNLOADING 回 PENDING
        assertTrue(output.contains("TempConsumerPlugin: ACTIVE -> UNLOADING"), output);
        assertTrue(output.contains("TempConsumerPlugin: UNLOADING -> PENDING"), output);
        // 收尾
        assertTrue(output.contains("=== Demo 结束"), output);
        // M2 段：MCP 连接 + 治理链（真实 stdio 子进程）
        assertM2Narration(output);
    }

    /** M2 段叙述：远端工具出现/真实文件内容/guard 署名/审批拒绝/拔除后消失。 */
    private static void assertM2Narration(String output) {
        assertTrue(output.contains("=== duo-harness M2 Demo：MCP 连接与治理链 ==="), output);
        // 远端工具读真实文件（内容原样返回）
        assertTrue(output.contains("[M2] read_file(notes.txt)"), output);
        assertTrue(output.contains("这是 notes.txt 的真实内容"), output);
        // guard 拦截涉密文件（署名 guard）
        assertTrue(output.contains("执行被拒绝: 禁止读取涉密文件（guard）"), output);
        // 审批拒绝写操作（工具被声明 ask + always-deny）
        assertTrue(output.contains("执行被拒绝: 被审批策略拒绝（策略: always-deny）"), output);
        // 拔连接后工具消失
        assertTrue(output.contains("[消失] 工具 \"mcp__files__read_file\" 未注册"), output);
        // 连接状态叙述完整
        assertTrue(output.contains("McpClientPlugin: LOADING -> ACTIVE"), output);
        assertTrue(output.contains("McpClientPlugin: UNLOADING -> DISPOSED"), output);
        assertTrue(output.contains("=== M2 Demo 结束"), output);
    }
}

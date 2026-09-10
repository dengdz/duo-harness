package dev.duo.harness.example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demo 冒烟测试：完整跑一遍 DemoMain.run，断言输出叙述覆盖全部演示机制。
 */
class DemoMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：DemoMainTest —— Demo 冒烟：一条命令跑通，输出叙述覆盖 boot/服务注入/三段管线/级联停止/整树回滚全部机制（1 用例） ===");
    }


    @Test
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
    }
}

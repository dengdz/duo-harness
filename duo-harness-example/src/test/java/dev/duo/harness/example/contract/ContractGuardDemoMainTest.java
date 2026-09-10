package dev.duo.harness.example.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约与 guard 演示冒烟：三幕跑通，断言输出叙述覆盖契约违约点名/未声明透传、
 * guard 拒绝署名与顺序短路、审批先于 guard 的时序证据。
 */
class ContractGuardDemoMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContractGuardDemoMainTest —— 契约与 guard 演示冒烟：三幕跑通，"
                + "覆盖违约点名/未声明透传/guard 署名拒绝/顺序短路/审批先行时序（1 用例） ===");
    }

    @Test
    void demoNarratesContractAndGuardBehaviors() throws Exception {
        Path yml = Path.of(ContractGuardDemoMain.class.getResource("/contract-demo.yml").toURI());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        ContractGuardDemoMain.run(yml, new PrintStream(buffer, true, StandardCharsets.UTF_8));

        String output = buffer.toString(StandardCharsets.UTF_8);
        // 第一幕：契约合规放行、违约点名、未声明透传
        assertTrue(output.contains("调用 summarize"), output);
        assertTrue(output.contains("输出违约: 已找到 integer，必须是 string"), output);
        assertTrue(output.contains("[放行] 任意形态都行"), output);
        // 第二幕：guard 拒绝署名（guard）、短路（第二道计数停在 1）
        assertTrue(output.contains("执行被拒绝: 目标文件含敏感词（guard）"), output);
        assertTrue(output.contains("第二道 guard 执行次数: 1"), output);
        // 第三幕：审批拒绝在先，guard 执行次数为 0（时序证据）
        assertTrue(output.contains("被审批策略拒绝（策略: always-deny）"), output);
        assertTrue(output.contains("guard 执行次数: 0"), output);
        // 收尾
        assertTrue(output.contains("=== 三幕结束"), output);
    }
}

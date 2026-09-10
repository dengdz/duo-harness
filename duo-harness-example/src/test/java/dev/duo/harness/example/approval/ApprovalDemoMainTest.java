package dev.duo.harness.example.approval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批演示冒烟：完整跑一遍三幕，断言输出叙述覆盖策略矩阵的每条分支
 * （白名单内放行 / 白名单外拒绝 / 未声明不介入 / 恒拒 / 未配置即拒）。
 */
class ApprovalDemoMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ApprovalDemoMainTest —— 审批演示冒烟：三幕跑通，"
                + "输出叙述覆盖白名单内放行/白名单外拒绝/未声明不介入/恒拒/未配置即拒（1 用例） ===");
    }

    @Test
    void demoNarratesWholePolicyMatrix() throws Exception {
        Path yml = Path.of(ApprovalDemoMain.class.getResource("/approval-demo.yml").toURI());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        ApprovalDemoMain.run(yml, new PrintStream(buffer, true, StandardCharsets.UTF_8));

        String output = buffer.toString(StandardCharsets.UTF_8);
        // 第一幕：yml 配的 auto-approve 白名单 [read_file]
        assertTrue(output.contains("read_file -> [放行]"), output);
        assertTrue(output.contains("不在审批白名单（策略: auto-approve）"), output);
        // 第二幕：always-deny 全拒
        assertTrue(output.contains("被审批策略拒绝（策略: always-deny）"), output);
        // 第三幕：无策略 → 未配置即拒
        assertTrue(output.contains("审批策略未配置（策略: none）"), output);
        // 未声明需审批的工具在三幕里都不被介入（三幕各一次放行）
        assertTrue(countOf(output, "echo -> [放行] echo 已执行") == 3, output);
        // 三幕收尾
        assertTrue(output.contains("=== 三幕结束"), output);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}

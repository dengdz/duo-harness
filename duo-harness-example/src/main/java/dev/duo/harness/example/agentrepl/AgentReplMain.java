package dev.duo.harness.example.agentrepl;

import dev.duo.harness.example.DuoMain;

import java.nio.file.Path;

/**
 * Agent 演示入口（兼容壳，ADR-0011）：演示装配全在 agent-demo.yml 声明（本机 fs
 * 工具族、档位审批、写提醒治理），无编程挂载段；MCP 机制演示在 DemoMain 的 M2 段
 * （demo-m2.yml）。本类是固定装载 agent-demo.yml 的 {@link DuoMain} 别名，
 * 保留只为启动命令不变。
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am package exec:java
 * -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain}</p>
 */
public final class AgentReplMain {

    private AgentReplMain() {
    }

    public static void main(String[] args) throws Exception {
        Path yml = Path.of(AgentReplMain.class.getResource("/agent-demo.yml").toURI());
        DuoMain.main(new String[]{yml.toString()});
    }
}

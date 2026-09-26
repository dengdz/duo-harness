package dev.duo.harness.agent.prompt;

import java.nio.file.Path;
import java.util.Objects;

/**
 * AGENTS.md 链服务（"agentsMd" 服务，M25 工单 07）：meta_user 注入段的供应商——
 * 每请求经 {@link #section()} 现发现现读（fs 增量发现），链语义与免责语见
 * {@link AgentsMd}。不可变无状态，多呈现位共享安全。
 *
 * @param cwd         工作目录（链下探终点）
 * @param userGlobal  用户全局 AGENTS.md 路径
 * @param budgetChars 总预算字符数
 */
public record AgentsMdChain(Path cwd, Path userGlobal, long budgetChars) {

    /** 服务名（camelCase 裸名；消费方视图接口方法名逐字一致）。 */
    public static final String SERVICE_NAME = "agentsMd";

    public AgentsMdChain {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(userGlobal, "userGlobal");
        if (budgetChars < 0) {
            throw new IllegalArgumentException("budgetChars 不能为负: " + budgetChars);
        }
    }

    /** meta_user 注入段（现发现现读；链上无文件为 null——零注入）。 */
    public String section() {
        return AgentsMd.metaUserSection(cwd, userGlobal, budgetChars);
    }
}

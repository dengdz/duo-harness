package dev.duo.harness.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * MCP 远端工具的公开命名（M24 工单 05，ADR-0026 决策四）：有损规范化 + 短哈希
 * 后缀防坍缩——{@code mcp__<server>__<清洗后工具名>__<8位哈希>}。哈希对
 * <b>server 与原始工具名</b>计算（非清洗后形态）：原始名不同的工具即使清洗后
 * 同形（{@code a.b} 与 {@code a$b} 同为 {@code a_b}），哈希也不同——防坍缩承诺
 * 由哈希本身兑现。任何服务器组合下名字稳定可预期（工具名被审批卡、权限规则、
 * 会话历史引用），废弃「清洗坍缩重名即抛错」旧语义。
 */
public final class McpToolNames {

    /** 哈希截断字节数（4 字节 = 8 hex 字符；32 位截断的理论碰撞由调用方序号兜底收敛）。 */
    private static final int HASH_BYTES = 4;

    private McpToolNames() {
    }

    /**
     * 公开工具名：展示段为规范化后的工具名（非法字符替换为下划线），尾缀为
     * server 与<b>原始工具名</b>的 SHA-256 短哈希——同输入恒同名（跨重启稳定），
     * 清洗同形的异名工具哈希必不同。
     *
     * @param serverName  服务器名（yml 声明，已受限字符集）
     * @param rawToolName 远端原始工具名（协议必填，非 null）
     */
    public static String publicName(String serverName, String rawToolName) {
        Objects.requireNonNull(rawToolName, "rawToolName");
        String normalized = rawToolName.replaceAll("[^A-Za-z0-9_-]", "_");
        String base = "mcp__" + serverName + "__" + normalized;
        return base + "__" + shortHash(serverName + '\n' + rawToolName);
    }

    /** SHA-256 前 {@value #HASH_BYTES} 字节的 hex（{@code HASH_BYTES} × 2 字符）。 */
    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, HASH_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}

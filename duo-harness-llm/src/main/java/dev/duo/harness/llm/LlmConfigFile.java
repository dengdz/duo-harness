package dev.duo.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * config.yml 的 llm 段结构化写回（M38 工单 02，ADR-0040 决策四）：查看/编辑/新增
 * 走 Web 端点后的落盘通道——**llm 段文本手术**（定位顶层 llm 块整块替换为重序列化
 * 文本，非 llm 段字节级保留；llm 段内注释不保留，M35 plugins.yml「结构化写回注释
 * 不保留」既定口径同源）。写回前经临时文件 {@link LlmConfig#load} 全规则校验
 * （provider 四值/models 条目/关键项齐全——boot 会怎么读就怎么验），校验过才原子
 * 改名（临时文件 + 原子改名不半写，BootLoader 物化先例），失败不留半文件。
 *
 * <p>effort 不在写回面：思考等级不在 yml 解析面（load 显式声明「写 yml 亦被忽略」），
 * 写回含 effort 即违反「永不静默」——本类只搬运调用方给定的 llm 节点，不注入 effort。</p>
 */
public final class LlmConfigFile {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private LlmConfigFile() {
    }

    /** config.yml 标准位置（{@code ~/.duo/config.yml}，DuoHome 解析含测试注入口）。 */
    public static Path path() {
        return DuoHome.resolve().root().resolve("config.yml");
    }

    /** 读当前 llm 段节点（文件缺失或无 llm 段返回 null——调用方按空配置处理）。 */
    public static JsonNode readLlmNode(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JsonNode root = YAML.readTree(file.toFile());
            return root == null ? null : root.get("llm");
        } catch (Exception e) {
            throw new PluginException("配置文件解析失败: " + file + "（应为 YAML）", e);
        }
    }

    /**
     * llm 段整块替换写回：非 llm 段字节级保留、llm 段按 patchedLlm 重序列化；临时文件
     * load 全规则校验（env 与运行时同源——env 兜底的部署文件可缺项）过验后原子改名。
     *
     * @throws PluginException 校验失败（provider 非法/models 坏条目/关键项缺失——消息即
     *                         load 点名原文）；原文件不动
     */
    public static void writeLlmSection(Path file, JsonNode patchedLlm) throws IOException {
        String original = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String spliced = spliceLlmSection(original, serializeLlmBlock(patchedLlm));
        // 校验与原子写合一：临时文件 load（env 与运行时同源）——boot 会怎么读就怎么验；
        // 验不过即抛，原文件不动；验过原子改名（不支持原子改名的文件系统回落普通改名）
        // 请求唯一临时名（并发 PUT 不共用固定名——审查轴三：固定名 tmp 有
        // 「未校验内容被并发搬入正文件」竞态窗口）
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        Files.write(tmp, spliced.getBytes(StandardCharsets.UTF_8));
        try {
            LlmConfig.load(tmp, System.getenv());
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp); // 校验抛出/改名失败不留残件
        }
    }

    /** llm 节点重序列化为块文本（剥根分隔符，逐行缩进两格——顶层 llm: 键的子块形态）。 */
    private static String serializeLlmBlock(JsonNode patchedLlm) {
        try {
            String body = YAML.writeValueAsString(patchedLlm);
            if (body.startsWith("---")) {
                body = body.replaceFirst("^---\\s*", "");
            }
            StringBuilder out = new StringBuilder("llm:");
            for (String line : body.split("\n", -1)) {
                if (!line.isBlank()) {
                    out.append("\n  ").append(line);
                }
            }
            return out.toString();
        } catch (Exception e) {
            throw new PluginException("llm 段序列化失败", e);
        }
    }

    /**
     * 文本手术：定位顶层 {@code llm:} 块（列 0 起、到下一个顶层键或文件尾）整块替换；
     * 无 llm 块则追加到文件尾。其余行字节级原样。
     */
    static String spliceLlmSection(String original, String llmBlock) {
        List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("llm:")) {
                start = i;
                break;
            }
        }
        List<String> block = List.of(llmBlock.split("\n", -1));
        if (start < 0) {
            // 无 llm 块：追加（前有空行分隔，防粘连既有尾行）
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.addAll(block);
        } else {
            int end = start + 1;
            while (end < lines.size() && (lines.get(end).isBlank() || Character.isWhitespace(lines.get(end).charAt(0)))) {
                end++;
            }
            lines.subList(start, end).clear();
            lines.addAll(start, block);
        }
        return String.join("\n", lines);
    }
}

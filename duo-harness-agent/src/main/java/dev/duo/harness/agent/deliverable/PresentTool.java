package dev.duo.harness.agent.deliverable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * present 工具（M26 工单 04，ADR-0028；DSH 同款词汇）：任务收尾时模型主动声明
 * 交付物——单次上报 1-8 个成果文件路径，工具先校验文件真实存在（相对路径按
 * 工作目录解析），通过后落 {@code deliverable/presented} 会话事件（可回放、入
 * 检索索引、进导出报告交付清单章节）。
 *
 * <p>校验失败的文件逐条点名、整次拒绝（不落事件）——成果文件清单只收全数验证过的
 * 路径，宁缺毋假（DSH 对照：失败粒度研究文档未载，duo 自选整拒并记档）。路径合法性
 * 以「normalize 后为常规文件」为判据——形态非法（InvalidPathException）由工具管线
 * 容错边界收敛为通用错误，不逐条点名。DSH 要求 open turn 内调用，duo 无 turn 状态
 * 面向工具暴露——有意放宽，收尾时机靠 description 引导（记档）。声明是 harness 内部
 * 数据（不写文件系统），不经 workspace 三档不声明审批（ADR-0022 决策 10 同款）。
 * 独占工具（不覆写 isConcurrencySafe，fail-closed 默认）——声明落会话日志，并行写
 * 无意义。</p>
 */
public final class PresentTool implements ToolDefinition {

    /** 工具名（模型侧调用名，DSH 同款）。 */
    public static final String NAME = "present";

    /** 单次申报上限（对齐 DSH present 形态）。 */
    static final int MAX_FILES = 8;

    private final Supplier<Session> currentSession;
    private final Path cwd;

    /**
     * @param currentSession 当前会话供给（换绑后留新会话，与交互/todo 工具同模式）
     * @param cwd            工作目录（相对路径的解析基准；生产装配传进程工作目录，
     *                       与会话落盘 cwd 同源）
     */
    public PresentTool(Supplier<Session> currentSession, Path cwd) {
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
        this.cwd = Objects.requireNonNull(cwd, "cwd");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "声明本轮任务的交付文件。任务完成、成果文件已就绪时调用：传入 1-8 个"
                + "交付文件的路径（相对当前工作目录或绝对路径），系统校验文件真实存在后"
                + "记入会话的成果文件清单（导出报告与历史检索都能按它找到本次成果）。只声明"
                + "真正交付给用户的成果文件——草稿、中间产物、临时文件不要声明。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "files":{"type":"array","items":{"type":"string"},
                        "minItems":1,"maxItems":8,
                        "description":"交付文件路径列表（1-8 个；相对当前工作目录或绝对路径）"}},
                      "required":["files"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("present 参数 schema 内置错误", e);
        }
    }

    @Override
    public String execute(ToolExecution exec) {
        Session session = currentSession.get();
        if (session == null) {
            return error("present 需要一个归属会话（当前无活跃会话）");
        }
        JsonNode files = exec.args().path("files");
        if (!files.isArray() || files.isEmpty()) {
            return error("参数 files 必须是非空数组（1-8 个交付文件路径）");
        }
        if (files.size() > MAX_FILES) {
            return error("单次最多声明 " + MAX_FILES + " 个文件（收到 " + files.size() + " 个）");
        }
        // 校验 + 规范化：trim 非空、相对路径按 cwd 解析、必须真实存在的常规文件；
        // 失败逐条收集一次点名（成果文件清单只收全数验证过的路径），去重保序
        Set<String> seen = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode item : files) {
            if (item.isNull()) {
                failures.add("（null 元素——应为路径字符串）");
                continue;
            }
            String raw = item.asText("").strip();
            if (raw.isEmpty()) {
                failures.add("（空路径）");
                continue;
            }
            Path resolved = Path.of(raw);
            if (!resolved.isAbsolute()) {
                resolved = cwd.resolve(raw);
            }
            resolved = resolved.normalize(); // ./x 与 x 同文件同形态——去重键与落盘清单统一
            if (!seen.add(resolved.toString())) {
                continue; // 重复路径静默去重（同一文件报两次无歧义）
            }
            if (!Files.isRegularFile(resolved)) {
                failures.add(raw + "（文件不存在或不是常规文件）");
            }
        }
        if (!failures.isEmpty()) {
            return error("以下文件校验未通过，本次声明未生效（修正后重新调用）:\n- "
                    + String.join("\n- ", failures));
        }
        var payload = JsonNodeFactory.instance.arrayNode();
        for (String path : seen) {
            payload.add(path);
        }
        String filesJson = payload.toString();
        session.append(SessionEvent.deliverablePresented(filesJson));
        return "交付声明已记录（" + seen.size() + " 个文件）:\n- " + String.join("\n- ", seen);
    }

    private static String error(String msg) {
        return "[present 错误] " + msg;
    }
}

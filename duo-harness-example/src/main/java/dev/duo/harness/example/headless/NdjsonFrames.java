package dev.duo.harness.example.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NDJSON 帧构建与 bounding 降级链（M23 工单 07，ADR-0025）：headless --json 的
 * stdout 逐行 JSON 事件流。词汇七类——session/status/thinking/text/tool_call/
 * tool_result/error/final（grill Q9 全量；thinking 当前装配无独立思考事件源，
 * 有源才发帧，同 DSH「缺样本宁缺勿假」纪律）。
 *
 * <p>bounding（对齐 DSH json-stream 探测契约）：单个字符串值超 {@link #MAX_STRING_CHARS}
 * 截断并附 {@code truncated:true}；序列化后整行（含换行转义）超 {@link #MAX_LINE_CHARS}
 * 降级为 {@code {type, truncated}} 两字段帧。{@code final} 帧承载无损答案，调用方
 * 经 {@link #finalFrame} 豁免 bounding——消费者必须容忍中间帧降级、final 永不截断。</p>
 */
public final class NdjsonFrames {

    /** 单字符串值上限（字符）。 */
    public static final int MAX_STRING_CHARS = 8 * 1024;

    /** 单行上限（序列化后字符，含换行转义）。 */
    public static final int MAX_LINE_CHARS = 32 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NdjsonFrames() {
    }

    /** 有序单字段对构造（链式补字段用 fields(k1,v1) 后 put(k2,v2)）。 */
    public static LinkedHashMap<String, Object> fields(String key, Object value) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * 构建一帧并施加 bounding 降级链（中间帧用）。
     *
     * @param type   帧类型（七类词汇之一）
     * @param fields 有序字段（Jackson 序列化按插入序，消费者读到稳定列序）
     * @return 单行 JSON（无换行符，调用方负责补行分隔）
     */
    public static String frame(String type, LinkedHashMap<String, Object> fields) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        boolean truncated = false;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > MAX_STRING_CHARS) {
                node.put(entry.getKey(), s.substring(0, MAX_STRING_CHARS));
                truncated = true;
            } else {
                node.set(entry.getKey(), MAPPER.valueToTree(value));
            }
        }
        if (truncated) {
            node.put("truncated", true);
        }
        String line = toJson(node);
        if (line.length() > MAX_LINE_CHARS) {
            // 行级降级：丢弃全部载荷，只留类型与截断标记（消费者按降级帧跳过）
            ObjectNode degraded = MAPPER.createObjectNode();
            degraded.put("type", type);
            degraded.put("truncated", true);
            return toJson(degraded);
        }
        return line;
    }

    /**
     * final 帧（无损豁免）：最终答案不截断、不降级——自动化消费者以本帧为答案锚点。
     */
    public static String finalFrame(String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "final");
        node.put("text", text);
        return toJson(node);
    }

    /** 会话开场帧（先写后订阅：消费者以本帧锚定 sessionId 后再解析事件帧）。 */
    public static String sessionFrame(String sessionId, String cwd) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "session");
        node.put("sessionId", sessionId);
        node.put("cwd", cwd);
        return toJson(node);
    }

    private static String toJson(ObjectNode node) {
        return node.toString(); // JsonNode.toString 转义换行——单行契约由序列化器保证
    }
}

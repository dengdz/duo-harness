package dev.duo.harness.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;

/**
 * 记忆写入工具（M25 工单 03，写路径模型侧唯一入口）：追加一条记忆进项目记忆本。
 *
 * <p>形态裁定（探测工单 10 + grill 裁定三）：专用工具而非指令约定——append 语义
 * 在服务端保证"用户手改与模型写不互相吞"（模型没有整文件覆写能力面），工具调用
 * 天然落 tool/call + tool/result 会话事件（可回放可审计），description 承载写协议
 * （指令形态，BUG-20260925-02 经验）。</p>
 *
 * <p>治理面：不要求审批（仅追加用户显式要求记的记忆本条目，不触其他文件——与
 * workspace 区内写免审同档）；独占工具（append 为读-补-写三步非原子，并发安全
 * 声明 false 走 ADR-0018 屏障串行化，杜绝并行补换行互踩）。plan 态不在白名单——
 * 计划模式不可写记忆（M24 工单 04 默认）。</p>
 */
public final class MemoryWriteTool implements ToolDefinition {

    /** 工具名（模型侧调用名）。 */
    public static final String NAME = "memory_write";

    /** 单条记忆长度上限（字符）：条目协议是"一句话一条"，
     * 硬上限防超长条目撑爆注入预算。 */
    public static final int MAX_CONTENT_CHARS = 2000;

    private final MemoryBook memory;

    /** @param memory 记忆本（写目标） */
    public MemoryWriteTool(MemoryBook memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把一条信息写入项目记忆本（.duo/MEMORY.md，跨会话持久、"
                + "每次请求自动注入）。当用户让你\"记住 X\"、交代需要跨会话延续的"
                + "偏好/约定/事实时调用；"
                + "每次追加一条，已记过的内容不重复写。"
                + "用户要修改或删除记忆时，告知其直接编辑 .duo/MEMORY.md（每行一条）。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "content":{"type":"string","description":"要记住的内容（一条完整自足的事实，如'用户偏好中文回复'）"}},
                     "required":["content"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("memory_write 工具参数 schema 内置错误", e);
        }
    }

    @Override
    public boolean isConcurrencySafe(JsonNode args) {
        return false;
    }

    @Override
    public String execute(ToolExecution execution) throws java.io.IOException {
        JsonNode contentNode = execution.args().get("content");
        if (contentNode == null || contentNode.isNull() || contentNode.asText().isBlank()) {
            throw new PluginException("memory_write 缺少必填参数 content（要记住的内容）");
        }
        String content = contentNode.asText().strip();
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new PluginException("memory_write 单条记忆超长（" + content.length()
                    + " > " + MAX_CONTENT_CHARS + " 字符）——拆成多条分别写入或精简为一句话");
        }
        memory.append(content);
        return "已记入记忆本：" + content + "（当前共 " + memory.entryCount() + " 条，"
                + "之后每轮请求自动注入，新会话同样可见）";
    }
}

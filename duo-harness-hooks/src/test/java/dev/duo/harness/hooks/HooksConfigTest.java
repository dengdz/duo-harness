package dev.duo.harness.hooks;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hooks 配置解析用例（纯单元，不上缝）：两家同形（Claude Code matcher 组 / Codex
 * 扁平处理器）、未知顶层键宽容、不支持事件跳过点名、条目级问题跳过、结构错误点名
 * 文件抛 PluginException（插件 FAILED 面）。
 */
class HooksConfigTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HooksConfigTest —— hooks.json 解析：两家同形、宽容跳过、"
                + "结构错误点名（7 用例） ===");
    }

    @TempDir
    Path tempDir;

    private Path write(String content) throws Exception {
        Path file = tempDir.resolve("hooks-" + System.nanoTime() + ".json");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void claudeCodeMatcherGroupShapeParses() throws Exception {
        HooksConfig config = HooksConfig.load(write("""
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "Bash",
                        "hooks": [
                          {"type": "command", "command": "echo hi", "args": ["--x"], "timeout": 30}
                        ]
                      }
                    ]
                  }
                }
                """));
        assertEquals(List.of(), config.skippedEvents());
        List<HookRule> rules = config.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE);
        assertEquals(1, rules.size());
        assertEquals("Bash", rules.get(0).matcher());
        assertEquals(1, rules.get(0).handlers().size());
        HookHandler handler = rules.get(0).handlers().get(0);
        assertEquals("echo hi", handler.command());
        assertEquals(List.of("--x"), handler.args());
        assertEquals(Duration.ofSeconds(30), handler.timeout());
    }

    @Test
    void codexFlatShapeParsesSingleHandler() throws Exception {
        // Codex 形态：事件下条目直接是 command 处理器（无 matcher 组、无 hooks 数组）
        HooksConfig config = HooksConfig.load(write("""
                {"hooks": {"PreToolUse": [{"command": "node guard.js --pre"}]}}
                """));
        HookRule rule = config.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE).get(0);
        assertEquals(1, rule.handlers().size());
        assertEquals("node guard.js --pre", rule.handlers().get(0).command());
        assertEquals(HookHandler.DEFAULT_TIMEOUT, rule.handlers().get(0).timeout());
    }

    @Test
    void unknownTopLevelKeysIgnored() throws Exception {
        // Claude Code settings.json 整文件粘贴兼容：permissions 等未知键宽容忽略
        HooksConfig config = HooksConfig.load(write("""
                {
                  "permissions": {"allow": ["Bash"]},
                  "hooks": {"PreToolUse": [{"matcher": "*", "hooks": [{"type": "command", "command": "true"}]}]}
                }
                """));
        assertEquals(1, config.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE).size());
    }

    @Test
    void unsupportedEventsSkippedAndNamed() throws Exception {
        HooksConfig config = HooksConfig.load(write("""
                {"hooks": {"SessionStart": [{"command": "true"}], "PreToolUse": [{"command": "true"}]}}
                """));
        assertEquals(List.of("SessionStart"), config.skippedEvents());
        assertEquals(1, config.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE).size());
    }

    @Test
    void entryLevelProblemsSkippedNotFatal() throws Exception {
        // 处理器非 command、缺 command、timeout 非法：条目级跳过（WARN），不炸插件
        HooksConfig config = HooksConfig.load(write("""
                {
                  "hooks": {
                    "PreToolUse": [
                      {"matcher": "*", "hooks": [
                        {"type": "prompt", "command": "x"},
                        {"type": "command"},
                        {"type": "command", "command": "true", "timeout": -3}
                      ]}
                    ]
                  }
                }
                """));
        List<HookRule> rules = config.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE);
        assertEquals(1, rules.size());
        assertEquals(0, rules.get(0).handlers().size(), "三条均有条目级问题，全部跳过");
    }

    @Test
    void structuralFailuresThrowNamingFile() throws Exception {
        Path bad = write("{not json");
        PluginException e1 = assertThrows(PluginException.class, () -> HooksConfig.load(bad));
        assertTrue(e1.getMessage().contains("hooks 配置解析失败") && e1.getMessage().contains(bad.toString()),
                e1.getMessage());

        Path badHooks = write("{\"hooks\": []}");
        PluginException e2 = assertThrows(PluginException.class, () -> HooksConfig.load(badHooks));
        assertTrue(e2.getMessage().contains("\"hooks\" 键须为对象"), e2.getMessage());

        Path badEvent = write("{\"hooks\": {\"PreToolUse\": {}}}");
        PluginException e3 = assertThrows(PluginException.class, () -> HooksConfig.load(badEvent));
        assertTrue(e3.getMessage().contains("值须为数组"), e3.getMessage());
    }

    @Test
    void missingOrBlankFileIsEmptyConfig() throws Exception {
        assertTrue(HooksConfig.load(tempDir.resolve("nope.json")).isEmpty());
        assertTrue(HooksConfig.load(write("   ")).isEmpty());
    }

    @Test
    void invalidRegexMatcherSkippedAtParseTime() throws Exception {
        // 非精确形态的 matcher 按正则校验：非法正则条目级跳过（WARN），不留死规则
        HooksConfig config = HooksConfig.load(write("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "([unclosed", "hooks": [{"type": "command", "command": "true"}]},
                  {"matcher": "^probe", "hooks": [{"type": "command", "command": "true"}]}
                ]}}
                """));
        List<HookRule> rules = config.rulesFor(HooksConfig.EVENT_PRE_TOOL_USE);
        assertEquals(1, rules.size(), "非法正则条目跳过、合法条目保留");
        assertEquals("^probe", rules.get(0).matcher());
    }

    @Test
    void postToolUseIsSupportedEvent() throws Exception {
        // PostToolUse 已入受支持集（工单 04）：不再进 skippedEvents
        HooksConfig config = HooksConfig.load(write("""
                {"hooks": {"PostToolUse": [{"command": "true"}]}}
                """));
        assertTrue(config.skippedEvents().isEmpty(), () -> "PostToolUse 不应被跳过: " + config.skippedEvents());
        assertEquals(1, config.rulesFor(HooksConfig.EVENT_POST_TOOL_USE).size());
    }
}

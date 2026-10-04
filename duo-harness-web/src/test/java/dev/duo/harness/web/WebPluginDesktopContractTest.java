package dev.duo.harness.web;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.DuoHome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M37 工单 01 桌面契约面回归锁（ADR-0039 决策六）：桌面壳与后端的两处对接面——
 * 端口三级覆盖（sysprop duo.web.port > env DUO_WEB_PORT > 装配 config.port）与
 * stdout 机器锚点行 duo:web-ready。锚点是壳拉起后端的唯一契约面（S1）与端口
 * 注入口的优先级语义（S2），后端改动无此锁即静默断链。
 */
class WebPluginDesktopContractTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebPluginDesktopContractTest —— 桌面契约面：端口三级覆盖 + stdout 机器锚点行（M37-01） ===");
    }

    // ---------- S2：端口三级覆盖（DuoHome 同构：sysprop > env > 装配值） ----------

    @Test
    void portFallsBackToConfigThenDefaultWithoutOverrides() {
        // 无覆盖：装配值优先于缺省；无装配段：缺省 8080（CLI/Web 直跑行为不变）
        assertEquals(9999, WebPlugin.resolvePort(cfg(9999), null, null), "无覆盖回落装配 port");
        assertEquals(WebPlugin.DEFAULT_PORT, WebPlugin.resolvePort(null, null, null), "无装配段回落缺省 8080");
    }

    @Test
    void envOverridesConfig() {
        // 桌面壳注入口：环境变量覆盖装配值
        assertEquals(7788, WebPlugin.resolvePort(cfg(9999), null, "7788"), "env 覆盖装配 port");
    }

    @Test
    void syspropOverridesEnvAndConfig() {
        // 测试注入口最高优先：sysprop > env > 装配值
        assertEquals(6600, WebPlugin.resolvePort(cfg(9999), "6600", "7788"), "sysprop 最高优先");
        assertEquals(7788, WebPlugin.resolvePort(cfg(9999), "  ", "7788"), "sysprop 空白等价未设，env 生效");
    }

    @Test
    void invalidEnvFallsBackToConfigWithNotice() {
        // 非法值（非数字/越界/负数）回落装配值并点名，不启动失败（ADR-0039 决策六）
        assertEquals(9999, WebPlugin.resolvePort(cfg(9999), null, "abc"), "非数字回落装配值");
        assertEquals(9999, WebPlugin.resolvePort(cfg(9999), null, "70000"), "越界回落装配值");
        assertEquals(9999, WebPlugin.resolvePort(cfg(9999), null, "-1"), "负数回落装配值");
        assertEquals(9999, WebPlugin.resolvePort(cfg(9999), "65536", null), "sysprop 越界同回落");
    }

    @Test
    void invalidSyspropFallsThroughToEnv() {
        // sysprop 非法回落 env（逐级回落，不跳级）
        assertEquals(7788, WebPlugin.resolvePort(cfg(9999), "xyz", "7788"), "sysprop 非法回落 env");
    }

    @Test
    void zeroPortHonoredAsRandomSemantics() {
        // port 0（随机分配）经覆盖口合法——壳选具体端口，0 保留既有测试语义；上界 65535 同合法
        assertEquals(0, WebPlugin.resolvePort(cfg(9999), null, "0"), "env 0 = 随机分配语义保留");
        assertEquals(65535, WebPlugin.resolvePort(cfg(9999), null, "65535"), "合法上界放行");
    }

    @Test
    void productionEntrypointReadsSystemProperty() {
        // 生产入口真读系统属性（装配路径的接线锁：重载漏接 System.getProperty 即此锁红）
        String old = System.getProperty(WebPlugin.PORT_PROP_OVERRIDE);
        System.setProperty(WebPlugin.PORT_PROP_OVERRIDE, "6601");
        try {
            assertEquals(6601, WebPlugin.resolvePort(cfg(9999)), "生产入口读 sysprop 覆盖");
        } finally {
            if (old == null) {
                System.clearProperty(WebPlugin.PORT_PROP_OVERRIDE);
            } else {
                System.setProperty(WebPlugin.PORT_PROP_OVERRIDE, old);
            }
        }
    }

    // ---------- S1：stdout 机器锚点行（壳的唯一对接面） ----------

    @Test
    void startupPrintsMachineReadyAnchorWithTokenUrl(@TempDir Path tempDir) throws Exception {
        // 拉真实装配（纯 Web 插件树）断言：锚点行存在、URL 含 token 可解析、端口与实际绑定一致；
        // 人读文案原样保留（1.x 兼容承诺，缺人读行 = 兼容破坏）
        Path home = tempDir.resolve("duo-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), """
                llm:
                  baseUrl: https://placeholder.local
                  apiKey: test-key
                  model: test-model
                """);
        String oldHome = System.getProperty(DuoHome.PROP_OVERRIDE);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        java.io.PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            Context root = Context.root();
            JsonNodeFactory factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.prompt.PromptPlugin(), factory.objectNode()).awaitStartup();
            root.plugin(new dev.duo.harness.tools.InteractionPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(), factory.objectNode()).awaitStartup();
            WebPlugin web = new WebPlugin();
            root.plugin(web, factory.objectNode().put("port", 0)).awaitStartup();
            try {
                int actualPort = web.face().port();
                String expectedToken = web.face().authToken();
                String stdout = captured.toString(StandardCharsets.UTF_8);
                Matcher anchor = Pattern.compile("duo:web-ready url=(\\S+)").matcher(stdout);
                assertTrue(anchor.find(), "stdout 应含 duo:web-ready 机器锚点行:\n" + stdout);
                String url = anchor.group(1);
                String expectedUrl = "http://127.0.0.1:" + actualPort + "/?token=" + expectedToken;
                assertEquals(expectedUrl, url, "锚点 URL = 实际绑定端口 + token 查询参数");
                assertNotNull(java.net.URI.create(url).getQuery(), "锚点 URL 含查询参数（token 可解析）");
                assertTrue(stdout.contains("Web 面已启动（鉴权开启）"), "人读文案原样保留（1.x 兼容锁）");
            } finally {
                root.dispose();
            }
        } finally {
            System.setOut(originalOut);
            if (oldHome == null) {
                System.clearProperty(DuoHome.PROP_OVERRIDE);
            } else {
                System.setProperty(DuoHome.PROP_OVERRIDE, oldHome);
            }
        }
    }

    @Test
    void readyUrlOmitsTokenQueryWhenAuthDisabled() {
        // auth: none 形态：锚点 URL 无 token 查询段（壳直连裸 URL）
        assertEquals("http://127.0.0.1:8080", WebPlugin.webReadyUrl(8080, null),
                "auth none 无查询参数");
        assertEquals("http://127.0.0.1:8080/?token=abc", WebPlugin.webReadyUrl(8080, "abc"),
                "auth token 带 token 查询参数");
    }

    @Test
    void statusExposesTurnActiveForDesktopQuitProbe(@TempDir Path tempDir) throws Exception {
        // 工单 04 退出探活契约：/api/status 载荷含 turnActive 布尔（进程级在飞 send
        // 计数）——桌面壳 before-quit 据此判「后端还有 agent 在跑」；idle 装配为 false
        Path home = tempDir.resolve("duo-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), """
                llm:
                  baseUrl: https://placeholder.local
                  apiKey: test-key
                  model: test-model
                """);
        String oldHome = System.getProperty(DuoHome.PROP_OVERRIDE);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        try {
            Context root = Context.root();
            JsonNodeFactory factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.prompt.PromptPlugin(), factory.objectNode()).awaitStartup();
            root.plugin(new dev.duo.harness.tools.InteractionPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(), factory.objectNode()).awaitStartup();
            WebPlugin web = new WebPlugin();
            root.plugin(web, factory.objectNode().put("port", 0)).awaitStartup();
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + web.face().port()
                                + "/api/status?token=" + web.face().authToken()))
                        .GET().build();
                java.net.http.HttpResponse<String> res =
                        client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, res.statusCode(), "状态端点 200");
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(res.body());
                assertTrue(json.has("turnActive") && json.path("turnActive").isBoolean(),
                        "turnActive 布尔字段在载荷（桌面壳退出探活契约）: " + res.body());
                assertFalse(json.path("turnActive").asBoolean(), "idle 装配 turnActive=false");
            } finally {
                root.dispose();
            }
        } finally {
            if (oldHome == null) {
                System.clearProperty(DuoHome.PROP_OVERRIDE);
            } else {
                System.setProperty(DuoHome.PROP_OVERRIDE, oldHome);
            }
        }
    }

    private static ObjectNode cfg(int port) {
        return JsonNodeFactory.instance.objectNode().put("port", port);
    }
}

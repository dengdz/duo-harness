package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmConfigFile;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M38 工单 02 回归锁：/api/llm-config 端点与 config.yml 写回——GET 文件面（apiKey
 * 零回显）、PUT 结构化写回（非 llm 段字节级保留/校验不过原文件不动/apiKey 空=保留/
 * 鉴权 fail-closed）。effort 不在写回面（load 显式声明「写 yml 亦被忽略」）。
 */
class WebLlmConfigEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN = "test-token-abcdef";
    private static final String FIXTURE = """
            # 用户注释行（llm 块外应字节保留）
            llm:
              baseUrl: https://api.original.com
              apiKey: original-key-123
              model: orig-model
              models:
              - orig-model
            other:
              keep: true
            """;

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebLlmConfigEndpointTest —— llm-config 端点：GET 遮蔽/PUT 写回/校验/鉴权（M38-02） ===");
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
        System.clearProperty(DuoHome.PROP_OVERRIDE); // JVM 级属性清理（同模块先例）
    }

    interface ToolsView {

        ToolsService tools();
    }

    private Path writeFixture() throws Exception {
        Path home = tempDir.resolve("duo-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), FIXTURE, StandardCharsets.UTF_8);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        return home.resolve("config.yml");
    }

    private WebFace start() throws Exception {
        tempDir.resolve("out").toFile().mkdirs();
        Session session = Session.create(tempDir.resolve("sessions"));
        Context ctx = Context.root();
        ctx.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        dev.duo.harness.agent.ChatAgent stub = new dev.duo.harness.agent.ChatAgent() {
            @Override
            public dev.duo.harness.agent.AgentReply send(String userText,
                                                         dev.duo.harness.agent.AgentListener listener) {
                return new dev.duo.harness.agent.AgentReply("好的", java.util.List.of(), true);
            }
        };
        face = WebFace.start(0, ctx, ctx.as(ToolsView.class).tools(), session, stub,
                null, null, tempDir.resolve("out"), 50, null, () -> false, null, TOKEN);
        return face;
    }

    private HttpResponse<String> send(WebFace f, String method, String body, boolean withToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + f.port() + "/api/llm-config"));
        if (withToken) {
            builder.header("X-Duo-Token", TOKEN);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getReturnsFileFaceWithoutKeyMaterial() throws Exception {
        Path configFile = writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "GET", null, true);
        assertEquals(200, res.statusCode());
        JsonNode json = MAPPER.readTree(res.body());
        assertEquals("https://api.original.com", json.path("baseUrl").asText());
        assertEquals("orig-model", json.path("model").asText());
        assertTrue(json.path("apiKeySet").asBoolean(), "已配置布尔在");
        assertFalse(res.body().contains("original-key-123"), "apiKey 键值本体零回显");
        assertEquals("orig-model", json.path("models").get(0).asText(), "白名单在列");
    }

    @Test
    void getAndPutRequireTokenFailClosed() throws Exception {
        writeFixture();
        WebFace f = start();
        assertEquals(403, send(f, "GET", null, false).statusCode(), "GET 无 token 403");
        assertEquals(403, send(f, "PUT", "{\"baseUrl\":\"https://x\"}", false).statusCode(),
                "PUT 无 token 403（fail-closed）");
    }

    @Test
    void putRewritesLlmSectionPreservingRest() throws Exception {
        Path configFile = writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "PUT", """
                {"baseUrl": "https://api.new.com", "apiKey": "new-key-456",
                 "model": "orig-model", "models": ["orig-model", "new-model"]}""", true);
        assertEquals(200, res.statusCode());
        assertTrue(MAPPER.readTree(res.body()).path("restartRequired").asBoolean(),
                "重启生效语义在响应");
        JsonNode llm = LlmConfigFile.readLlmNode(configFile);
        assertEquals("https://api.new.com", llm.path("baseUrl").asText());
        assertEquals("new-key-456", llm.path("apiKey").asText());
        assertEquals(2, llm.path("models").size(), "白名单整组替换");
        // 非 llm 段字节级保留
        String after = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(after.contains("# 用户注释行（llm 块外应字节保留）"), "llm 块外注释保留");
        assertTrue(after.contains("keep: true"), "other 段保留");
        assertTrue(after.contains("other:"), "other 键保留");
        // 写回结果 boot 可装载（load 全规则过验已在 writeLlmSection 内执行）
        assertEquals("https://api.new.com", LlmConfig.load(configFile, java.util.Map.of()).baseUrl());
    }

    @Test
    void putInvalidProviderRejectedAndFileUntouched() throws Exception {
        Path configFile = writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "PUT", "{\"provider\": \"foo-provider\"}", true);
        assertEquals(400, res.statusCode(), "provider 越界 400 点名");
        assertTrue(res.body().contains("provider"), res.body());
        // 原文件不动
        JsonNode llm = LlmConfigFile.readLlmNode(configFile);
        assertEquals("https://api.original.com", llm.path("baseUrl").asText());
        assertEquals("original-key-123", llm.path("apiKey").asText());
        assertFalse(Files.exists(configFile.resolveSibling("config.yml.tmp")), "无临时残件");
    }

    @Test
    void putBlankApiKeyKeepsOriginalValue() throws Exception {
        Path configFile = writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "PUT",
                "{\"model\": \"orig-model\", \"apiKey\": \"\"}", true);
        assertEquals(200, res.statusCode());
        JsonNode llm = LlmConfigFile.readLlmNode(configFile);
        assertEquals("original-key-123", llm.path("apiKey").asText(), "apiKey 空 = 保留原值");
    }

    @Test
    void putUnmanagedFieldRejectedNotSilentlyDropped() throws Exception {
        Path configFile = writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "PUT", "{\"effort\": \"high\"}", true);
        assertEquals(400, res.statusCode(), "白名单外字段点名（永不静默——静默丢弃会让调用方误以为已写入）");
        assertTrue(res.body().contains("effort"), res.body());
        JsonNode llm = LlmConfigFile.readLlmNode(configFile);
        assertFalse(llm.has("effort"), "原文件未注入 effort");
    }

    @Test
    void putMalformedJsonReturns400Not500() throws Exception {
        writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "PUT", "{not-json", true);
        assertEquals(400, res.statusCode(), "畸形 JSON 就近 400（客户端错误不进 500 日志）");
        assertTrue(res.body().contains("error"), res.body());
    }

    @Test
    void putBadModelsEntryRejected() throws Exception {
        Path configFile = writeFixture();
        WebFace f = start();
        HttpResponse<String> res = send(f, "PUT", "{\"models\": [\"ok\", \"\"]}", true);
        assertEquals(400, res.statusCode(), "坏条目 400（写回校验点名）");
        JsonNode llm = LlmConfigFile.readLlmNode(configFile);
        assertEquals(1, llm.path("models").size(), "原文件不动");
    }
}

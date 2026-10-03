package dev.duo.harness.center;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.web.WebFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 S3（Web HTTP 缝）用例：插件中心 Web 桥（M35 工单 06）——桥随装配自动
 * 挂接（optionalInject webRoutes）、rows/scan/inspect/install/disable 端到端、
 * 业务点名 400（与 500 兜底分离）、鉴权栅栏照常覆盖贡献端点。
 */
class PluginCenterWebBridgeTest {

    private static final String FIXTURE_FQCN = "dev.duo.harness.center.CenterFixturePlugin";
    private static final String TOKEN = "0123456789abcdef-test-token";

    @TempDir
    Path tempHome;

    private WebFace face;
    private Context treeCtx;
    private final HttpClient client = HttpClient.newHttpClient();
    private Path fixtureJar;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PluginCenterWebBridgeTest —— 中心 Web 桥：随装配自动挂接、"
                + "装/停端到端、业务点名 400、鉴权覆盖（4 用例） ===");
    }

    interface ToolsView {
        ToolsService tools();
    }

    interface PluginCenterView {
        PluginCenter pluginCenter();
    }

    interface BoxView {
        String boxMarker();
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
        System.clearProperty(DuoHome.PROP_OVERRIDE);
    }

    /** 装配：真实树（tools → WebFace 起服发布贡献口 → 中心行后挂——optionalInject
     * 指纹驱动的装载顺序与 Web 部署一致）+ 带令牌 WebFace。 */
    private WebFace start() throws Exception {
        System.setProperty(DuoHome.PROP_OVERRIDE, tempHome.toString());
        Files.writeString(tempHome.resolve("plugins.yml"), """
                plugins:
                  - id: greeter
                    name: dev.duo.harness.tools.ToolsPlugin
                """);
        fixtureJar = buildFixtureJar();

        Context ctx = Context.root();
        treeCtx = ctx;
        // 模拟 boot 装载前发布行级控制口（BootLoader 同款时机）
        ctx.provide(PluginRows.SERVICE_NAME, PluginRows.of(ctx));
        ctx.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = ctx.as(ToolsView.class).tools();
        tools.register(ctx, new ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回声工具";
            }

            @Override
            public JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public String execute(ToolExecution execution) {
                return "echo";
            }
        });
        Session session = Session.create(tempDir().resolve("sessions"));
        ChatAgent agent = (userText, listener) -> new AgentReply("ok", List.of(), true);
        face = WebFace.start(0, ctx, tools, session, agent, null, null,
                tempDir().resolve("web-sessions"), 50, null, null, null, TOKEN);
        // WebPlugin 同款发布点：face 起服即发布端点贡献口
        ctx.provide(dev.duo.harness.web.WebRouteRegistry.SERVICE_NAME, face.routes());
        // 中心行后于贡献口在场——optionalInject 指纹驱动自动激活（Web 部署的真实形态）
        ctx.plugin(new PluginCenterPlugin(), null).awaitStartup();
        return face;
    }

    @TempDir
    Path scratch;

    private Path tempDir() {
        return scratch;
    }

    private Path buildFixtureJar() throws IOException {
        Path pluginsDir = tempHome.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("fixture.jar");
        byte[] classBytes;
        try (InputStream in = CenterFixturePlugin.class.getResourceAsStream("CenterFixturePlugin.class")) {
            classBytes = in.readAllBytes();
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("dev/duo/harness/center/CenterFixturePlugin.class"));
            out.write(classBytes);
            out.closeEntry();
            out.putNextEntry(new JarEntry("marker.txt"));
            out.write("来自插件包".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private HttpResponse<String> get(WebFace target, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + path))
                .header("X-Duo-Token", TOKEN).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(WebFace target, String path, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + path))
                .header("X-Duo-Token", TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // === 用例 ===

    @Test
    void bridgeMountsAutomaticallyAndRowsEndpointServes() throws Exception {
        WebFace tokenFace = start();

        HttpResponse<String> rows = get(tokenFace, "/plugins/center/api/rows");
        assertEquals(200, rows.statusCode());
        assertTrue(rows.body().contains("greeter"), "桥应随装配挂接且状态含装配行: " + rows.body());
        // 服务面在册（编程挂载的中心行不经行登记——管理对象是 yml 行与 rows.load 行）
        assertNotNull(treeCtx.as(PluginCenterView.class).pluginCenter());
    }

    @Test
    void installThenDisableViaEndpointsEndToEnd() throws Exception {
        WebFace tokenFace = start();

        HttpResponse<String> install = post(tokenFace, "/plugins/center/api/install", """
                {"jar": "%s", "id": "box", "entryFqcn": "%s",
                 "config": {"serviceName": "boxMarker", "markerFile": "marker.txt"}}
                """.formatted(fixtureJar.toAbsolutePath(), FIXTURE_FQCN));
        assertEquals(200, install.statusCode(), install.body());
        assertEquals("来自插件包", treeCtx.as(BoxView.class).boxMarker());

        HttpResponse<String> disable = post(tokenFace, "/plugins/center/api/disable", """
                {"id": "box"}
                """);
        assertEquals(200, disable.statusCode(), disable.body());
        assertTrue(disable.body().contains("box"));
    }

    @Test
    void businessNamingResponds400NotBareClose() throws Exception {
        WebFace tokenFace = start();

        // 业务点名（缺参数/缺包文件）→ 400 + 消息 JSON；路由层 500 兜底留给真异常
        HttpResponse<String> noParam = get(tokenFace, "/plugins/center/api/inspect");
        assertEquals(400, noParam.statusCode());
        assertTrue(noParam.body().contains("error"), noParam.body());

        HttpResponse<String> badJar = get(tokenFace, "/plugins/center/api/inspect?jar="
                + tempHome.resolve("不存在.jar"));
        assertEquals(400, badJar.statusCode());
        assertTrue(badJar.body().contains("不存在"), badJar.body());
    }

    @Test
    void contributedCenterEndpointsRequireToken() throws Exception {
        WebFace tokenFace = start();

        // 鉴权栅栏照常覆盖贡献端点（fail-closed 403）
        HttpRequest noToken = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + tokenFace.port() + "/plugins/center/api/rows")).GET().build();
        HttpResponse<String> response = client.send(noToken, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }
}

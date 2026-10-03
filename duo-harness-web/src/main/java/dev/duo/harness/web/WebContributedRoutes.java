package dev.duo.harness.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link WebRouteRegistry} 实现：前缀占用表 + 每前缀已挂路径清单。挂接统一走
 * {@link WebEndpoints#routeHandler}（入口栅栏 + 500 兜底与内部端点同一条铁律）；
 * 摘除经 {@link HttpServer#removeContext}（无匹配请求由 JDK 自动 404）。
 * synchronized 串行化申请/挂接/摘除——操作者驱动的低频动作，正确性优先。
 */
final class WebContributedRoutes implements WebRouteRegistry {

    private final HttpServer server;
    private final WebEntryGate gate;
    /** 已占用前缀 → 已挂完整路径清单（摘除用）。 */
    private final Map<String, List<String>> claimed = new HashMap<>();

    WebContributedRoutes(HttpServer server, WebEntryGate gate) {
        this.server = server;
        this.gate = gate;
    }

    @Override
    public synchronized Disposable claim(String prefix) {
        requireValidPrefix(prefix);
        if (claimed.containsKey(prefix)) {
            throw new PluginException("命名空间前缀 /plugins/" + prefix + "/ 已被占用"
                    + "（先申请方在场，重复申请点名拒绝）");
        }
        claimed.put(prefix, new ArrayList<>());
        return () -> unmountAll(prefix);
    }

    @Override
    public synchronized void mount(String prefix, String relativePath, HttpHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (!claimed.containsKey(prefix)) {
            throw new PluginException("前缀 /plugins/" + prefix + "/ 未申请（先 claim 再 mount）");
        }
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/")) {
            throw new PluginException("相对路径须为非空且不带前导 / 的路径段: " + relativePath);
        }
        String fullPath = "/plugins/" + prefix + "/" + relativePath;
        WebEndpoints.routeHandler(server, fullPath, gate, handler);
        claimed.get(prefix).add(fullPath);
    }

    /** 摘除本前缀全部路由；幂等（未占用前缀静默通过——移除器重复执行安全）。 */
    synchronized void unmountAll(String prefix) {
        List<String> mounted = claimed.remove(prefix);
        if (mounted == null) {
            return;
        }
        for (String fullPath : mounted) {
            try {
                server.removeContext(fullPath);
            } catch (IllegalArgumentException e) {
                WebFace.log.warn("贡献路由摘除失败（可能已移除）: {}", fullPath, e);
            }
        }
    }

    /** 前缀合法性：非空路径段，仅字母/数字/-/_（拒绝 /、空白与 . 段——防路径逃逸）。 */
    private static void requireValidPrefix(String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_-]+")) {
            throw new PluginException("命名空间前缀须为非空路径段（字母/数字/-/_）: " + prefix);
        }
    }
}

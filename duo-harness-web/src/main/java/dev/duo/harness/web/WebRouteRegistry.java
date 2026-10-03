package dev.duo.harness.web;

import com.sun.net.httpserver.HttpHandler;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;

/**
 * Web 端点命名空间贡献口（ADR-0037 内核受控口二）：插件按<b>前缀申请制</b>在
 * {@code /plugins/<前缀>/**} 下挂接 HTTP 端点。三条铁律与内部端点同一条：
 * ①贡献端点一律过入口栅栏（鉴权 fail-closed；{@code auth: none} 行为一致）；
 * ②处理器异常一律 500 + 日志，绝不连接裸关（BUG-20261002-01 铁律）；
 * ③前缀占用即点名拒绝（对齐服务同名互斥口径）。
 *
 * <p>获取：经服务 {@value #SERVICE_NAME}（WebPlugin 发布；视图接口方法名与
 * 服务名逐字一致）。提供方拔除即端点摘除——把 {@link #claim} 返回的移除器
 * 挂到本插件作用域（{@code ctx.effect}），作用域回滚时路由同步 404。</p>
 *
 * <p>边界：仅 HTTP 端点通道——静态资源服务、前端页面结构与 SSE 枢纽仍在
 * 内核独占面（呈现层开放的后续台阶见 ADR-0037 决策四）。</p>
 */
public interface WebRouteRegistry {

    /** 服务名（WebPlugin 发布；消费方视图接口方法名与此逐字一致）。 */
    String SERVICE_NAME = "webRoutes";

    /**
     * 申请命名空间前缀：{@code /plugins/<prefix>/…}。占用即点名拒绝；prefix
     * 须为合法路径段（字母/数字/-/_，不含 / 与 . 段）。
     *
     * @return 幂等移除器——执行即摘除本前缀全部已挂路由（同前缀可重新申请）
     */
    Disposable claim(String prefix);

    /**
     * 在已申请前缀下挂接端点：{@code relativePath} 相对前缀（非空、不带前导
     * {@code /}），完整路径 {@code /plugins/<prefix>/<relativePath>}。未申请
     * 前缀先 mount 点名拒绝。
     */
    void mount(String prefix, String relativePath, HttpHandler handler);
}

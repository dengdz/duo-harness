package dev.duo.harness.tools.web;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SSRF 三道防线之①②与③的校验件（M20，ADR-0021 决策 4）：
 *
 * <ul>
 *   <li>① URL 字面预检——仅 http/https、拒绝内嵌账密、长度 ≤2048；</li>
 *   <li>② DNS 解析后校验——非字面量主机名取<b>全部解析地址</b>逐一公网判定，
 *       任一非公网整体拒绝（防部分记录投毒）；IP 字面量直接分类；</li>
 *   <li>③ 重定向逐跳校验——每次跳转重走①②并强制同源（scheme+host+port）。</li>
 * </ul>
 *
 * <p><b>连接 pinning 不做</b>（JDK HttpClient 无连接层 DNS 定制点）："校验后到连接前"
 * 的重解析窗口（TOCTOU）为已知限制，如实记 limitations——真利用需攻击者控制权威 DNS
 * 且毫秒级翻转，个人工具威胁模型下偏理论（M22 沙箱复审再议）。</p>
 *
 * <p>公网判定覆盖：回环/RFC1918/链路本地/组播/任意本地（JDK 内建）+ CGNAT 100.64/10、
 * 保留段 240/4 与 0/8、文档段（TEST-NET、2001:db8::/32）、IPv6 ULA fc00::/7（JDK 不判）。</p>
 *
 * <p>解析器构造可注入——测试注入假 resolver，零真 DNS。</p>
 */
final class UrlGuard {

    /** 解析函数抽象（生产 = 系统解析全地址集；测试 = 假 resolver）。 */
    @FunctionalInterface
    interface Resolver {

        List<InetAddress> resolve(String host) throws IOException;
    }

    static final int MAX_URL_LENGTH = 2048;

    private final Resolver resolver;

    UrlGuard(Resolver resolver) {
        this.resolver = resolver;
    }

    /** 生产实例：系统解析器（getAllByName 全地址集，无排序承诺）。 */
    static UrlGuard withSystemResolver() {
        return new UrlGuard(host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    /** 校验拒绝（消息面向模型，带自纠指引；工具层转结构化错误）。 */
    static final class RejectedException extends RuntimeException {

        RejectedException(String message) {
            super(message);
        }
    }

    /** ①+②：字面预检 + DNS 全地址集公网校验，返回可直接请求的 URI。 */
    URI check(String raw) {
        URI uri = validateLiteral(raw);
        verifyResolvable(uri);
        return uri;
    }

    /**
     * ③：重定向目标校验——Location 按当前地址解析相对路径，重走字面预检 + 同源
     * 强制 + DNS 校验。跨源拒绝并指引模型直接抓取。
     */
    URI checkRedirect(URI current, String location) {
        URI next;
        try {
            URI resolved = URI.create(location.strip());
            next = current.resolve(resolved).normalize();
        } catch (IllegalArgumentException e) {
            throw new RejectedException("重定向 Location 无效: " + location);
        }
        validateLiteral(next.toString());
        requireSameOrigin(current, next);
        verifyResolvable(next);
        return next;
    }

    /** ①：字面预检（scheme / 内嵌账密 / 长度 / 可解析形态）。 */
    private URI validateLiteral(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RejectedException("URL 不能为空");
        }
        if (raw.length() > MAX_URL_LENGTH) {
            throw new RejectedException("URL 超长（>" + MAX_URL_LENGTH + " 字符）");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new RejectedException("URL 无效: " + raw);
        }
        if (uri.getHost() == null) {
            throw new RejectedException("URL 缺主机名（须为含主机的绝对 http(s) 地址）: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RejectedException("仅支持 http/https，实际 scheme: " + (scheme.isEmpty() ? "（缺）" : scheme));
        }
        if (uri.getUserInfo() != null) {
            throw new RejectedException("URL 内嵌用户名/密码被拒绝——请改用无凭据地址或 header 方式");
        }
        return uri;
    }

    /** ②：DNS 全地址集公网校验——任一非公网整体拒绝（防部分记录投毒）。 */
    private void verifyResolvable(URI uri) {
        String host = uri.getHost();
        for (InetAddress address : resolveAll(host)) {
            if (!isPublic(address)) {
                throw new RejectedException("目标解析到非公网地址（" + address.getHostAddress()
                        + "，内网/回环/保留段），已拒绝——若确为公网服务请核对该域名");
            }
        }
    }

    private List<InetAddress> resolveAll(String host) {
        // IP 字面量直接分类（getByName 对字面量不做 DNS）
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            try {
                return List.of(InetAddress.getByName(host));
            } catch (IOException e) {
                throw new RejectedException("IP 字面量无效: " + host);
            }
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (IOException e) {
            throw new RejectedException("DNS 解析失败: " + host + "（" + e.getMessage() + "）");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new RejectedException("DNS 解析无结果: " + host);
        }
        return addresses;
    }

    /** 同源强制：scheme + host + port 全等（缺省端口按 scheme 归一）。 */
    private void requireSameOrigin(URI current, URI next) {
        if (!origin(current).equals(origin(next))) {
            throw new RejectedException("重定向跨源被拒绝（" + origin(current) + " → " + origin(next)
                    + "）——如需该来源内容请直接抓取其 URL");
        }
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** 公网判定：非回环/私网/链路本地/组播/保留/文档段/ULA 才算公网。 */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress() || address.isSiteLocalAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            if (b[0] == 0 || b[0] == 127 || (b[0] & 0xff) >= 240) {
                return false; // 0/8、127/8（与回环判定双保险）、240/4 保留（含 255.255.255.255）
            }
            if (b[0] == (byte) 198 && (b[1] & 0xff) >= 18 && (b[1] & 0xff) <= 19) {
                return false; // 198.18.0.0/15（RFC 2544 基准测试保留段）
            }
            if (b[0] == 100 && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127) {
                return false; // CGNAT 100.64/10
            }
            if (b[0] == (byte) 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2)) {
                return false; // 192.0.0.0/24（IETF 协议）与 192.0.2.0/24（TEST-NET-1）
            }
            if (b[0] == (byte) 198 && b[1] == 51 && b[2] == 100) {
                return false; // 198.51.100.0/24（TEST-NET-2）
            }
            if (b[0] == (byte) 203 && b[1] == 0 && b[2] == 113) {
                return false; // 203.0.113.0/24（TEST-NET-3）
            }
            return true;
        }
        // IPv6：ULA fc00::/7 与文档段 2001:db8::/32（JDK 不判这两段）
        if ((b[0] & 0xfe) == 0xfc) {
            return false;
        }
        return !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8);
    }
}

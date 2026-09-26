package dev.duo.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * 会话文件格式版本（M26 工单 01，ADR-0028 决策二）：JSONL 首行版本头与相邻迁移链。
 *
 * <p><b>版本头</b>：新会话文件首行为 {@code {"type":"session","version":N,"cwd":"…"}}
 * （cwd 缺省不写）。旧文件无头即 v0——v0 语义就是现有格式，直读不迁移；磁盘文件
 * 永不重写（append-only 与审计承诺不受格式演进影响）。读取侧按首行有无分流：
 * {@link Session#load} 解析头行后其余行才是事件。</p>
 *
 * <p><b>迁移链</b>：读端认出 {@code version < 当前} 时，按 vN→vN+1 相邻接力在内存
 * 转换事件行（隔代逐级），磁盘不动。链上每个迁移器只负责一步；缺环即失败——
 * 不存在"跳级迁移"。头行由框架按迁移目标重写（仅内存表示），迁移器只管事件行。
 * 本期当前版本为 1，链为空：机制建好备用，首个真实迁移出现在 v2 格式变更时。</p>
 */
public final class SessionFormat {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 当前 harness 支持的会话文件格式版本（读端能接受的最高版本）。 */
    public static final int CURRENT_VERSION = 1;

    /** 版本头行的 type 值（非事件类型——头行不是 {@link SessionEvent}）。 */
    private static final String HEADER_TYPE = "session";

    /** 头行 JSON 字段名（序列化与解析同源引用，不做字面量重复）。 */
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_CWD = "cwd";

    private SessionFormat() {
    }

    /** 版本头：格式版本 + 会话工作目录（cwd 为会话元信息，检索授权过滤的依据，ADR-0028 决策三）。 */
    public record Header(int version, Path cwd) {
    }

    /** 序列化版本头行（cwd 为 null 时省略字段——旧签名创建的会话不携带目录）。 */
    public static String headerLine(int version, Path cwd) {
        var node = JSON.createObjectNode();
        node.put(FIELD_TYPE, HEADER_TYPE);
        node.put(FIELD_VERSION, version);
        if (cwd != null) {
            node.put(FIELD_CWD, cwd.toString());
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new PluginException("版本头序列化失败", e);
        }
    }

    /** 解析版本头行（须已经 {@link #isHeaderLine} 判定）。 */
    public static Header parseHeader(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            int version = node.path(FIELD_VERSION).asInt(0);
            JsonNode cwdNode = node.get(FIELD_CWD);
            Path cwd = cwdNode == null || cwdNode.isNull() ? null : Path.of(cwdNode.asText());
            return new Header(version, cwd);
        } catch (IOException e) {
            throw new PluginException("版本头解析失败: " + line, e);
        }
    }

    /**
     * 该行是否为版本头行：type 为 {@code session} 即是。静态读者（标题/权限档/检索
     * 扫描/子会话端点）按事件 type 过滤，头行天然不命中——本判定供逐行 parse 前的
     * 分流（load）与防重复头使用。解析失败的行按非头处理（坏行语义归 parse）。
     */
    public static boolean isHeaderLine(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            return HEADER_TYPE.equals(node.path(FIELD_TYPE).asText());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 相邻迁移链的一步：把 fromVersion 的事件行整体转换为 toVersion 形态。
     * 只声明相邻一步（toVersion = fromVersion + 1），隔代升级由 {@link #migrate}
     * 逐级接力完成。迁移只发生在内存，磁盘文件永不重写。
     */
    public interface LineMigration {

        int fromVersion();

        int toVersion();

        /** 转换事件行序列（头行不在其中，由 {@link #migrate} 按目标版本重写）。 */
        List<String> migrate(List<String> eventLines);
    }

    /**
     * 沿迁移链接力把事件行从 fromVersion 升到 targetVersion：逐步找
     * {@code fromVersion == v} 的迁移器，缺环即抛异常（链必须完整无跳级）；
     * 迁移后的事件行由调用方按 targetVersion 解析。
     *
     * @throws PluginException 链断裂（缺 fromVersion 起步的迁移器）或方向异常
     */
    public static List<String> migrate(int fromVersion, List<String> eventLines,
                                       List<LineMigration> chain, int targetVersion) {
        if (fromVersion >= targetVersion) {
            throw new PluginException("迁移方向异常: from=" + fromVersion + ", target=" + targetVersion);
        }
        List<String> lines = new ArrayList<>(eventLines);
        int v = fromVersion;
        while (v < targetVersion) {
            LineMigration step = null;
            for (LineMigration m : chain) {
                if (m.fromVersion() == v) {
                    step = m;
                    break;
                }
            }
            if (step == null) {
                throw new PluginException("迁移链断裂: 不存在 v" + v + " 的迁移器（目标 v" + targetVersion + "）");
            }
            if (step.toVersion() != v + 1) {
                throw new PluginException("迁移器必须相邻: v" + v + " → v" + step.toVersion());
            }
            lines = new ArrayList<>(step.migrate(lines));
            v = step.toVersion();
        }
        return lines;
    }

    /** 迁移器基本校验（实现类构造时调用——错误前移到构造点；包私有——本期链为空）。 */
    static void requireValidMigration(int from, int to) {
        if (to != from + 1) {
            throw new IllegalArgumentException("迁移器必须相邻且升一版: v" + from + " → v" + to);
        }
    }
}

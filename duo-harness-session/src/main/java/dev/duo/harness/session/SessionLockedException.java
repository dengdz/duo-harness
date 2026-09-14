package dev.duo.harness.session;

import dev.duo.harness.core.api.PluginException;

import java.nio.file.Path;

/**
 * 会话被占用：打开时未能取得独占锁——同一 JSONL 文件已由本进程的另一实例
 * 或其他进程打开。
 *
 * <p>会话是单写者数据基座（内存事件列表 + JSONL 追加），两个进程各持一份内存
 * 视图同时写会静默分脑：日志交错追加、双方上下文分叉且互不可见。此异常把该
 * 状态从"静默损坏"变为"打开即失败"，报错点即用户决策点（关掉占用方或改用其他会话）。</p>
 */
public class SessionLockedException extends PluginException {

    private final String sessionId;

    public SessionLockedException(String sessionId, Path jsonl) {
        this(sessionId, jsonl, null);
    }

    public SessionLockedException(String sessionId, Path jsonl, Throwable cause) {
        super(describe(sessionId, jsonl), cause);
        this.sessionId = sessionId;
    }

    /** 被占用的会话 id（装配层给出针对性提示用）。 */
    public String sessionId() {
        return sessionId;
    }

    /** 简短描述（不含路径）：给响应体等不该回显内部路径的展示位用。 */
    public String brief() {
        return "会话已被占用：" + sessionId;
    }

    private static String describe(String sessionId, Path jsonl) {
        return "会话已被占用：" + sessionId + "（" + jsonl + "）——"
                + "同一会话同一时刻只允许一个进程使用，请先关闭占用它的程序";
    }
}

package dev.duo.harness.core.api.boot;

import java.nio.file.Path;

/**
 * 进程工作目录（cwd）单一取值源（M28 工单 08，H-15）：全仓 cwd 取值收敛此一处——
 * 「可配 cwd」这类未来变化只改本类。原样返回进程启动目录，不做规范化（行为与
 * 逐处 {@code Path.of(System.getProperty("user.dir"))} 逐字一致）。
 */
public final class Cwd {

    private Cwd() {
    }

    /** 进程工作目录（cwd 路径形态——会话落盘、路径解析等消费方）。 */
    public static Path path() {
        return Path.of(System.getProperty("user.dir"));
    }

    /** 进程工作目录（字符串形态——frame/载荷/文本拼装消费方）。 */
    public static String text() {
        return System.getProperty("user.dir");
    }
}

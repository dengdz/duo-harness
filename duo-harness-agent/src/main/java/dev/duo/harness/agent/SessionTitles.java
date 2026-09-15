package dev.duo.harness.agent;

import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话标题生成器（精简版，工单 M13-06 / ADR-0013）：首条 user/message 落日志后
 * 异步生成一次标题，以 {@code session/title} 事件落会话日志（投影 latest-wins，
 * {@link Session#title()} 读取）。生成是锦上添花：独立直答小请求（无工具、单段聚合、
 * 限时），失败/超时降级为首条消息前 {@value #MAX_TITLE_CHARS} 字截断；每会话路径
 * 至多触发一次（跨实例静态去重——双开装配同源不重复生成），不重生成、不可改名。
 *
 * <p>输出约束落在提示词（单行纯文本、跟随消息语言、长度上限）与落日志前的截断
 * 双侧——provider 侧无输出上限契约可传，超长输出靠截断兜底。</p>
 */
public final class SessionTitles {

    /** 标题最大字符数（落日志前截断，降级截断同口径）。 */
    public static final int MAX_TITLE_CHARS = 20;

    /** 生成超时（秒）：标题不值得等，超时即降级，不拖会话。 */
    static final long TIMEOUT_SECONDS = 20;

    static final String TITLE_PROMPT =
            "为下面的用户消息生成一个会话标题：不超过 " + MAX_TITLE_CHARS + " 字的单行纯文本，"
                    + "使用与消息相同的语言，只输出标题本身，不加引号、句号或任何解释。";

    /** 跨实例去重表（会话绝对路径 → 已触发）：in-flight 与完成合一，常驻不清理——本地会话数量级小。 */
    private static final java.util.concurrent.ConcurrentMap<Path, Boolean> TRIGGERED =
            new ConcurrentHashMap<>();

    /** 标题直答执行器（虚拟线程按任务建，限时取消不占平台线程）。 */
    private static final ExecutorService TITLE_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-title-", 0).factory());

    private SessionTitles() {
    }

    /**
     * 给会话挂标题生成监听（CLI/Web 同源装配）：仅当首条事件（下标 0）是 user/message
     * 时触发一次——续接的历史会话（首条早已落盘、追加下标 > 0）天然不再触发。
     *
     * @param session 目标会话（监听随会话实例生命周期；换绑后的新会话需另行 attach）
     * @param llm     直答 adapter（与对话执行链同源）
     */
    public static void attach(Session session, LlmAdapter llm) {
        attach(session, llm, TIMEOUT_SECONDS);
    }

    /** 超时可注入版（包级：测试限时验证降级路径；生产走缺省超时）。 */
    static void attach(Session session, LlmAdapter llm, long timeoutSeconds) {
        session.addListener((index, event) -> {
            if (index != 0 || !SessionEvent.USER_MESSAGE.equals(event.type())) {
                return;
            }
            Path key = session.jsonl().toAbsolutePath().normalize();
            if (TRIGGERED.putIfAbsent(key, Boolean.TRUE) != null) {
                return; // 本会话已触发过（含 in-flight）：去重
            }
            String firstMessage = event.text();
            Thread.ofVirtual().name("session-title-watch-" + session.id())
                    .start(() -> generate(session, llm, firstMessage, timeoutSeconds));
        });
    }

    /** 限时生成：成功落 LLM 标题（截断），失败/超时降级首条截断——两者都落 title 事件。 */
    private static void generate(Session session, LlmAdapter llm, String firstMessage, long timeoutSeconds) {
        Future<String> future = TITLE_EXECUTOR.submit(() -> requestTitle(llm, firstMessage));
        String title;
        try {
            title = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            title = null;
            System.out.println("[会话标题] 生成超时，降级截断（会话 " + session.id() + "）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            title = null;
        } catch (ExecutionException e) {
            title = null;
            System.out.println("[会话标题] 生成失败，降级截断: " + e.getCause());
        }
        appendTitle(session, truncate(title != null ? title : firstMessage));
    }

    /** 直答请求：无工具单消息，聚合全部增量。 */
    private static String requestTitle(LlmAdapter llm, String firstMessage) {
        ChatRequest request = new ChatRequest(TITLE_PROMPT,
                List.of(new ChatMessage(ChatMessage.Role.USER, firstMessage, null, null, null)),
                List.of());
        StringBuilder out = new StringBuilder();
        llm.stream(request, (ChatChunk chunk) -> out.append(chunk.text()));
        return out.toString();
    }

    private static void appendTitle(Session session, String title) {
        try {
            session.append(SessionEvent.title(title));
        } catch (RuntimeException e) {
            // 会话已关闭（生成期间用户退出）或落盘失败：标题是锦上添花，放弃并留痕
            System.out.println("[会话标题] 落日志失败（会话 " + session.id() + "）: " + e.getMessage());
        }
    }

    private static String truncate(String text) {
        String stripped = text.strip();
        return stripped.length() <= MAX_TITLE_CHARS ? stripped : stripped.substring(0, MAX_TITLE_CHARS);
    }
}

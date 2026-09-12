package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交互服务用例（ADR-0008 的 seam 机制核）：回答者注册与作用域摘除、
 * 询问按注册序遍历（放弃作答权交下一个）、无人在场或全部放弃 fail-closed。
 */
class InteractionRegistryTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：InteractionRegistryTest —— 交互服务：回答者注册与作用域摘除、"
                + "注册序遍历（null 交下一个）、无人在场或全部放弃 fail-closed（5 用例） ===");
    }

    /** 服务视图接口（方法名即服务名 "answers"）。 */
    interface AnswersView {

        InteractionService answers();
    }

    private Context root;

    @BeforeEach
    void setUp() {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
    }

    @AfterEach
    void tearDown() {
        root.dispose();
    }

    private InteractionService answers() {
        return root.as(AnswersView.class).answers();
    }

    /** 恒放行回答者（署名 test）。 */
    private static Answerer allowAll() {
        return request -> InteractionAnswer.allow("test");
    }

    @Test
    void askReachesRegisteredAnswererAndReturnsItsAnswer() {
        AtomicReference<InteractionRequest> seen = new AtomicReference<>();
        InteractionAnswer given = InteractionAnswer.allow("test");
        answers().register(root, request -> {
            seen.set(request);
            return given;
        });

        InteractionRequest request = InteractionRequest.approval("write_file", "{\"path\":\"a.txt\"}");
        InteractionAnswer answer = answers().ask(request);

        assertSame(given, answer, "回答者的回答原样返回");
        assertEquals(InteractionRequest.KIND_APPROVAL, seen.get().kind());
        assertEquals("write_file", seen.get().subject(), "请求原样送达回答者");
    }

    @Test
    void noAnswererFailsClosed() {
        InteractionAnswer answer = answers().ask(InteractionRequest.question("用哪个方案？", List.of("A", "B"), false));

        assertFalse(answer.approved(), "无人在场按拒绝处理");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }

    @Test
    void nullAnswerFallsThroughToNextAnswerer() {
        answers().register(root, request -> null);
        answers().register(root, request -> InteractionAnswer.allow("second"));

        InteractionAnswer answer = answers().ask(InteractionRequest.approval("echo", "{}"));

        assertTrue(answer.approved());
        assertEquals("second", answer.source(), "首个放弃作答权，第二个胜出");
    }

    @Test
    void allDecliningFailsClosed() {
        answers().register(root, request -> null);
        answers().register(root, request -> null);

        InteractionAnswer answer = answers().ask(InteractionRequest.approval("echo", "{}"));

        assertFalse(answer.approved());
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }

    @Test
    void registrantScopeDisposeRemovesAnswerer() throws Exception {
        AtomicReference<Context> scope = new AtomicReference<>();
        root.plugin(new Plugin<Void>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                scope.set(ctx);
                answers().register(ctx, request -> InteractionAnswer.allow("scoped"));
                return () -> { };
            }
        }, null).awaitStartup();

        assertTrue(answers().ask(InteractionRequest.approval("echo", "{}")).approved(), "插件在场时回答放行");

        scope.get().dispose();
        InteractionAnswer answer = answers().ask(InteractionRequest.approval("echo", "{}"));
        assertFalse(answer.approved(), "插件停止后回答者随作用域摘除");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }

    @Test
    void manualRemovalDisposesAnswerer() throws Exception {
        Disposable removal = answers().register(root, allowAll());
        removal.dispose();

        InteractionAnswer answer = answers().ask(InteractionRequest.approval("echo", "{}"));
        assertFalse(answer.approved(), "手动注销后回答者不再在场");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }
}

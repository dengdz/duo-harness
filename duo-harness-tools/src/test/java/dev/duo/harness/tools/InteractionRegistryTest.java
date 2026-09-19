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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交互服务用例（ADR-0008 的 seam 机制核）：回答者注册与作用域摘除、
 * 询问按注册序遍历（放弃作答权交下一个）、无人在场或全部放弃 fail-closed；
 * M19 亲和路由（ADR-0020 决策 7）：请求携发起呈现位标记时发起方回答者优先，
 * 放弃/缺席才轮注册序（组合矩阵风格）。
 */
class InteractionRegistryTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：InteractionRegistryTest —— 交互服务：回答者注册与作用域摘除、"
                + "注册序遍历（null 交下一个）、无人在场或全部放弃 fail-closed、发起方亲和路由矩阵（10 用例） ===");
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

    /** 携呈现位标记的具名回答者（模拟 console / web answerer 的亲和身份）。 */
    private record MarkedAnswerer(String presenterId, Answerer delegate) implements Answerer {

        @Override
        public String presenterId() {
            return presenterId;
        }

        @Override
        public InteractionAnswer answer(InteractionRequest request) {
            return delegate.answer(request);
        }
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
    void presenterAffinityRoutesToOriginAnswererFirst() {
        // 亲和路由（M19，ADR-0020 决策 7）：注册序 web 在前、cli 在后（双开装配的
        // 现实次序）；cli 发起的请求路由给 cli 回答者——"谁发起谁作答"，不再跳 Web
        AtomicReference<InteractionRequest> webSeen = new AtomicReference<>();
        answers().register(root, new MarkedAnswerer("web", r -> {
            webSeen.set(r);
            return InteractionAnswer.allow("web");
        }));
        AtomicReference<InteractionRequest> cliSeen = new AtomicReference<>();
        answers().register(root, new MarkedAnswerer("cli", r -> {
            cliSeen.set(r);
            return InteractionAnswer.allow("console");
        }));

        InteractionAnswer answer = answers().ask(
                InteractionRequest.approval("bash", "{}", "cli"));

        assertEquals("console", answer.source(), "发起方（cli）回答者优先作答");
        assertNull(webSeen.get(), "web 回答者未被询问（终端零提示事故销账）");
        assertEquals("cli", cliSeen.get().presenterId(), "请求携发起方标记送达");
    }

    @Test
    void originDecliningFallsThroughToRegistrationOrder() {
        // 发起方在场但放弃作答权（null）→ 轮注册序全遍历（兜底保留）
        answers().register(root, new MarkedAnswerer("web",
                r -> InteractionAnswer.allow("web")));
        answers().register(root, new MarkedAnswerer("cli", r -> null));

        InteractionAnswer answer = answers().ask(
                InteractionRequest.question("选哪个？", List.of("A"), false, "cli"));

        assertEquals("web", answer.source(), "发起方放弃后注册序兜底");
    }

    @Test
    void originAbsentFollowsRegistrationOrder() {
        // 发起方缺席（无 cli 回答者在场）→ 注册序全遍历
        answers().register(root, new MarkedAnswerer("web",
                r -> InteractionAnswer.allow("web")));

        InteractionAnswer answer = answers().ask(
                InteractionRequest.approval("bash", "{}", "cli"));

        assertEquals("web", answer.source(), "发起方缺席不阻断，注册序兜底");
    }

    @Test
    void requestWithoutMarkerFollowsRegistrationOrder() {
        // 无标记请求（直调 execute 等）走注册序——亲和只在有标记时介入
        AtomicReference<InteractionRequest> webSeen = new AtomicReference<>();
        answers().register(root, new MarkedAnswerer("web", r -> {
            webSeen.set(r);
            return InteractionAnswer.allow("web");
        }));

        InteractionAnswer answer = answers().ask(
                InteractionRequest.approval("bash", "{}"));

        assertEquals("web", answer.source());
        assertNull(webSeen.get().presenterId(), "无标记请求原样送达");
    }

    @Test
    void originDeclinedIsNotAskedTwice() {
        // 让渡语义（审查修复）：发起方放弃作答权（null）后，注册序全遍历不再二次询问发起方
        AtomicInteger cliAsked = new AtomicInteger();
        answers().register(root, new MarkedAnswerer("web",
                r -> InteractionAnswer.allow("web")));
        answers().register(root, new MarkedAnswerer("cli", r -> {
            cliAsked.incrementAndGet();
            return null;
        }));

        InteractionAnswer answer = answers().ask(
                InteractionRequest.approval("bash", "{}", "cli"));

        assertEquals(1, cliAsked.get(), "发起方恰被询问一次");
        assertEquals("web", answer.source(), "兜底命中其余回答者");
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

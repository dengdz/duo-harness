package dev.duo.harness.agent;


import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审计回答者（装饰桥）用例：审批交互前后写 approval/requested 与
 * approval/decided 事件（决定署名回答者来源）；提问类透传不加事件；
 * 委托放弃作答权（null）时原样透传。
 */
class AuditingAnswererTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AuditingAnswererTest —— 审计桥：审批请求/决定事件落会话、"
                + "提问透传、null 透传、计划复核同通道留痕与打回语义（5 用例） ===");
    }

    @TempDir
    Path tempDir;

    @Test
    void approvalInteractionWritesRequestedAndDecidedEvents() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        AuditingAnswerer auditing = new AuditingAnswerer(() -> session,
                request -> InteractionAnswer.allow("console"));

        InteractionAnswer answer = auditing.answer(InteractionRequest.approval("write_file", "{\"path\":\"a.txt\"}"));

        assertEquals(true, answer.approved());
        assertEquals(2, session.events().size(), "请求与决定两个审计事件");
        assertEquals(SessionEvent.APPROVAL_REQUESTED, session.events().get(0).type());
        assertEquals("write_file", session.events().get(0).toolName());
        assertEquals("{\"path\":\"a.txt\"}", session.events().get(0).text());
        assertEquals(SessionEvent.APPROVAL_DECIDED, session.events().get(1).type());
        assertEquals("allow（回答者: console）", session.events().get(1).text());
        // 审计事件不进对话投影
        assertEquals(0, session.deriveMessages().size());
    }

    @Test
    void questionRequestsPassThroughWithoutAuditEvents() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        AuditingAnswerer auditing = new AuditingAnswerer(() -> session,
                request -> InteractionAnswer.answered(List.of("方案 A"), "console"));

        InteractionAnswer answer = auditing.answer(
                InteractionRequest.question("用哪个？", List.of("方案 A"), false));

        assertEquals(List.of("方案 A"), answer.values(), "提问透传给委托者并原样返回");
        assertEquals(0, session.events().size(), "提问由 tool 事件覆盖，审计桥不加事件");
    }

    @Test
    void decliningDelegatePassesThroughNull() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        AuditingAnswerer auditing = new AuditingAnswerer(() -> session, request -> null);

        assertNull(auditing.answer(InteractionRequest.approval("write_file", "{}")),
                "委托者放弃作答权时原样透传 null");
        assertEquals(1, session.events().size(), "仅 requested 事件（无决定可记）");
        assertEquals(SessionEvent.APPROVAL_REQUESTED, session.events().get(0).type());
    }

    @Test
    void planRequestsAreAuditedLikeApprovals() throws IOException {
        // BUG-20260917-04 回归锁：计划复核（KIND_PLAN）与审批同通道留痕——双呈现位
        // 部署下浏览器计划卡的数据源；subject=工具名是前端渲染计划卡形态的身份键
        Session session = Session.create(tempDir.resolve("sessions"));
        AuditingAnswerer auditing = new AuditingAnswerer(() -> session,
                request -> InteractionAnswer.answered(List.of("批准，开始执行"), "web"));

        InteractionAnswer answer = auditing.answer(InteractionRequest.plan(
                "exit_plan_mode", "# 计划：两步早餐清单", List.of("批准，开始执行")));

        assertEquals(true, answer.approved());
        assertEquals(2, session.events().size(), "请求与决定两个审计事件");
        assertEquals(SessionEvent.APPROVAL_REQUESTED, session.events().get(0).type());
        assertEquals("exit_plan_mode", session.events().get(0).toolName(), "身份键供呈现位渲染计划卡");
        assertEquals("# 计划：两步早餐清单", session.events().get(0).text(), "计划全文作事件载荷");
        assertEquals(SessionEvent.APPROVAL_DECIDED, session.events().get(1).type());
        assertEquals("allow（回答者: web）", session.events().get(1).text(), "命中批准项记 allow");
    }

    @Test
    void planRetypeIsRecordedAsDeny() throws IOException {
        // 打回的决定语义：计划回答的 approved 恒真（answered），批准与否看值是否命中
        // 批准项——旧实现按 approved 写 allow，卡冻结为"✓ 计划已获批准"（错判）
        Session session = Session.create(tempDir.resolve("sessions"));
        AuditingAnswerer auditing = new AuditingAnswerer(() -> session,
                request -> InteractionAnswer.answered(List.of("把第二步拆细"), "web"));

        auditing.answer(InteractionRequest.plan(
                "exit_plan_mode", "# 计划草稿", List.of("批准，开始执行")));

        assertEquals(SessionEvent.APPROVAL_DECIDED, session.events().get(1).type());
        assertEquals("deny（回答者: web）", session.events().get(1).text(), "反馈文本（非批准项）记 deny");
    }
}

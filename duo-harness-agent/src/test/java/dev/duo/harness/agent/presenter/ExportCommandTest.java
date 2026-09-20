package dev.duo.harness.agent.presenter;

import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.agent.commands.CommandDefinition;
import dev.duo.harness.agent.commands.CommandEnv;
import dev.duo.harness.agent.commands.CommandOutcome;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /export 命令（M21 工单 09）：CLI 写盘 / Web 下载 URL 分流、非法参数点名、
 * busySafe、command/run+done 审计白得。
 */
class ExportCommandTest {

    @TempDir
    Path tempDir;

    private Context root;
    private Session session;
    private final CommandsRegistry commands = new CommandsRegistry();

    @BeforeEach
    void mount() throws Exception {
        root = Context.root();
        session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("导出我"));
    }

    @AfterEach
    void unmount() throws Exception {
        session.close();
        root.dispose();
    }

    private CommandEnv env(CommandScope scope) {
        return new CommandEnv(scope, () -> session, s -> { }, () -> { },
                () -> false);
    }

    @Test
    void cliWritesFileToExportDir() {
        PresenterAssembly.registerExportCommand(root, commands, tempDir);
        CommandOutcome outcome = commands.dispatch("/export", env(CommandScope.CLI), null);
        assertTrue(outcome.isCommand());
        String text = outcome.text();
        assertTrue(text.startsWith("已导出: "));
        Path written = Path.of(text.substring("已导出: ".length()));
        assertTrue(Files.exists(written));
        assertTrue(written.getFileName().toString().equals("duo-session-" + session.id() + ".md"));
        assertFalse(written.getFileName().toString().endsWith(".jsonl"));
    }

    @Test
    void cliJsonArgumentWritesJsonl() throws Exception {
        PresenterAssembly.registerExportCommand(root, commands, tempDir);
        CommandOutcome outcome = commands.dispatch("/export json", env(CommandScope.CLI), null);
        assertTrue(outcome.isCommand());
        Path written = Path.of(outcome.text().substring("已导出: ".length()));
        assertTrue(written.getFileName().toString().endsWith(".jsonl"));
        List<String> lines = Files.readAllLines(written, StandardCharsets.UTF_8);
        // 导出执行在 command/run 落盘之后——导出含消息与本次命令自己的 run 事件（done 尚未落）
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("导出我"));
        assertTrue(lines.get(1).contains("command/run"));
    }

    @Test
    void webReturnsDownloadEndpointUrl() {
        PresenterAssembly.registerExportCommand(root, commands, tempDir);
        CommandOutcome outcome = commands.dispatch("/export markdown", env(CommandScope.WEB), null);
        assertTrue(outcome.isCommand());
        assertEquals("/api/session/export?format=markdown", outcome.text());
        // Web 分流不落盘
        try (var list = Files.list(tempDir)) {
            assertTrue(list.filter(p -> p.getFileName().toString().startsWith("duo-session-"))
                    .findFirst().isEmpty());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void invalidFormatIsNamedLoudly() {
        PresenterAssembly.registerExportCommand(root, commands, tempDir);
        CommandOutcome outcome = commands.dispatch("/export xml", env(CommandScope.CLI), null);
        assertTrue(outcome.isCommand());
        assertTrue(outcome.text().contains("[/export 错误]"));
        assertTrue(outcome.text().contains("xml"));
    }

    @Test
    void defaultArgumentIsMarkdown() {
        PresenterAssembly.registerExportCommand(root, commands, tempDir);
        CommandOutcome outcome = commands.dispatch("/export", env(CommandScope.WEB), null);
        assertEquals("/api/session/export?format=markdown", outcome.text()); // 缺省 markdown
    }

    @Test
    void busySafeAndAuditEvents() throws Exception {
        PresenterAssembly.registerExportCommand(root, commands, tempDir);
        CommandDefinition definition = commands.find("export");
        org.junit.jupiter.api.Assertions.assertNotNull(definition);
        assertTrue(definition.busySafe());
        assertTrue(definition.scope().admits(CommandScope.CLI)
                && definition.scope().admits(CommandScope.WEB));

        AtomicBoolean busy = new AtomicBoolean(true); // agent 执行中
        CommandEnv busyEnv = new CommandEnv(CommandScope.CLI, () -> session, s -> { },
                () -> { }, busy::get);
        CommandOutcome outcome = commands.dispatch("/export", busyEnv, null);
        assertTrue(outcome.isCommand()); // busySafe：执行中照常导出

        List<SessionEvent> events = session.events();
        assertEquals(SessionEvent.COMMAND_RUN, events.get(1).type()); // 0 = user/message
        assertEquals(SessionEvent.COMMAND_DONE, events.get(events.size() - 1).type());
    }
}

package dev.duo.harness.agent.fileref;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @file grammar 纯函数测试（M21 工单 07）：token 识别/mention 格式化/目录补齐。 */
class FileMentionGrammarTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：FileMentionGrammarTest —— @file grammar（纯函数） ===");
    }

    @Test
    void 行首at触发() {
        assertEquals("src/Foo.java", FileMentionGrammar.activeToken("@src/Foo.java"));
    }

    @Test
    void 空白后at触发() {
        assertEquals("a.md", FileMentionGrammar.activeToken("看看 @a.md"));
    }

    @Test
    void 邮箱内at不触发() {
        assertNull(FileMentionGrammar.activeToken("联系 user@example.com"));
    }

    @Test
    void 非at返回null() {
        assertNull(FileMentionGrammar.activeToken("普通消息"));
    }

    @Test
    void mention格式化() {
        assertEquals("@src/Foo.java", FileMentionGrammar.formatMention("src/Foo.java", false));
        assertEquals("@src/Foo.java/", FileMentionGrammar.formatMention("src/Foo.java", true));
        assertEquals("@\"my docs/a b.md\"", FileMentionGrammar.formatMention("my docs/a b.md", false));
    }

    @Test
    void 控制字符路径拒绝() {
        assertNull(FileMentionGrammar.formatMention("bad\u0000path", false));
    }
}

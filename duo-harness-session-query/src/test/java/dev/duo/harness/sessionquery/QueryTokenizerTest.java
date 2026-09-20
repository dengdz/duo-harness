package dev.duo.harness.sessionquery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分词规则（英文整词 + 汉字单字 + 其余为分隔符）——索引与查询两侧同口径的根基。 */
class QueryTokenizerTest {

    @Test
    void englishWordsLowercased() {
        assertEquals(List.of("hello", "world"), QueryTokenizer.tokenize("Hello WORLD"));
    }

    @Test
    void cjkCharsAreIndividualTokens() {
        assertEquals(List.of("附", "件", "库"), QueryTokenizer.tokenize("附件库"));
    }

    @Test
    void mixedContent() {
        assertEquals(List.of("sha", "256", "硬", "链", "接"),
                QueryTokenizer.tokenize("SHA-256 硬链接"));
    }

    @Test
    void duplicatesDedupedOrderKept() {
        assertEquals(List.of("附", "件"), QueryTokenizer.tokenize("附件 附件"));
    }

    @Test
    void underscoresAndDigitsAreWordChars() {
        assertEquals(List.of("tool_call", "2026"), QueryTokenizer.tokenize("tool_call 2026"));
    }

    @Test
    void blankAndNullAreEmpty() {
        assertTrue(QueryTokenizer.tokenize("").isEmpty());
        assertTrue(QueryTokenizer.tokenize("   ").isEmpty());
        assertTrue(QueryTokenizer.tokenize(null).isEmpty());
    }

    @Test
    void punctuationIsSeparator() {
        assertEquals(List.of("read"), QueryTokenizer.tokenize("（read），！"));
    }
}

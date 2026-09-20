package dev.duo.harness.tools.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown 转换器纯函数单测（seam ③）：映射规则、深度护栏、表格降级、转义。
 * 零网络，直调 convert。
 */
class HtmlToMarkdownTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HtmlToMarkdownTest —— HTML→Markdown 映射与护栏（纯函数） ===");
    }

    @Test
    void 标题层级映射() {
        String md = HtmlToMarkdown.convert("<h1>大标题</h1><h3>小标题</h3>");
        assertTrue(md.contains("# 大标题"));
        assertTrue(md.contains("### 小标题"));
    }

    @Test
    void 无序与有序列表及嵌套() {
        String md = HtmlToMarkdown.convert(
                "<ul><li>甲</li><li>乙<ol><li>子一</li><li>子二</li></ol></li></ul>");
        assertTrue(md.contains("- 甲"));
        assertTrue(md.contains("- 乙"));
        assertTrue(md.contains("  1. 子一"), "嵌套列表应缩进: " + md);
        assertTrue(md.contains("  2. 子二"));
    }

    @Test
    void 链接与无href退化() {
        String md = HtmlToMarkdown.convert("<p><a href=\"https://a.com\">文档</a>与<a>裸链接</a></p>");
        assertTrue(md.contains("[文档](https://a.com)"));
        assertTrue(md.contains("裸链接"));
        assertFalse(md.contains("](})"));
    }

    @Test
    void 围栏代码块保留换行() {
        String md = HtmlToMarkdown.convert("<pre><code>x = 1\ny = x + 1</code></pre>");
        assertTrue(md.contains("```\nx = 1\ny = x + 1\n```"), "代码块应围栏并保留换行: " + md);
    }

    @Test
    void 行内格式标记() {
        String md = HtmlToMarkdown.convert("<p><strong>粗</strong><em>斜</em><code>码</code></p>");
        assertTrue(md.contains("**粗**"));
        assertTrue(md.contains("*斜*"));
        assertTrue(md.contains("`码`"));
    }

    @Test
    void 表格降级为逐行文本() {
        String md = HtmlToMarkdown.convert(
                "<table><tr><th>名字</th><th>数量</th></tr><tr><td>笔</td><td>3</td></tr></table>");
        assertTrue(md.contains("名字 | 数量"));
        assertTrue(md.contains("笔 | 3"));
        assertFalse(md.contains("|---|"), "不产出 GFM 表格分隔行");
    }

    @Test
    void 图片降级为alt文本() {
        String md = HtmlToMarkdown.convert("<p><img src=\"a.png\" alt=\"标志图\">前文</p>");
        assertTrue(md.contains("标志图"));
        assertFalse(md.contains("!["), "不产出图片语法");
        String silent = HtmlToMarkdown.convert("<p><img src=\"b.png\">后文</p>");
        assertFalse(silent.contains("b.png"), "无 alt 不留图片痕迹: " + silent);
    }

    @Test
    void 引用块加前缀() {
        String md = HtmlToMarkdown.convert("<blockquote><p>引文一行</p></blockquote>");
        assertTrue(md.contains("> 引文一行"));
    }

    @Test
    void 行内强调符号被转义() {
        String md = HtmlToMarkdown.convert("<p>单价 5*3 与 snake_case 名称</p>");
        assertTrue(md.contains("5\\*3"));
        assertTrue(md.contains("snake\\_case"));
    }

    @Test
    void script内容剔除() {
        String md = HtmlToMarkdown.convert("<body><p>正文</p><script>alert(1)</script></body>");
        assertTrue(md.contains("正文"));
        assertFalse(md.contains("alert"));
    }

    @Test
    void 深嵌套返回占位符() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            html.append("<div>");
        }
        html.append("<p>core</p>");
        for (int i = 0; i < 600; i++) {
            html.append("</div>");
        }
        assertEquals(HtmlToMarkdown.UNCONVERTIBLE_PLACEHOLDER, HtmlToMarkdown.convert(html.toString()));
    }

    @Test
    void 空白折叠与块间空行规整() {
        String md = HtmlToMarkdown.convert("<p>第一段</p>\n<p>第二段</p>");
        assertEquals("第一段\n\n第二段", md);
    }
}

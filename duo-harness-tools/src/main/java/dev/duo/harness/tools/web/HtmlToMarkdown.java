package dev.duo.harness.tools.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTML→Markdown 转换器（M20，ADR-0021 决策 5）：jsoup 解析清洗 + 自写轻量映射。
 * 输出面向模型的常用 Markdown 子集——标题/列表/链接/围栏代码块/粗斜体完整映射，
 * 表格降级为逐行文本（DSH 为表格专门写防爆炸规则，恰证其是成本中心）。
 *
 * <p>清洗剔除不可见节点（script/style 等防注入面 + 防 token 噪音）；深度护栏
 * （嵌套超深返回占位，先于转换执行——jsoup 树深度超线性会饿死协作超时）；
 * 转换失败同样返回占位而非源码（垃圾 HTML 不进上下文）。</p>
 */
final class HtmlToMarkdown {

    /** 转换失败/超深时的固定占位（不灌源码，ADR-0021 决策 5）。 */
    static final String UNCONVERTIBLE_PLACEHOLDER = "[HTML content omitted: unable to convert safely.]";
    /** 深度护栏：嵌套超过该层数不转换。 */
    private static final int MAX_DEPTH = 512;

    private HtmlToMarkdown() { }

    /** 转换入口：清洗 → 深度护栏 → 逐块映射为 Markdown 行。 */
    static String convert(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style,noscript,template,iframe,object,embed,"
                + "[hidden],[aria-hidden=true],input[type=hidden]").remove();
        Element body = doc.body();
        if (maxDepth(body, 0) > MAX_DEPTH) {
            return UNCONVERTIBLE_PLACEHOLDER;
        }
        try {
            StringBuilder out = new StringBuilder();
            renderChildren(body, out);
            return normalize(out.toString());
        } catch (Exception e) {
            // 转换失败返回占位而非源码：垃圾 HTML 不进上下文
            return UNCONVERTIBLE_PLACEHOLDER;
        }
    }

    /** 树深探测：超过 MAX_DEPTH 立即返回（递归自身有界）。 */
    private static int maxDepth(Node node, int depth) {
        if (depth > MAX_DEPTH) {
            return depth;
        }
        int max = depth;
        for (Node child : node.childNodes()) {
            max = Math.max(max, maxDepth(child, depth + 1));
            if (max > MAX_DEPTH) {
                return max;
            }
        }
        return max;
    }

    /** 块级渲染：逐子节点分发，块间以空行分隔。 */
    private static void renderChildren(Element parent, StringBuilder out) {
        for (Node child : parent.childNodes()) {
            renderBlock(child, out);
        }
    }

    private static void renderBlock(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            String s = collapse(text.getWholeText());
            if (!s.isEmpty()) {
                out.append("\n\n").append(escapeText(s));
            }
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                String text = inline(el).strip();
                if (!text.isEmpty()) {
                    out.append("\n\n").append("#".repeat(level)).append(' ').append(text);
                }
            }
            case "p" -> emitParagraph(inline(el), out);
            case "pre" -> {
                // wholeText 取未归一化文本：text() 的空白保留只看节点及其一级父，
                // <pre> 内行内元素嵌套更深（如 <span><code>）时换行缩进会被折叠
                String code = el.wholeText();
                if (!code.isBlank()) {
                    out.append("\n\n```\n").append(code.strip().stripTrailing()).append("\n```");
                }
            }
            case "blockquote" -> emitParagraph(quoteLines(inline(el)), out);
            case "ul", "ol" -> renderList(el, 0, out);
            case "table" -> renderTable(el, out);
            case "hr" -> out.append("\n\n---");
            case "br" -> out.append('\n');
            // div/section 等容器块：递归子块（纯文本容器退化为段落由 inline 兜底）
            case "div", "section", "article", "main", "header", "footer", "aside",
                 "nav", "figure", "figcaption", "address", "form", "fieldset" -> renderChildren(el, out);
            case "dl" -> renderChildren(el, out);
            case "dt" -> emitParagraph("**" + inline(el).strip() + "**", out);
            case "dd" -> emitParagraph(inline(el), out);
            default -> {
                // 游离的行内节点/未知标签按段落兜底
                String text = inline(el);
                if (!text.isBlank()) {
                    emitParagraph(text, out);
                }
            }
        }
    }

    /** 列表：ul 用 -，ol 递增编号；li 内嵌套列表缩进下探。 */
    private static void renderList(Element list, int depth, StringBuilder out) {
        boolean ordered = list.tagName().equalsIgnoreCase("ol");
        String indent = "  ".repeat(depth);
        int index = 1;
        for (Element li : list.children()) {
            if (!li.tagName().equalsIgnoreCase("li")) {
                continue;
            }
            String marker = ordered ? (index++) + ". " : "- ";
            StringBuilder itemText = new StringBuilder();
            List<Element> nested = new ArrayList<>();
            for (Node child : li.childNodes()) {
                if (child instanceof Element e
                        && (e.tagName().equalsIgnoreCase("ul") || e.tagName().equalsIgnoreCase("ol"))) {
                    nested.add(e);
                } else {
                    renderInlineInto(child, itemText);
                }
            }
            out.append('\n').append(indent).append(marker).append(itemText.toString().strip());
            for (Element sub : nested) {
                renderList(sub, depth + 1, out);
            }
        }
    }

    /** 表格降级：每行渲染为「单元格 | 单元格」文本行（保行列对应，不做 GFM 表格）。 */
    private static void renderTable(Element table, StringBuilder out) {
        for (Element tr : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            // 单元格内竖线转义：保住「a | b」逐行文本的行列对应不被数据内容破坏
            tr.children().forEach(cell -> cells.add(inline(cell).strip().replace("|", "\\|")));
            if (!cells.isEmpty()) {
                out.append("\n\n").append(String.join(" | ", cells));
            }
        }
    }

    /** 行内渲染：格式标记（粗/斜/行内码/链接/图片 alt）+ 文本转义。 */
    private static String inline(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node child : el.childNodes()) {
            renderInlineInto(child, sb);
        }
        return sb.toString();
    }

    private static void renderInlineInto(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(escapeText(collapse(text.getWholeText())));
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "strong", "b" -> out.append("**").append(inline(el)).append("**");
            case "em", "i" -> out.append('*').append(inline(el)).append('*');
            case "code", "kbd", "samp" -> {
                // 内容含反引号时改用双反引号定界（CommonMark 惯例），防 code span 提前闭合
                String code = el.text();
                out.append(code.indexOf('`') >= 0 ? "`` " + code + " ``" : "`" + code + "`");
            }
            case "a" -> {
                String text = inline(el).strip();
                String href = el.hasAttr("href") ? el.attr("href") : "";
                // href 仅放行 http(s)：与 UrlGuard 口径一致，javascript:/data: 不写进模型上下文
                boolean safeHref = href.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
                        || href.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
                out.append(safeHref && !text.isEmpty() ? "[" + text + "](" + href + ")" : text);
            }
            case "img" -> {
                String alt = el.hasAttr("alt") ? el.attr("alt") : "";
                if (!alt.isBlank()) {
                    out.append(escapeText(alt));
                }
            }
            case "br" -> out.append('\n');
            default -> {
                for (Node child : el.childNodes()) {
                    renderInlineInto(child, out);
                }
            }
        }
    }

    private static void emitParagraph(String text, StringBuilder out) {
        String stripped = text.strip();
        if (!stripped.isEmpty()) {
            out.append("\n\n").append(stripped);
        }
    }

    /** 引用块：每行加 "> " 前缀。 */
    private static String quoteLines(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.strip().split("\n", -1)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("> ").append(line.strip());
        }
        return sb.toString();
    }

    /** 行内文本转义：抹掉易误判的 Markdown 强调标记。 */
    private static String escapeText(String s) {
        return s.replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
    }

    /** 折叠连续空白（含换行）为单空格。 */
    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** 规整空白：块间空行收敛为两换行，首尾去空白。 */
    private static String normalize(String text) {
        return text.strip().replaceAll("\n{3,}", "\n\n");
    }
}

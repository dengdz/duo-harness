# 08: Markdown 渲染

## What to build

agent 回复按 Markdown 正常渲染：代码块、列表、标题不再平铺成纯文本。渲染库与消毒库以单文件 vendor 入 classpath（无 CDN、无构建链、无包管理器），LLM 输出经消毒后才插入页面——模型输出中的注入脚本不可执行。代码块本期只做等宽样式，语法高亮留 backlog。

## Blocked by

01（前端结构）

## Status
done

## Checklist
- [x] vendor marked.js + DOMPurify 单文件入库 classpath（无 CDN、无构建链、无包管理器）
- [x] 助手消息 MD 渲染 + 代码块等宽样式（浏览器冒烟：代码块/列表/标题用例；流式期间与完成后的渲染切换体验顺滑）
- [x] 消毒生效：构造含 script 与事件属性的回复确认不执行（人工冒烟留证）

## Comments

- 实现（2026-09-14）：vendor marked v18.0.13（UMD 单文件 45KB，npm pack 固定版本）+ DOMPurify v3.4.15（min 29KB）入 classpath；渲染只接 `finishAssistant`（实时收口与历史回放同路）——流式期间保持纯文本追加（半截 markdown 渲染闪烁），错误/系统消息维持 textContent；`marked.parse → DOMPurify.sanitize` 顺序固定（消毒作用于解析后 HTML），库缺失降级纯文本不断对话。
- 验证：WebFaceTest 16/16（vendor 可达用例）；真实对话冒烟——请求标题/列表/代码块/行内码，渲染结构精确命中（h2、3 li、pre code、行内码）；XSS 探针——`<script>` 剥除未执行、`onerror` 剥除、`javascript:` URL 中和为纯文本；全量 `mvn -o test` 262 用例绿。
- 审查（ocr）：2 处 style 修复——h5/h6 纳入标题样式覆盖（全局 margin reset 下会渲染破相）、宽表格块级横向滚动（防溢出气泡）。
- 待手动验证：用户亲跑确认后置 done。

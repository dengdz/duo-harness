# 08: Markdown 渲染

## What to build

agent 回复按 Markdown 正常渲染：代码块、列表、标题不再平铺成纯文本。渲染库与消毒库以单文件 vendor 入 classpath（无 CDN、无构建链、无包管理器），LLM 输出经消毒后才插入页面——模型输出中的注入脚本不可执行。代码块本期只做等宽样式，语法高亮留 backlog。

## Blocked by

01（前端结构）

## Status
ready-for-agent

## Checklist
- [ ] vendor marked.js + DOMPurify 单文件入库 classpath 静态资源
- [ ] 助手消息 MD 渲染 + 代码块等宽样式（浏览器冒烟：代码块/列表/标题用例；流式期间与完成后的渲染切换体验顺滑）
- [ ] 消毒生效：构造含 script 与事件属性的回复确认不执行（人工冒烟留证）

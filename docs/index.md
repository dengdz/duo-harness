---
layout: home

hero:
  name: duo-harness
  text: Java 插件化 AI agent harness
  tagline: 内核为自研轻量插件容器，所有能力以插件组装——工具 / MCP / 技能 / 人机协同，终端与浏览器双呈现位 + headless 同源运行
  actions:
    - theme: brand
      text: 快速开始 →
      link: /01-入门/快速开始
    - theme: alt
      text: GitHub
      link: https://github.com/dengdz/duo-harness

features:
  - icon: 🔧
    title: 工具域
    details: 本机 fs 与 web 工具族，三段执行管线挂审批 / guard / 输出契约——能动的东西都过闸门
  - icon: 🔌
    title: MCP
    details: 官方 Java SDK 接 stdio 服务器，断连重连、远端工具自动同步，本地远端同权
  - icon: 🤖
    title: agent 循环
    details: Function Calling 闭环 + 并发调度 + 子代理模板制派发，迭代上限防失控
  - icon: 💬
    title: 人机协同
    details: 审批 / 提问 / 计划确认卡片，谁发起谁作答;无人应答一律 fail-closed
  - icon: 🧠
    title: 上下文治理
    details: 真实 usage 计量、microcompact 本地裁剪、自动压缩——长会话记得住
  - icon: 🕸️
    title: 会话与检索
    details: 事件溯源 JSONL、崩溃恢复、FTS5 全文检索、/export 交付清单
  - icon: 📦
    title: 插件化扩展
    details: yml 一行挂载、四扩展点写插件、零内核改动——不想用的能力不装配
  - icon: 🖥️
    title: 双呈现位 + headless
    details: 终端 REPL 与浏览器共享会话与审批路由；--json 事件流接 CI
---

## 按你想要做的事找

| 想做什么 | 去哪 |
|---|---|
| 装起来跑 | [快速开始](01-入门/快速开始.md) |
| 自己组装一个 agent | [组装你的第一个 agent](02-指南/组装你的第一个agent.md) |
| 控制 agent 能动什么 | [权限与审批](02-指南/权限与审批.md) |
| 找回会话与历史 | [会话管理与恢复](02-指南/会话管理与恢复.md) · [导出与检索](02-指南/导出与检索.md) |
| 查命令、切模型 | [斜杠命令](02-指南/斜杠命令.md) · [模型与思考档位](02-指南/模型与思考档位.md) |
| 接外部能力 | [MCP 接入](02-指南/MCP接入.md) · [hooks](02-指南/hooks.md) · [子代理](02-指南/子代理.md) |
| 查配置、参数、界面 | [config 全量字段参考](05-参考/config全量字段参考.md) · [CLI 参考](05-参考/CLI参考.md) · [Web 界面使用说明](05-参考/Web界面使用说明.md) |
| 查工具与事件 | [工具目录](05-参考/工具目录.md) · [会话事件类型表](05-参考/会话事件类型表.md) |
| 写技能、写插件 | [技能编写指南](03-高级/技能编写指南.md) · [MCP 深入](03-高级/MCP深入.md) · [多插件协同](03-高级/多插件协同.md) · [插件扩展点清单](05-参考/插件扩展点清单.md) · [插件配置参考](05-参考/插件配置参考.md) |
| 理解设计 | [架构·设计主线](04-架构/设计主线.md) · [模块划分](04-架构/模块划分.md) |

## 文档之外

- [已知限制](limitations.md)——唯一权威清单
- [术语表](05-参考/术语表.md)——领域词汇定义(仓库根 CONTEXT.md 为指针)
- [架构决策记录(ADR)](adr/)——每个「为什么这样设计」的裁定原文
- GitHub:[仓库](https://github.com/dengdz/duo-harness) · [Releases](https://github.com/dengdz/duo-harness/releases)

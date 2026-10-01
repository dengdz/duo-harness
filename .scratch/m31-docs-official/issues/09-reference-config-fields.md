# 09: 参考新增——config.yml 全量字段参考

## What to build

一张 config.yml 全量字段参考：全部段（llm/web/mcp/governance/hooks 及各插件 config 段）的字段、类型、缺省值、约束与相互依赖，一张表查完任何配置——不翻源码。体量最大故单列；以配置绑定代码为事实源逐段盘点。

## Blocked by

01（大纲确认）。

## Status

ready-for-agent

## Checklist

- [ ] 字段全量盘点：逐段从配置绑定代码提取（每字段：类型/缺省值/约束），与既有《插件配置参考》对账不矛盾
- [ ] 段落组织与《快速开始》样例、《插件配置参考》互链（一张表为主干，插件细节链接不复制）
- [ ] 抽查关键字段实测（改配置看行为，活体引用）
- [ ] 内链自查 + vitepress build 通过
- [ ] 用户验收通过；CHANGELOG 0.27.0 段同 diff 记账

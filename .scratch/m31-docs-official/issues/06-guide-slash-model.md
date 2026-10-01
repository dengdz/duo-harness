# 06: 指南新增——斜杠命令 + 模型与思考档位

## What to build

两篇新指南：①斜杠命令（按场景分组讲 /compact /export /model /effort /permission 等——每命令干什么、什么时候用，CLI 与 Web 双面入口差异）；②模型与思考档位（/model 切换与 `llm.models` 白名单、/effort 档位与各 provider 映射、不支持时的显式降级标注、resume 意图不自动切）。

## Blocked by

01（大纲确认）。

## Status

ready-for-agent

## Checklist

- [ ] 斜杠命令篇成稿：命令清单与场景分组，双呈现位入口差异说明
- [ ] 模型档位篇成稿：切换/白名单/降级标注/resume 语义，含思考流式卡的前端呈现指向
- [ ] 命令行为逐条实测（活体引用）
- [ ] 内链自查 + vitepress build 通过
- [ ] 用户验收通过；CHANGELOG 0.27.0 段同 diff 记账

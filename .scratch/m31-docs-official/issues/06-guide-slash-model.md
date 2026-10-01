# 06: 指南新增——斜杠命令 + 模型与思考档位

## What to build

两篇新指南：①斜杠命令（按场景分组讲 /compact /export /model /effort /permission 等——每命令干什么、什么时候用，CLI 与 Web 双面入口差异）；②模型与思考档位（/model 切换与 `llm.models` 白名单、/effort 档位与各 provider 映射、不支持时的显式降级标注、resume 意图不自动切）。

## Blocked by

01（大纲确认）。

## Status

done（2026-10-01 用户验收通过）

## Checklist

- [x] 斜杠命令篇成稿：发法（CLI/Web 共享注册表、busy-safe 运行中可用/其余等待空闲、不进模型上下文）、11 命令按场景分组四表（会话流/模型档位/权限计划/上下文成果）、技能直调与未知命令报错附可用清单
- [x] 模型档位篇成稿：provider 四值声明表、/model 白名单与保存意图/执行绑定分离（resume 提示不自动切）、/effort 四档与 provider 映射、显式降级标注（锚 LlmConfig#175「DeepSeek 不支持思考等级参数……切 reasoner 模型」）、辅助请求强制降档（锚 SessionTitles#107-110）、思考过程呈现（流式卡/折叠卡/回放同源）
- [x] 命令行为逐条实测（活体引用——命令清单/语法锚探索报告实测清单 + 源码行号，降级/降档两断言本轮锚定源码）
- [x] 内链自查 + vitepress build 通过（2026-10-01，1.25s）；sidebar 两篇入册；口吻自查零命中
- [x] 用户验收通过（2026-10-01）；CHANGELOG 0.27.0 段同 diff 记账

## Comments

- 2026-10-01 产出：`docs/02-指南/斜杠命令.md`（约 70 行）、`docs/02-指南/模型与思考档位.md`（约 65 行）。模型篇含 3 枚前向链接（config 全量字段参考 → 工单 09；Web 界面使用说明 → 工单 11），工单 12 收口核对。

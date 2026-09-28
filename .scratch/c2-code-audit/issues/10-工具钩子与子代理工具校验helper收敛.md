# 10: 工具钩子与子代理工具校验 helper 收敛

## What to build
两处 copy-paste 校验/拼装逻辑收敛：① 工具钩子 Pre/Post 两段监听器中重复四份的"裁定 detail 提取 + 阻断消息拼装"收敛为共享 helper；② 子代理工具族（spawn/fork/send/interrupt 等）逐字重复的参数校验（取必填文本、会话归属校验）收敛为包内 helper。校验规则与错误文案调整此后只改一处。

证据锚点：审计报告 P2-B 族第 6/9 条（HooksPlugin 四份拷贝、子代理工具族六份拷贝）。

验收标准（用户可感）：Pre 与 Post 钩子的阻断消息口径永远一致；子代理工具的参数错误提示口径统一。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] 钩子 detail 提取 + 消息拼装 helper（decisionDetail/blockMessage 单点，消四份逐字拷贝）
- [x] 子代理工具族参数校验 helper（SubagentArgs：requireText ×4、requireCurrentSession ×2 收敛，调用方传各自工具名保持原文案）
- [x] Pre/Post 阻断消息一致性（既有 HooksPluginEndToEndTest 断言两侧文案 + 子代理既有用例全绿 + 文案抽查一致——零行为变化）
- [x] CHANGELOG：无用户可见变化（文案逐字保持），不记账（红线 6 只记用户可见）

## Comments

- 2026-09-28 实现：钩子侧 helper 落 HooksPlugin 私有静态（事件名经 HooksConfig 常量传参——值 "PreToolUse"/"PostToolUse" 与原文案一致）；子代理侧新建包内 SubagentArgs（SubagentManager 在父包，import 记档）。下一裁定形态（updatedInput）落地时只改单点。agent 255 + hooks 36 用例全绿。


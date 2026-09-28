# 10: 工具钩子与子代理工具校验 helper 收敛

## What to build
两处 copy-paste 校验/拼装逻辑收敛：① 工具钩子 Pre/Post 两段监听器中重复四份的"裁定 detail 提取 + 阻断消息拼装"收敛为共享 helper；② 子代理工具族（spawn/fork/send/interrupt 等）逐字重复的参数校验（取必填文本、会话归属校验）收敛为包内 helper。校验规则与错误文案调整此后只改一处。

证据锚点：审计报告 P2-B 族第 6/9 条（HooksPlugin 四份拷贝、子代理工具族六份拷贝）。

验收标准（用户可感）：Pre 与 Post 钩子的阻断消息口径永远一致；子代理工具的参数错误提示口径统一。

## Status
ready-for-agent

## Checklist
- [ ] 钩子 detail 提取 + 消息拼装 helper（消四份拷贝）
- [ ] 子代理工具族参数校验 helper（取文本/会话归属，消六份拷贝）
- [ ] Pre/Post 阻断消息一致性用例；子代理工具既有用例全绿 + 文案抽查一致
- [ ] CHANGELOG 记账

# 05: ZCode UI 交互形态研究（视觉线先行）

## What to build
ZCode 前端的视觉/交互形态研究新篇，落 `docs/research/ZCode/呈现与UI/` 下（现有三篇讲机制层，本篇补形态层）：四块逐块提炼——思考过程折叠（默认态/展开态/流式期形态）、分工具类型卡形态（family 分发下各工具卡的版式差异）、子代理页（页面结构与抽屉/页面对比）、确认交互卡样式（审批/计划/提问卡的版式与按键组织）；每块附「对 duo 的落地清单」（映射到 duo app.js/theme.css 的落点方向与可借鉴/不可借鉴判断）。产出喂 06/07 卡片工单与 09 走查评审。

## Blocked by
无（可立即开工）

## Status
done（2026-09-29 用户确认两篇研究文档 + 模仿开发可用性审查通过）

## Checklist
- [x] 源码级核验：本机 `/Users/zhangyl/IdeaProjects/ZCode/`，文档头部声明源锚点 872ad960（v3.14.0，结论只对该锚点负责）
- [x] 四块逐块产出：组件/样式源码定位 → 形态描述（版式、状态、动效）→ 对 duo 落地清单（可借鉴项 + 落点方向、明确不借鉴项及理由）
- [x] 五段式模板对齐研究文档惯例（机制全貌/关键流程/接口要点/边界与坑/对 duo 的启示——形态篇可裁剪但风格一致）
- [x] 研究索引（docs/research/index.md）同 diff 更新；~~sidebar 更新~~——实测修正：config.mts:13 `srcExclude: ['**/research/**']` 研究目录不上文档站，sidebar 无 research 节，无需更新（工单立项时的误设）
- [x] duo 侧落点全部经前端现状核实（app.js 分区、theme.css token、既有卡片族），不凭研究方想象
- [x] 提交前两 grep 自检（乱码 grep 零命中；研究文档为内部开发文档不上站，CHANGELOG 不记——M22 探测批先例口径）

## Comments

- 2026-09-29：两探索代理并行深挖（块一思考折叠+确认卡 47 次工具调用；块二工具卡+子代理页 56 次），产出按「源码定位→形态描述→CSS/交互」组织并区分实读/推断。落盘前承重事实机械抽查五条全过（reasoning.tsx 扫光分支 :331、styles.css .animated-gradient-text :823、PermissionDialog 实卡版式 :693、ToolLayout 运行态注释 :139-147、ReasoningRowView :1587）。
- 2026-09-29：关键反直觉发现（对 06/07/09 工单有直接输入）：①ZCode 工具卡收起态不是卡片，是一行文字流，「卡片感」由各卡展开内容自绘；②运行态不用 spinner，类别词文案扫光（性能注释原文在 ToolLayout.tsx:139-141）；③状态徽标只报忧不报喜（完成态默认无状态词）；④子代理页=右栏 tab + 只读复用主会话组件，非路由页；⑤确认卡挂 timeline 底部 dock（架构与 duo 消息流内嵌不同，判不借鉴）。
- 2026-09-29：**用户指示扩展为全景盘点**（「文字怎么渲染、格式怎么实时渲染、思考和正文怎么区别渲染、loading 怎么做、计划列表怎么呈现、会话列表怎么渲染，能看到的地方都搞清楚」）——追加三探索代理（文字渲染 36 次 / loading+计划面 60 次 / 会话列表与周边 77 次，共 173 次工具调用），产出第二篇 `消息渲染与面板形态.md`（正文 markdown 栈：Streamdown=Vercel 开源库、React #185 两条设计决策注释原文、思考 vs 正文分层对比表、用户消息不渲染 markdown、CJK singleTilde 双关闭；loading 全景：全库约 120 处 animate-spin 修正第一篇口径、TTFT 轮尾槽+可见性裁决、无打字光标无骨架屏；计划面=状态面板分区+侧栏 tab 实时更新、无独立 todoPanel、plansLoading 死字段；会话列表/composer/状态面板/toast/空态/顶栏全盘点）。第二篇抽查五条全中（package.json:75 streamdown、message.tsx:700-706 mode 注释、ConversationUserInputContent 纯文本、标题 slice(0,80)、composer 壳）。第一篇「唯一转圈处」口径已修正（限定子代理目录 pane 内）并互链。
- 2026-09-29：**用户发起模仿开发可用性审查**（「这个文档是否支持后续的模仿开发」）——逐工单对照实现所需 vs 文档已有，三处缺口当场补强：①附录 A1 扫光完整 CSS 抄录（5 段色标/keyframes/reduced-motion，原生可直抄）；②附录 A3 duo token 映射表（九 token 四类处置，新增 `--text-faint` 建议）；③附录 A2 走马灯参数（补充抽查挖出 40px/s/停留 2s/hover 延迟 1s）。07 成果卡参照来源声明补入（防实现者找错参照）。审查结论：补强后支持模仿开发。
- 2026-09-29：**用户确认两篇 + 附录**。工单 05 done。06/07/09 的实现参照系就位。

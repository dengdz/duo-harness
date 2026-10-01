# M29 整页走查清单（walkthrough.md）

> 状态：**初稿待用户逐区域裁定**（工单 09——本工单只评审出清单不改代码，打磨项裁定后排入工单 10）。截图存证：`screenshots/01-06`（隔离实例 18090，2026-09-29）。三方基线：M8 冻结原型（整体布局/token 未漂移）+ DSH 亮色 token（全程一致）+ ZCode 落地清单（研究文档两篇附录）。

## 区域清单与现状（截图为证）

| # | 区域 | 截图 | 现状一句话 |
|---|---|---|---|
| R1 | 三栏骨架 + hero 空态 | 01 | 布局与原型一致；hero 为「logo + 新会话 + 一句描述」 |
| R2 | 会话侧栏 | 01/02 | 条目=标题+绝对时间；选中蓝框；使用中灰显 |
| R3 | 状态面 | 01-06 | 上下文占用/后台任务/连接器/插件六态/工具清单，全展开固定占满右栏 |
| R4 | 工具卡（write/bash 通用卡） | 02/04 | 🔧 头行 + 徽标 + **参数 JSON 全量** pre + 结果折叠 |
| R5 | 成果卡（新） | 02 | ZCode 预览卡形态 ✓（本轮已交付） |
| R6 | 思考卡（新） | 05/06 | 流式展开滚动 + 定稿折叠 ✓（本轮已交付） |
| R7 | 语法高亮（新） | 03 | 流式逐步上色 ✓（本轮已交付） |
| R8 | 审批卡（挂起/冻结） | 05/06 | 四选项全宽行 + 挂起蓝选中 + 冻结 ✓ 已批准/✗ 已拒绝 |
| R9 | 错误卡 | 06 | bash 失败红底卡 + ✗ 徽标，状态醒目 |
| R10 | 输入区 | 01/05 | 单输入框 + 发送/停止红钮切换 ✓ |
| R11 | 正文排版 | 02-06 | markdown 渲染 + 行内 code 徽标 ✓；行高 1.65 |
| R12 | toast/提问卡/todo 卡/子代理抽屉 | 未现场截 | 提问卡形态同审批卡族；本次未构造 |

## 候选打磨项（对照基线逐条列，待裁定）

### W1 会话侧栏：标题截断 → 渐隐 + hover 走马灯
ZCode 标题不用省略号，右缘 1.5rem 渐隐 + hover 匀速滚动（附录 A2 参数齐：40px/s、停留 2s、hover 延迟 1s）。duo 现状 CSS ellipsis 截断，长标题（「创建并申报 demo-out 两个文件」）一屏内已可见，超长标题信息损失。
**体量**：小（theme.css 一段 + 少量 JS）；**涉及面**：侧栏条目渲染。

### W2 会话侧栏：运行中/使用中的状态点强化
现状「使用中 · 今天 20:19」灰字。ZCode：前置 16px 状态点槽（error 红 > unread 蓝 > loading 转圈 > 空闲灰点）。duo 无未读/错误态数据源，可做的子集 = 运行中会话转圈点/当前选中点。
**体量**：小；**涉及面**：侧栏条目渲染。

### W3 hero 空态：时段问候语
ZCode 六档时段问候（「上午好呀…」）。duo hero 固定「新会话 + 描述一句」。加问候语 = 纯文案 + 一小时段判断函数。
**体量**：小；**涉及面**：hero 渲染。

### W4 hero 空态：示例提示 chips
ZCode 有推荐提示词瀑布（duo 无推荐系统，可做固定三条示例：写代码/查会话/跑命令）。
**体量**：小-中；**涉及面**：hero 渲染 + 点击填输入框。

### W5 工具卡参数摘要化
现状 write/bash 卡把**整个参数 JSON** 展示为 pre（02 截图 write 卡参数占半屏）。ZCode 收起态显示参数摘要（bash 显示命令本体、write 显示文件 chip）。改法：write→「path: xxx」、bash→命令文本、其余工具回退 JSON——参数摘要函数 + 保留展开原文（结果 details 里）。
**体量**：中；**涉及面**：toolCall 渲染（或参数摘要 helper）。

### W6 状态面分区折叠
现状五个分区固定全展开（右栏满屏插件表）。ZCode 每分区 Radix Collapsible（标题行 chevron）。改法：原生 `<details>` 分区折叠（默认展开状态保留、插件/工具两个长表默认收起）。
**体量**：中；**涉及面**：状态面渲染。

### W7 正文行高/字距微调
duo 行高 1.65 无字距；ZCode 1.75 + tracking-wide。微调对齐（观感更松）。
**体量**：极小（一行 CSS）；**涉及面**：.msg.assistant。

### W8 用户气泡样式微调
现状 user 消息灰底方框右对齐；ZCode 圆角右肩气泡（rounded-tr-xs 特征）。纯 CSS 圆角调整。
**体量**：极小；**涉及面**：.msg.user。

### W9 卡片过渡动画
卡片无入场/hover 过渡。ZCode 有 stream 动画与 hover 过渡（我们 animated=false 反闪烁裁定的启示：仅加 hover/折叠 transition，不加入场动画）。
**体量**：小；**涉及面**：theme.css transition 若干。

## 明确不做（基线对照后排除，理由在册）

- 暗色主题/色板更换（走查红线：打磨不重设计）
- 底部停靠交互卡（架构级，06 轮已裁不借鉴）
- 侧栏未读/错误状态点（duo 无未读语义数据源，只有运行中可做——并入 W2 子集）
- @文件补全、附件入口、推荐提示词系统（功能新增非打磨，超走查边界——W4 只做固定示例的降级版）
- 状态面悬浮化/双形态（ZCode panel/mini 双形态是布局重排）

## 裁定记录（2026-09-29 用户逐组裁定）

| 项 | 裁定 | 备注 |
|---|---|---|
| W1 标题走马灯 | **做** | 附录 A2 参数 |
| W2 状态点 | **做** | 运行中转圈点 + 选中点子集 |
| W3 时段问候 | **做** | 六档文案 |
| W4 示例 chips | **做** | 固定三条降级版 |
| W5 参数摘要化 | **做** | write/bash 摘要 + 原文留结果折叠 |
| W6 状态面折叠 | **做** | 插件/工具长表默认收起 |
| W7 行高微调 | **做** | 1.75 + 字距 |
| W8 气泡微调 | **做** | 圆角右肩 |
| W9 过渡动画 | **做** | 仅 hover/折叠 transition |

九项全做，全部排入工单 10（执行清单见下行「工单 10 执行清单」节）。

## 工单 10 执行清单（按涉及面分组）

**theme.css 为主（纯样式）**：W7 行高 1.75+字距 ｜ W8 气泡圆角右肩 ｜ W9 hover/折叠 transition ｜ W1 走马灯样式（渐隐 mask + marquee keyframes，附录 A2）｜ W2 状态点样式 ｜ W6 details 折叠样式

**app.js（渲染逻辑）**：W1 走马灯 JS（hover 启动/位移循环）｜ W2 运行中状态点判定（会话使用中态已有）｜ W3 时段问候函数｜ W4 三条示例 chips + 点击填输入框 ｜ W5 参数摘要 helper（write→path、bash→command，其余回退 JSON；原文进结果折叠）｜ W6 状态面 details 化（插件/工具表默认收起）

**验收**：每项复截对照（screenshots/ 续编 07+）+ 用户目测销号；全仓回归绿（状态面/侧栏渲染改动不破既有端点测试）。

## 工单 12 追加轮：问题 5 展开体格式渲染（2026-09-30）

用户问题 5 后半：「点击展开后的内容需要进行格式渲染，行距大小等和外面的正文格式一致」。

**落地**：
- app.js 四处填充点切 renderMarkdown：toolResult 通用结果盒、memoryCard 记忆内容、skillCard 预览（SKILL.md 本就是文档）；fileCard write 预览保留 `code-raw` 变体（文件内容=代码语义 12px mono）
- theme.css：`.result-inbody` 去 mono 12px → 14px/1.75/--text，保留 pre-wrap（markdown 渲染 + 纯输出行结构两全）；`.skill-preview` 同步正文排版
- 字号收口漏网：`#messages` 补 `font-size: 14px` 基准——气泡此前继承 body 16px 是两档制漏网（「其他全 14」覆盖正文本身）

**实测对账（token=cba03d94 实例回放 3c0c 会话）**：展开体 4/4 md 渲染 + 14px/24.5px；非 14 仅剩 h2 标题 16.8px（em 比例，标题层级）与「✗ 已拒绝」徽标 12px（用户钦定徽标 12）——两处为规则内豁免。

## 工单 12 追加轮：工具卡展示样式 ZCode 源码级对比（2026-09-30）

锚点：ZCode 新 HEAD `29628c9`（v3.14.3，pull --ff-only 进一提交；ToolCallBlocks 目录由 components/ai-elements/ 迁至 packages/ui/src/ToolCallBlocks/，ToolLayout 363 行 / ToolSummaryRow 235 行行数未变）。工单 05 研究文档锚点 872ad96 落后一提交，行号锚点需增量校正（目录路径已变）。

一致项（同款）：壳层无框行式 / 行结构顺序 / gap 8px / 字号两档制（ZCode --ui-font-size:14px，styles.css:58）/ chevron 悬停浮现+旋转 90° 200ms / 类别词 medium+浅色 / 报忧不报喜 / 失败 tooltip / diff +N绿-N红 / 文件 chip+父目录 / 未知工具显式兜底 / 中文工具名（i18n 等价）。
真差异（9 项）：①运行态 ZCode 类别词扫光 vs duo ⟳ 静态徽标；②ZCode 终端卡收起态命令 font-sans（execute.tsx:291-296 注释明确）vs duo tcard-primary mono；③输出盒 ZCode 5 行 max-h-[5lh] 纯 pre vs duo 240px markdown（用户问题 5 裁定，有意超越）；④fallback raw JSON ZCode 10px 限高 200px 盒 vs duo param-raw 14px 无限高；⑤todo ZCode 彩色图标+划线 vs duo ✓/→/○ 文字符号；⑥子代理色 ZCode 8 色 hash vs duo 6 色写死；⑦折叠工程 ZCode 300ms 延迟卸载+Map 持久+autoOpen vs duo 直切（零依赖等价简化）；⑧无障碍 ZCode role/aria/键盘 vs duo 无；⑨mono 栈 ZCode 显式补 CJK（styles.css:138-141）vs duo 纯西文栈（theme.css:24）。

## 工单 12 追加轮：对比差异 4-9 全部落地（2026-09-30，用户裁定「4-9全部落地」）

| # | 落地 | 锚点 |
|---|---|---|
| 4 | 运行态 ⟳ 徽标退役 → 类别词 `.tcard-label.sweep` 扫光（keyframes sweep-flow 4s + `--sweep-strong/soft` token + reduced-motion 兜底）；toolResult/skill 特判收尾时移除 sweep；行尾新增常驻 `.tcard-status` 槽，失败填点线「执行失败」+ title 全文（ZCode ToolLayout.tsx:139-147,240-302） | app.js typedCardShell/toolResult、theme.css sweep 块 |
| 5 | `.tcard-primary` 去 mono → sans（ZCode execute.tsx:291-296 注释：收起态摘要属正文用 sans） | theme.css |
| 6 | `.param-raw` 14px 无限高 → 12px mono + surface-2 盒 + 200px 内滚（ZCode fallback.tsx:64 形态） | theme.css |
| 7 | `--mono` 补 CJK 栈（PingFang SC/Hiragino Sans GB/Microsoft YaHei；ZCode styles.css:138-141） | theme.css:24 |
| 8 | todo 清单 ✓/→/○ 文字符号 → SVG 图标三态（完成绿勾 circle-check+划线变浅 / 进行中静态箭头 / 待办空心圈，ZCode todo.tsx:17-45）；ICON_PATHS 增 circle-check/arrow-right/circle | app.js todoCard、theme.css .todo-item |
| 9 | 子代理色补齐 8 色对齐 subagentColors.ts text-*-700（amber/rose/orange/emerald/cyan/sky/violet/pink）；**发现并删除 490 行旧 6/7 重复定义**（级联覆盖新值，agentColorClass 本就 %8） | theme.css |

**实测**（token=798f43cd 实例）：历史卡 sweep 零残留、失败词 1 处正确、primary sans、param-raw 12px/200px、mono 栈含 CJK、8 色探针全对（#b45309/#be123c/#c2410c/#047857/#0e7490/#0369a1/#7c3aed/#be185d）、扫光 animation=sweep-flow 4s、todo 三态划线/绿色勾/静态箭头探针全对。截图 m29-49-landed.png。
保留：badge-run 仅存成果卡（ADR-0031 不以 ZCode 为视觉参照）与子代理实卡（状态词语义）。

## 工单 12 追加轮：思考卡段间隙异常修复（2026-09-30，用户发现「思考里行间距很高」）

根因：`.reasoning-body` 的 `white-space: pre-wrap`（工单 06 流式纯文本遗产）与定稿态 markdown 渲染叠加——marked 输出的 `</p>\n<p>` 字面换行被 pre-wrap 显示成整行空隙，实测段间隙 24.5+8=32.5px（应 8px）。
修复：`.reasoning-body .md-body { white-space: normal; }`——定稿态（有 .md-body 包裹）关 pre-wrap，流式期纯文本（无包裹）不受影响。实测段间隙 8px，截图 m29-reasoning-gap-fixed.png。
经验点：pre-wrap 容器内嵌 markdown 渲染体时，渲染器输出的排版换行符会被放大为整行——「纯文本容器 + 块渲染体」混用必须显式重置 white-space。

## 工单 12 追加轮：非 14/12 字号审计落地（2026-09-30，用户「确认」）

审计结论 11 处非 14/12 值：6 处合理保留（h1/h2 em 浮点≈ZCode ui-xl/lg、▸10px 图标等价、todo ✓9px 圆内符号、@弹层 12/11、文件图标 22、hero 展示层），3 处修正，2 条死样式删除：
- `.md-body h3/h4-h6` 1.1/1.05em → 1em（ZCode message.tsx:443-446 压平 base，仅字重区分）；实测 h3=14px
- `.choice` / `.free-input input` 13 → 14px（ZCode PermissionDialog.tsx:815/819 选项行 text-ui-base）；实测 choice=14px
- `.todo-fold` 13 → 14px（旧 /todo 路径仍存活 1 处）
- 删 `details.params` summary/pre 死样式（app.js 零引用；从与 details.result 的共用选择器中摘除，result 全保留）；grep 确认 params 规则清零

## 工单 12 追加轮：展开体形态再裁定——干净代码块直出（2026-09-30）

用户指着模型回复里的引用代码块裁定「这块才应该是展开后的内容」：展开体该是这种单块 mono 形态，而非 markdown 解析的碎块（缩进行被识别成代码块、[exit code: 0] 成孤段——工具原始输出本不是 markdown 文档，解析反而造伪结构）。
落地：toolResult 通用路径 renderMarkdown → 单一 `<pre>` 直出（.result-inbody 盒 + `.result-inbody > pre` mono 12px/1.5，与 md 代码块同档；failed 红字同步）；fileCard write 内容并入同款（code-raw 变体删除）；memoryCard/skillCard 文档类保留 markdown 渲染（内容本就是文档）。
实测：终端卡展开体单块 pre、mono 12、margin 0，与模型引用块视觉同款。
分工口径定型：机器产出（bash/read/glob/grep 等）= 干净代码块；文档类（记忆/技能 SKILL.md）= markdown 渲染。

## 工单 12 追加轮：技能卡内容错位修复（2026-09-30）

用户实测：点「加载技能」行展开的是空 `.tcard-body`（高 6px 空条），SKILL.md 内容（.skill-preview）挂在卡根上常驻展开体外——结构错位，内容不受点开控制。
修复：skillCard 的 preview 从 `card.appendChild` 改为 `body.appendChild`——「行=摘要，点行=展开内容」模型归位（收起只见行摘要，点行展开 SKILL 内容；结果未到时展开显示「加载中…」）。toolResult 特判填充逻辑不受影响（querySelector 仍在卡内）。
实测（48a5 演练会话回放）：收起 preview 在 body 内且隐藏；点行后展开显示 markdown 渲染的 SKILL 内容。截图 m29-skill-inbody.png。

## 工单 12 追加轮：ask_user 回放冻结双份修复（2026-09-30）

用户问「这几个是什么区域」时暴露：同一次 ask_user 提问回放产生两条冻结——tool/call 回放分支冻问题行（原文命中）、tool/result 回放分支再冻一对（qEvent 找不到问题原文 → 「（提问）」占位 + 回答行）。
修复：call 冻结卡按 toolCallId 登记 t.toolCards；result 到达按 id 找回就地补回答行（ask-frozen-a），找不到才走 qEvent 兜底。askFrozenLine 补 return card。
实测（48a5 回放）：冻结卡 1 张（问题原文+回答成对），占位 0。截图 m29-ask-merged.png。

## 工单 12 追加轮：ask_user 冻结卡并入行卡模型（2026-09-30，用户裁定）

「默认只展示第一行，给 ask_user 加名称（询问用户）+ 问题，点击展开用户选择的内容」——askFrozenLine 从 msg.ask-frozen 双行直出改为 typedCardShell 行卡：💡 询问用户 + 问题摘要（ellipsis 截断、title 悬停全文），点行展开体 = 问题全文 + 用户选择（result 补回答改 append 进 .tcard-body）。实时流 dock 冻结路径不受影响。
实测（48a5 回放）：收起仅一行「询问用户 | 前 9 个工具…」，展开两行（问题全文+回答）。截图 m29-ask-tcard.png。

## 工单 12 追加轮：read 卡行数回填退役（2026-09-30）

用户问「为什么还显示个 6 行」——旧设计残留（read 结果行数回填行内副信息「Markdown · 6 行」），与「行=摘要，点行=展开」模型不符（ZCode read 卡副信息仅类型+目录）。删 toolResult 的 read 行数特判；文件内容仍在展开体（干净代码块路径）。
实测：4 张读取卡全部无「N 行」，行= 读取+路径+类型，点开见文件内容。

## 工单 12 追加轮：通用卡参数摘要上行（2026-09-30）

用户问「抓取网页怎么三个区域」——行主信息空（URL 缺席）+ 展开体 param-raw JSON 盒 + result-inbody 结果盒三处并存。根因：toolCall 未消费 paramSummary（该函数一直支持 web_fetch→url 等），且无摘要命中判断，参数原文恒渲染。
修复：primary = paramSummary(...)；命中即渲染摘要且不建 param-raw（原文仅在摘要未命中时进展开体兜底）。行=「工具名 + 关键参数」，展开=结果，两区域。
实测（48a5）：抓取网页|https://example.com、会话搜索|执行序、搜索内容|badge、查找文件|**/*.md 全部摘要上行，param-raw 零残留。

## 工单 12 追加轮：成果卡 ⟳ 运行中徽标永挂修复（2026-09-30）

用户问「这个区域这样展示对吗」——present 成果申报卡在完成/回放态仍挂「⟳ 运行中」。根因：成果卡走旧式 .card 族不走 typedCardShell，4-9 的扫光/徽标收尾够不到它；present 又在免结果段清单，成功后无任何收尾。修：toolResult 收尾段补旧式卡兼容（.tool > .badge——成功移除、失败换 ✗ 失败 + title）。实卡形态本身（文件预览+复制路径）为工单 07 ADR-0031 裁定保留。
实测：回放态成果卡头「📦 成果申报 · 1 件」无徽标，文件行完整。截图 m29-deliverable-fixed.png。

## 工单 12 追加轮：present 退役 → ZCode 式产物预览卡（2026-09-30，用户裁定）

「把工具申报功能砍掉，改成 Zcode 预览卡，好做就这次做」——评估好做，本轮落地：
- **后端**：WebPlugin 摘 registerPresentTool 调用（工具停注册，模型不再可见）；PresentTool/ChangeSummary 类保留（测试与导出交付清单消费不炸）
- **前端砍**：deliverableCard 删、dispatch present 分支删（历史回放走通用卡兜底）、免结果段/TOOL_LABELS 去 present
- **前端加**：extractPreviewPaths（ZCode conversation-preview-artifacts 简化版：产物扩展名 + \p{L}\p{N} 中文路径 + 绝对路径前导 /，去重保序上限 10）+ finishAssistant 定稿挂 .preview-cards 组（复用 filePreviewRow：44px 图标+文件名+类型·目录+复制路径），挂助手气泡后；回放同源
- **正则用例 6/6**：双路径/中文路径/裸文件名/代码围栏不误报/无产物/绝对路径
- **实测**：回放兼容（旧成果卡 0、present 兜底 1、21 卡不炸）；演练会话末条回复自动生成 4 张预览卡（notes.md/experience.md/all-tools.md×2）挂气泡后；web 模块 100 用例全绿
- **文档同步**：术语表「成果卡片」→「产物预览卡」词条改写、工具目录 present 节标退役、ADR-0028 Status 注退役、CHANGELOG Added 改写 + Removed 记账

## 工单 12 追加轮：图标 ZCode 同款分配落地（2026-09-30，用户先预览后拍板「ZCode 同款」）

ZCode 渲染器真实分配（源码挖取）：读/搜一族共用 SearchIcon（read/search/explore）、未识别统一 WrenchIcon——语义族共用是其原生哲学，非每类独占。落地：ICON_PATHS 全量换 lucide 官方 path（terminal=SquareTerminal/edit=Pencil/list=ListTodo/skill=WandSparkles/lightbulb=CircleHelp/book=BookOpenText/file-output/circle-stop/globe/file/sparkles 保留给记忆写入）+ 新增 TOOL_ICONS 工具→图标映射（toolIcon() 兜底 wrench）；fileCard 读写分图（write=file/read=search）；顺带修复 todoCard 引用不存在的 list 键回落 wrench 的缺键。改前页面注入预览条供用户目测拍板，落地后清除。
实测（48a5 回放 20 卡图标指纹逐一比对）：会话搜索=书、读取/搜索/查找=放大镜共用、记忆=星火、编辑=铅笔、工具统计/present 兜底=扳手、技能=魔杖、询问=圆问号、终端=双层终端、写入=文档、抓取=地球、清单=ListTodo——全部与 ZCode 分配吻合。截图 m29-icons-zcode-final.png。

## 工单 12 追加轮：失败态收敛为浅红四字（2026-09-30，用户裁定）

「失败时只需要执行失败四个字红色就行，而且是浅红」——.tcard-status.fail 去点线下划线/help 光标、正红→rgba(220,38,38,.75) 浅红；删 .tcard.error 染红 label/primary 的规则（行文字保持原色）。展开体 failed 盒（红底错误详情）保留。
实测：状态词 rgba(220,38,38,.75)/无下划线，类别词浅灰、主信息正色。截图 m29-fail-soft.png。

## 工单 12 追加轮：失败态浮窗（2026-09-30，用户裁定「下划线加回 + 悬停浮窗效果」）

「执行失败」四字：浅红 + 点线下划线加回；错误全文弃原生 title（延迟大样式朴素），改 CSS 浮窗——:hover::after content: attr(data-error)，白底描边阴影圆角 480px 限宽 160px 内滚 12px 红字，::before 小三角；JS 两处 status.title → dataset.error（防原生+浮窗双弹）。skill 特判同步。
验证：data-error 携全文、下划线 dotted 在位；浮窗规则经等价傍类强制应用实测（content/白底/12px/absolute 定位全对，截图 m29-fail-tooltip.png）；:hover 由浏览器原生触发。

## 工单 12 追加轮：浮窗定位与点击隔离修复（2026-09-30，用户两反馈）

①浮窗超窗/位置不对 → 改向下浮（top: calc(100%+8px)，向上浮在视口顶部超窗）+ max-width: min(480px, calc(100vw-64px)) 数学保证任意视口水平不超；三角翻到浮窗上方。②点「执行失败」仍触发开合 → 行点击排除链加 .tcard-status（badge/[data-action] 同款先例；hover 语义不受影响）。
实测：点词不开合（openBefore==openAfter）、点行正常开合、浮窗 top 定位向下。

## 工单 12 追加轮：浮窗钳制中栏（2026-09-30，用户反馈「不能超出三栏中间区域」）

根因：浮窗锚词右缘，而状态词位置随 primary 弹性收缩不固定（词常靠行左）——480px 宽向左伸穿出中栏左界（实测窄视口 -26px 越界）。
修复：锚点从词挪到 .tcard（占满中栏宽，position:relative）——浮窗 right:0 = 卡右缘 = 中栏右界内；宽 ≤ min(480px, 100cqw-16px)（cqw = 卡内容宽，.tcard 声明 container-type: inline-size）→ 左缘 ≥ 卡左缘+16 恒成立；top:28px（行高20+间距8）向下浮；指不准词的小三角移除（浮窗描边+阴影已足浮层语义）；cqw 不识别降级 480px 先声。
实测（窄视口 778px 卡宽）：中栏 [245,1055]，浮窗 [559,1039] ✓ 界内。截图 m29-tooltip-anchor-card.png。

## 工单 12 追加轮：浮窗改 JS 智能定位（2026-09-30，用户反馈「位置依然很偏」）

锚卡右缘解决了越界但离词远（词常在行左、浮窗贴右）。纯 CSS 单锚点无法两全（锚词→左越界；锚卡→位置偏），改 Radix 同款思路：mouseover 委托 + JS 悬停计算——词下方居中为理想位，clamp 到卡（中栏）界内，写 --fly-left（相对词）给伪元素；每次 mouseover 重算（词位置静态、计算廉价）；垂直回锚词（top: calc(100%+8px)）。
实测：词中心 426，浮窗 [269,749]，词中心在浮窗水平范围内 ✓ 正下方居中，且卡界内 ✓（本例理想位左缘 186 < 卡左界 269 被钳回）。截图 m29-fly-centered.png。

## 工单 12 追加轮：浮窗放宽 640（2026-09-30，用户裁定「宽一宽少换行」）

CSS 两条 max-width 与 JS 钳制 Math.min 同步 480→640。实测窄视口卡宽 778：浮窗 640 宽 [269,909] 界内，词下方居中保持。

## 工单 12 追加轮：浮窗改中栏半宽（2026-09-30，用户裁定「站中间区域的一半」）

640 固定上限 → calc(50cqw - 8px)（卡内容宽一半；降级 50vw），JS 钳制同步 cr.width/2-8。实测窄视口卡宽 778：浮窗 381（49%）、界内、词下方居中保持。截图 m29-fly-half.png。另：本轮发现「重启换 token 后用户页不重载资源」的验收坑——视觉验收前必须硬刷新（已在对话说明）。

## 工单 12 追加轮：失败卡整行不可展开（2026-10-01，用户反馈「点行其他位置依然展开」）

此前只挡了状态词点击，行其余位置仍展开错误盒——与「失败=四字浅红+悬停浮窗」的口径不一致。修：①typedCardShell 与 todoCard 行点击守卫加 error 态拦截（失败卡整行不可展开）；②toolResult 失败路径收起已开的展开体 + 不再填结果盒（错误全文唯一出口=悬停浮窗 data-error）；③skill 特判失败同理（preview 不填错误文本）。
实测（3c0c 回放失败卡）：点词/类别词/主信息三种点击开合状态零变化，body 零子节点，data-error 携全文。

## 工单 12 追加轮：行卡整行浅灰统一（2026-10-01，用户裁定）

「这一行都统一成浅灰」——.tcard-row 基准色 var(--text) → var(--text-faint)，primary 随行色变浅，label/ticon/secondary 去冗余单独色声明（同色继承）；语义色保留（diff +N绿-N红、失败浅红四字）。实测行色/类别词/主信息三者 rgba(26,31,39,0.4) 全等。截图 m29-row-unified.png。

## 工单 12 全量回归（2026-10-01，用户发起）

**后端**：mvn 全模块 test —— 1080 用例 0 失败 0 错误 BUILD SUCCESS（MCP session terminated 为测试 teardown 预期日志）。
**浏览器 DOM 回归 22/22**（48a5 演练会话）：图标分配 9 项（2 项初判 ✗ 为断言截断 24 字符假阴性，全 path 复验 ✓）、整行浅灰、正文 14/代码 12、失败浅红四字+不展开+浮窗数据、思考更名+段间隙 8px（复验选多段卡）、询问用户冻结合一、预览卡 4 张、param-raw 唯一残留=present 历史卡无摘要兜底（设计内）。
**实时冒烟**：第一轮裸调 /api/session/new 未切页面绑定（冒烟方法缺陷非产品缺陷），任务进 48a5——**意外抓出真缺口：present 仍可被调用**（CLI 呈现位 CliPlugin:278 同样注册，工具表呈现位间共享，单边摘除不彻底；模型调用拿到 CLI 旧会话「会话已关闭」错误）。修：CliPlugin 同口径摘注册。第二轮走 UI（＋新话题→发送→轮询）全链路通过：新会话绑定、写入卡实时渲染、回复提及路径、**气泡后预览卡实时成卡（regression2.md）**；状态面验证 present 已退册。

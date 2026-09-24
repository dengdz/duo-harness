# 05: duo-workflow + 根 AGENTS.md 路由同步改写

## What to build
改写 duo-workflow（入口 router）：L1/L2/L3 箭头链流程补各节点 Done when；"模型不可自动触发"等散文约束按探测结果落到最强可用层；跨技能引用改显式调用；description 三规则重写。根 AGENTS.md 路由表与 description 同 diff 双向同步（触发词与措辞一致）。改写排改写组第一张——后续每票的触发命中重放都在新路由下运行。

验收标准（重放 seam）：用真实口语说法（"开发个功能"/"提交一下"/"验收"等 ≥6 种）触发路由，核对命中预期技能与 L 级判定。

## Blocked by
04

## Status
done（2026-09-25；重放 seam 8 种说法（超要求的 6 种）机械层全绿，见下对照表；行为层真实命中率按 ADR-0027 Consequences ④预留使用周期观察）

## Checklist
- [x] duo-workflow SKILL.md 按规范改写（判级表 + 兜底 + 落痕规则；L1 七步 / L2 四步 / L3 三步全部带完成判据共 18 处；设计访谈 / spec 工单 / 逐单实现三门禁步统一"出示命令并停止，等用户键入"判据形态；跨技能引用显式 Skill tool 调用 11 处（tdd×2/diagnosing-bugs/duo-code-review/duo-acceptance/duo-comprehension/duo-release-workflow/git-commit-gen×2/duo-tracker）；description 三规则重写 77 字（预算 ≤80），含收口（wrap-up）中英锚点）
- [x] AGENTS.md 路由表同 diff 同步（workflow 行触发词与新 description 触发概念对齐：+实现效果/+微调、注 L1/L2/L3；口语"开发一个功能"与描述"加功能"为同概念不同词面）
- [x] 重放：8 种口语说法路由命中核对，对照表如下

## 重放对照表（2026-09-25，机械层）

| # | 口语说法 | 命中技能 | 命中证据（grep 双向） | L 级判定（新判级表） |
|---|---|---|---|---|
| 1 | 开发一个功能 | duo-workflow | 路由行"开发一个功能" + description"加功能" | 按内容判级：新模块→L1；模块内→L2；介级取高并说明 |
| 2 | 做个全新的多租户模块（大需求） | duo-workflow | 路由行"大需求" + description"L1 大需求" | L1——第 1 步出示 /grill-me 并停止，等用户键入 |
| 3 | 修个 bug：停止按钮没反应 | duo-workflow | 路由行"修 bug" + description"修 bug" | L2——diagnosing-bugs 先诊断，回归测试先红后绿 |
| 4 | 按钮颜色和设计稿不一致 | duo-workflow | 路由行"调整页面" + description"调整页面" | L3——截图留档，改动无逻辑面 |
| 5 | 提交一下代码 | duo-workflow 提交前核对 → git-commit-gen | AGENTS.md 独立路由行 | 提交前核对清单四步判据（三件套/版本条目/分支名/确认） |
| 6 | 验收 | duo-acceptance | AGENTS.md 独立路由行 | 衔接 workflow L1 步骤 5（显式调用 + done 定义） |
| 7 | 审查一下改动 | duo-code-review | AGENTS.md 独立路由行 | 衔接 workflow L1 步骤 4（四轴收口判据） |
| 8 | 推一下 | duo-pre-push-checks | AGENTS.md 独立路由行 | — |

机械层核对：8 个触发概念在新 description 与 AGENTS.md 路由表双向 grep 命中（每概念两侧各 ≥1 处；精确计数口径随文本演进漂移，不作为判据，双向命中为判据）。行为层（模型真实触发命中率）按 ADR-0027 Consequences ④预留真实使用周期观察，异常按 duo-bug-ledger 记档。

## 审查轮（2026-09-25）

**覆盖**：4 个文件 = 已审 4——duo-workflow/SKILL.md（重写）、AGENTS.md（路由 1 行）、CHANGELOG.md（C1-05 条目）、本工单。行级/Java 轴空集执行（SKILL.md 属文档、零 Java）。

**四轴**（Standards / Spec 双子代理）：保命题逐分句对照通过（两删除节"定义归技能"经亲核 acceptance/comprehension 真实成立）；8 个 Skill tool 目标全部存在；description 77 字、门禁判据形态、frontmatter 两键均合规。

- **阻断**：无。
- **计数与声称（已修 3）**："7 处显式调用"三口径不一致（实 11 处，修复后终值统一）；L3 步 3 无判据与工单声称不符——补判据（提交经用户确认 + 信息含判级依据）；对照表精确计数改"双向命中"判据口径（精确数随文本漂移不作判据）。
- **形态（已修 6）**：L2/L3 两处弱引用补显式调用；门禁步 2/3（/to-spec、/implement）补"出示并停止"；指针点名 2 处（docs/agents/issue-tracker.md 路径、duo-code-review 收口节）；"main 只收验收合并"改红线 7 指针形态；"先讲后考"→"先学后考"（与 comprehension:18 对齐）；L3 视觉确认判据回补（红线 5 + 用户确认，修复"出示 ≠ 确认"弱化）。
- **删净与挂接（已修 3）**："agent 测试全绿 ≠ 验收"重复删除；理解关卡切课描述压缩为"（先学后考）"；L2 补 duo-release-workflow 挂接（旧版有、初稿丢）。
- **规范锚点（已修 1，系统性）**：规范引用 workflow 的 2 处锚点随改写失效——规范头部加快照说明行，2 处反例改"改写前快照（对比报告 §2.2）"引用；后续每张改写票回查规范锚点（review-log 已记模式）。
- **裁定不修（2）**：工单日期 2026-09-25 为真实当前日期（审查子代理环境时钟滞后）；"开发一个功能 vs 加功能"同概念不同词面已在工单如实标注。
- **测试覆盖**：最窄验证 = 触发词双向 grep + docs:build + 规范/目标技能链接存在性核对。零 Java 触碰。

**处置汇总**：发现 12 处（合并去重）→ 修复 10 / 裁定 2。终值：显式调用 11 / 完成判据 18 / 门禁步 3 / 禁令句 0 / description 77 字 / 72 行。

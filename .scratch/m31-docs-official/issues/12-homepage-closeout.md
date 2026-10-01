# 12: 首页改造 + 全站收尾对账

## What to build

收口件：首页 index 产品化（产品简介 + 快速开始入口前置，research/、agents/ 退出站内导航与「文档之外」——文件本体不动）；nav/sidebar 收拢全站新篇目入口；全站构建绿 + 口吻终查（按规范抽查）；CHANGELOG 0.27.0 段记账核对（各工单行齐、发布口径就绪）。

## Blocked by

02, 03, 04, 05, 06, 07, 08, 09, 10, 11（全部篇目定稿后收拢入口）。

## Status

done（2026-10-01 用户验收通过——整站走查 + 首页 home 布局截图确认）

## Checklist

- [x] index 重写：产品一句话 + 「五分钟跑起来」快速开始前置 + 「按你想要做的事找」任务导向导航表（覆盖全部 24 篇）+「文档之外」收编（保留 limitations/术语表/ADR/GitHub 链接，research//agents 退出站内导航）
- [x] nav/sidebar 更新：指南分组 9 篇（组装 + 新 8）、参考分组 9 篇（原 5 + 新 3 + 术语表）全量入册（随各单实时更新，本单核终态）
- [x] 全站口吻终查：叙事残留扫描仅剩术语表词条溯源注（工单 04 裁定的决策寻址链，保留正确）；入门/指南/高级/架构/参考各篇零残留
- [x] research/、agents/ 文件本体零改动核实（git status 空）
- [x] vitepress build 全绿（2026-10-01，1.30s）；**站内死链 0**（脚本全量核对——工单 02 的 4 枚前向链接与 06 的链接全部兑现）；CHANGELOG 0.27.0 段逐单核对记账齐全（8 条）
- [x] 用户整站走查验收通过（2026-10-01）
- [x] 验收修改：首页升级 VitePress home 布局（hero + 八能力卡 + 任务导航表，零新依赖）——用户截图确认，留档 homepage-preview.png

## Comments

- 2026-10-01 产出：`docs/index.md` 重写（导航页 → 产品首页：一句话定位 + 快速开始前置 + 任务导向找文档 + 收编版「文档之外」）。全站 24 篇（快速开始 1 + 指南 9 + 高级 3 + 架构 2 + 参考 9）+ index + limitations。
- 2026-10-01 验收期修改（用户「加个好看的首页」）：index.md 升级 `layout: home` frontmatter——hero（name/text/tagline/双 CTA）+ features 八能力卡（工具域/MCP/agent 循环/人机协同/上下文治理/会话检索/插件化/双呈现位），markdown 体（任务导航表 + 文档之外）保留在卡片下方；VitePress 默认主题原生能力，零新依赖零构建链。截图确认留档 homepage-preview.png。

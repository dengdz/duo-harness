# 03: 1.x 版本策略声明（调研 + README + 文档站短页）

## What to build

使用者在 README 与文档站看到 1.x 版本承诺。口径（ADR-0034 裁定）：语义化版本——1.x 内向后兼容（新功能进 minor、修缺陷进 patch、破坏性变更才到 2.0）+ 按需发版 + 对照系惯例一句。声明前做一轮轻量对照调研（ZCode 主、DSH 辅的版本策略声明惯例：声明位置 / 措辞 / 承诺形态），结论写进声明正文或注；README 与文档站短页内容同源（短页为准，README 择要）。spec 见 [spec.md](../spec.md)。

## Blocked by

无（可即开）。

## Status

ready-for-agent

## Checklist

- [ ] 对照调研落底稿：ZCode / DSH 版本策略声明（位置 / 措辞 / 承诺形态）各一段，锚点在案
- [ ] README 加版本策略小节（快速开始 / 能力概览之后的门面段，措辞与调研结论一致）
- [ ] 文档站参考层短页（版本与发布策略）入 sidebar，与「已知限制」的位置关系按现结构定
- [ ] vitepress build 通过；站内链接自查（新增内链可达）
- [ ] CHANGELOG 1.0.0 未发布段记账（README + 文档站两处变更同段，红线 6）
- [ ] 用户亲手验收（README / 短页目测 + 声明措辞裁定）

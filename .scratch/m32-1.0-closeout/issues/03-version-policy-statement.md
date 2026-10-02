# 03: 1.x 版本策略声明（调研 + README + 文档站短页）

## What to build

使用者在 README 与文档站看到 1.x 版本承诺。口径（ADR-0034 裁定）：语义化版本——1.x 内向后兼容（新功能进 minor、修缺陷进 patch、破坏性变更才到 2.0）+ 按需发版 + 对照系惯例一句。声明前做一轮轻量对照调研（ZCode 主、DSH 辅的版本策略声明惯例：声明位置 / 措辞 / 承诺形态），结论写进声明正文或注；README 与文档站短页内容同源（短页为准，README 择要）。spec 见 [spec.md](../spec.md)。

## Blocked by

无（可即开）。

## Status

done（2026-10-02 用户确认声明措辞与落点）

## Checklist

- [x] 对照调研落底稿：ZCode / DSH 版本策略声明（位置 / 措辞 / 承诺形态）各一段，锚点在案（见 Comments）
- [x] README 加版本策略小节（能力概览之后、模块之前，措辞与调研结论一致）
- [x] 文档站参考层短页《版本与发布》入 sidebar（参考组末尾、「已知限制」之前）
- [x] vitepress build 通过（1.40s，2026-10-02）；站内链接自查（/01-入门/快速开始、sidebar 新项、README 相对链逐一核对可达）
- [x] CHANGELOG 1.0.0 未发布段记账（README + 文档站同一条目，红线 6）
- [x] 用户亲手验收（2026-10-02 用户确认 README / 短页目测与声明措辞）

## Comments

- 2026-10-02 产出：`docs/05-参考/版本与发布.md`（短页为准）+ sidebar 入册 + README「版本与发布」小节（短页择要）+ CHANGELOG 记账。调研底稿：

  **ZCode（主参考，锚点 `29628c9` = v3.14.3，~/IdeaProjects/ZCode）**：无专门「版本策略」声明页，惯例由发版设施承载——根 `.release-it.mjs` + `scripts/release-it/changelog-writer.mjs`（conventional-changelog-conventionalcommits preset，`breakingHeaderPattern: type(scope)!:` 即破坏性语法），feat→minor / fix→patch / `!:`→major 由工具链机械判定，changelog 自动分节（Features/Bug Fixes/Chores/…）；README 仅在安装节体现 `releases/<version>/` 产物 + `latest.json` 指针 + sha256 校验。承诺形态：**不写承诺、把语义化版本做进发版机械**；3.x 印证 major 常态迭代。

  **DSH（辅参考，锚点 `ddefc45f` = v0.1.6-alpha.2，~/IdeaProjects/deepseek-harness，与 docs/research 研究锚点同源）**：README 专节「Developer preview」（第 13 行），措辞 "in developer preview and iterating rapidly. THERE WILL BE COMPATIBILITY-BREAKING CHANGES."；承诺形态：**反承诺**——明示无兼容保证；版本形态 0.x alpha/rc（npm prerelease，tag `dsh-v0.1.6-alpha.2`），快速迭代无 CHANGELOG 文件。

  **对照启示**：duo 已过 preview 期盖章 1.0——承诺应强于 DSH 的反承诺；升档判定无 ZCode 式全自动机械，靠 CHANGELOG 锚点纪律与发版把关，声明里如实写明这一差异（不冒充机械化语义化版本）。声明措辞与 ADR-0034 裁定一致（语义化版本 + 按需发版 + 对照系一句）。

- **待用户验收**：README 小节、短页全文、声明措辞（尤其「对照系惯例」节措辞与承诺边界表述）由用户目测裁定；验收后置 done。
- 2026-10-02 **用户确认声明措辞与落点，工单 03 done**。工单 05 的前置剩余：工单 04。

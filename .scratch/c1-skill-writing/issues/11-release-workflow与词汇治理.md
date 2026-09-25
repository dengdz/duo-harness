# 11: duo-release-workflow 改写 + 词汇治理 + AGENTS.md 同义词红线

## What to build
duo-release-workflow 按规范改写（检查清单表格保留、失败路径 fail-fast、引用改显式调用、description 重写）；docs/agents/domain.md 增「技能用词」节——每词首选 + Avoid 两行（工单/ticket/issue 统一、验收 vs 验证分工、收口、领先词、上下文指针）；根 AGENTS.md 增红线"SKILL description 不引入词汇表外同义词"；术语表补齐改写过程新增词条（领先词、上下文指针等，随实现同 diff）。

验收标准（重放 seam）：一次真实发版（或演练到推送前检查）按新流程走通；抽查三个 SKILL.md 用词对照词汇表零表外同义词。

## Blocked by
04

## Status
done（2026-09-25；四项全落地 + 词汇抽查零表外同义词 + 发版演练挂账工单 12）

## Checklist
- [x] duo-release-workflow SKILL.md 按规范改写（description 169→62 字、八步各带完成判据共 9 处、显式调用 4 处（duo-code-review×2/duo-doc-standards/duo-pre-push-checks）+ 引导态 fail-fast、两表资产无损、红线不抄录原则保留、租约守卫改指针承接唯一硬护栏）
- [x] domain.md「技能用词」节（8 行表格，对照对比报告 §5.4 清单全覆盖）+ AGENTS.md 同义词红线 9（同 diff）+ 路由行补"准备发布"（1.4 对账）
- [x] 术语表新增词条补齐核对（C1-04 已落 8 词条与 §5.4 逐项对上，无缺漏；domain.md 收口行补"最窄"限定）
- [x] 重放：发版演练挂账工单 12（统一推送即第八步真实执行，届时按第六步三判据出示证据）；词汇抽查——Avoid 清单四词全库 grep 清零（"按需加载"3 处改"按需载入"，ticket 4 处为技能名专名豁免），**零表外同义词达成**

## 审查轮（2026-09-25）

**覆盖**：4 个文件 = 已审 4——release-workflow SKILL.md、domain.md、AGENTS.md（红线 9 + 路由行）、术语表核对；修复阶段扩展规范（3 处锚点）与 java-review（词汇残留）。行级/Java 轴空集执行；第 0 步预检：SHA eabb968 出示。

**四轴**（Standards / Spec 双子代理）：保命题逐分句全过（八步/需求分级表/审查方式表/租约守卫两条/红线不抄录/推送后验证/docs-site 确认全存活）；表格资产两处无损；frontmatter 单行标量 YAML 可解析。

- **规范 1.3 统一（已修 3）**：同文件内 3 处链接式 operative 引用改显式调用或"文件+节"形态（duo-doc-standards 文档同步步、duo-pre-push-checks 租约节、duo-code-review 重复调用合并）。
- **路由对账（已修 1）**：AGENTS.md 路由行补"准备发布"（description 触发词双向对账——ADR-0027 决策三）。
- **判据强化（已修 1）**：第三步"四类变更均落盘"补证据形态（git status 出示）。
- **锚点漂移（已修 3）**：规范块标量正例/红线引文语序/引导态守卫引文——节名化/快照化。
- **词汇残留（已修 1）**：java-review"按需加载"3 处改"按需载入"（L2 标题保留属专有命名体系）。
- **限定词（已修 1）**：domain.md 收口行补"最窄"（与术语表 :334 对齐）。
- **记档（2）**：description 无中英并列锚点（"发版"无英文先验，同批取舍一致）；正文"用户确认"与术语表 Avoid"确认"的语义边界（验收语境的动作描述 vs 协作词汇，词条定义自身含"确认通过"——留工单 12 词汇抽查复核）。
- **测试覆盖**：Avoid 四词 grep 全库清零 + description 62 字实测 + 对账 27+1 + docs:build。零 Java 触碰。

**处置汇总**：发现 11 项（合并去重）→ 修复 10 / 记档 2。终值：release-workflow 62 字·判据 9·显式调用 4·130 行。

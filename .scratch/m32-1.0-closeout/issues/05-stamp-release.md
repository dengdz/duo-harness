# 05: 盖章 1.0.0——发版

## What to build

M32 收口最后一单：CHANGELOG 落 1.0.0 段（未发布段落日期；判定四项结论择要并入——ADR-0033 预裁「acceptance.md 四节 + CHANGELOG 择要并入」）→ tag v1.0.0 → release.yml 自动挂 GitHub Release（照旧自动化不特殊化，ADR-0034 裁定）→ jar 实测 `java -jar`（常驻双呈现位 + `--json` headless，M30 发版验收同款）→ 用户验收通过后合并 `1.0.0` → main（红线 7：main 只收验收后的版本合并）。本单 done 即 1.0 锚点兑现、版图终态达成。spec 见 [spec.md](../spec.md)。

## Blocked by

03, 04（CHANGELOG 1.0.0 段并入四项结论与声明变更；发版收口等全部前置）。

## Status

done（2026-10-02 用户实测验收 + 合并 main）

## Checklist

- [x] CHANGELOG 1.0.0 段：四项结论择要 + 本期用户可见变更（版本策略声明条目），落日期 2026-10-02
- [x] tag v1.0.0 并推送，GitHub Release 自动产出确认——**发版两查通过**：①Release 实查（非 draft/prerelease，jar 22,387,407B + sha256 资产在档，发布说明 = CHANGELOG 盖章段）；②release run 36962356302 success；版本对齐提交 62c4b268（pom 14 处 + README jar 名 2 处），本地 package 产物名 duo-harness-1.0.0.jar 预验证通过
- [x] jar 实测（下载校验半边）：Release jar 下载 sha256 两端一致（724dc84d…），/tmp 留档待用户运行时实测
- [x] 用户验收：2026-10-02 用户实测通过（常驻 + headless）
- [x] 验收通过后合并 1.0.0 → main（用户确认执行；合并后复核 main CI 绿——见 Comments）

## Comments

- 2026-10-02 版本对齐提交 62c4b268 推送，tag v1.0.0 推送，release.yml 3 分钟 success；按经验档「发版两查」纪律完成 Release 资产与 run 结论实查，jar 下载 sha256 校验通过。
- 2026-10-02 **用户实测验收通过，合并 1.0.0 → main 执行**（duo-release-workflow 第七/八步：远端 OID 一致 + docs-site 与 ci 双绿复核）。**v1.0.0 盖章落地，M32 收口，版图终态（ADR-0034）达成。**

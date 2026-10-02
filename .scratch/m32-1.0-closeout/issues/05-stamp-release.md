# 05: 盖章 1.0.0——发版

## What to build

M32 收口最后一单：CHANGELOG 落 1.0.0 段（未发布段落日期；判定四项结论择要并入——ADR-0033 预裁「acceptance.md 四节 + CHANGELOG 择要并入」）→ tag v1.0.0 → release.yml 自动挂 GitHub Release（照旧自动化不特殊化，ADR-0034 裁定）→ jar 实测 `java -jar`（常驻双呈现位 + `--json` headless，M30 发版验收同款）→ 用户验收通过后合并 `1.0.0` → main（红线 7：main 只收验收后的版本合并）。本单 done 即 1.0 锚点兑现、版图终态达成。spec 见 [spec.md](../spec.md)。

## Blocked by

03, 04（CHANGELOG 1.0.0 段并入四项结论与声明变更；发版收口等全部前置）。

## Status

ready-for-agent

## Checklist

- [ ] CHANGELOG 1.0.0 段：四项结论择要 + 本期用户可见变更（README 声明、文档站短页），落日期
- [ ] tag v1.0.0 并推送，GitHub Release 自动产出确认（jar 产物在、发布说明与 CHANGELOG 段一致）
- [ ] jar 实测：`java -jar duo-harness-1.0.0.jar` 常驻启动 + `--json` headless（命令整块复制自文档，不自拼）
- [ ] 用户验收 Release 页面与 jar 实测
- [ ] 验收通过后合并 1.0.0 → main（用户确认后执行）

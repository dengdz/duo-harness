# 03: release.yml——tag 即发布

## What to build
独立发版 workflow（不塞 ci.yml，职责与权限双隔离）：

1. **触发与权限**：push tag `v*` 触发；`contents: write` 仅此文件持有（ci.yml 维持 contents: read 不动）。
2. **步骤链**：tag 与 pom 版本一致性校验（`v<pom.version>` 不等 tag 名即 fail-fast，防手滑错位静默发假 Release）→ 全量 verify（测试不过不发版）→ 生成 SHA256 → 创建 GitHub Release 挂 jar + `.sha256`。
3. **Release notes**：脚本从 CHANGELOG.md 对应版本段（`## <version>` 节）摘录填 Release body，不用 GitHub 自动生成（PR 列表与 CHANGELOG 唯一锚点形成两本账，红线 6）。

验收标准（CI 无法本地全验的分段口径）：本票验收 = workflow 配置逐项审查 + 版本校验步骤以错位 tag 名干跑 fail-fast 证据；真打 tag 的端到端验证留里程碑收口（Release 页可见 jar + 校验和 + notes 与 CHANGELOG 一致，spec Testing Decisions 已钉）。

## Blocked by
02（有产物才可挂 Release）。

## Status
ready-for-agent

## Checklist
- [ ] release.yml：on push tag `v*` + 独立文件 + contents: write 仅此文件
- [ ] tag 与 pom 版本一致性校验步骤（错位 fail-fast）
- [ ] 全量 verify 步骤（测试不过不发版）
- [ ] SHA256 生成与产物命名 `duo-harness-<version>.jar.sha256`
- [ ] Release 创建挂 jar + 校验和；notes 脚本摘 CHANGELOG 对应版本段
- [ ] 错位 tag 干跑校验步骤 fail-fast 证据
- [ ] （收口期）真打 tag `v0.26.0` 端到端：Release 页三件齐——jar / sha / notes 与 CHANGELOG 一致

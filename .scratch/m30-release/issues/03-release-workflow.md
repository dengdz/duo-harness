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
done（2026-10-01 用户走查确认随提交指令 + 收口期真打 tag `v0.26.0` 端到端通过——Release 页三件齐，release.yml 首秀完成；首轮 verify 失败为 CI flaky，诊断三连随行后第二轮 success）

## Checklist
- [x] release.yml：on push tag `v[0-9]*` + 独立文件 + contents: write 仅此文件
- [x] tag 与 pom 版本一致性校验步骤（错位 fail-fast + pom 取值空值守卫）
- [x] 全量 verify 步骤（测试不过不发版，与 ci.yml 逐字同口径）
- [x] SHA256 生成与产物命名 `duo-harness-<version>.jar.sha256`（cd 进 target 纯文件名生成，下载方同目录可校验）
- [x] Release 创建挂 jar + 校验和；notes 脚本摘 CHANGELOG 对应版本段（未发布段 exit 3 + 段缺失 -s 兜底双 fail-closed）
- [x] 错位 tag 干跑校验步骤 fail-fast 证据（见 Comments 干跑记录）
- [x] （收口期）真打 tag `v0.26.0` 端到端：Release 页三件齐——jar / sha / notes 与 CHANGELOG 一致（2026-10-01 实测：首轮 verify 失败为 CI flaky（同 commit ci 绿 + 本地绿），补失败诊断三连后第二轮 success；Release 页 jar 22.4MB + .sha256 + notes 逐字对账 True）

## Comments

### 实现记录（2026-10-01）

- `.github/workflows/release.yml` 八步：checkout → JDK 21（对齐 ci.yml 惯例）→ tag 与 pom 版本一致性校验（help:evaluate 取 pom.version，空值显式点名拒绝）→ 全量 verify（`set -o pipefail && ./mvnw -B -ntp verify 2>&1 | tee` 与 ci.yml 逐字同口径）→ SHA256（显式版本名拒绝通配——本地 target 历史产物会被中段通配误捕获；cd 进 target 纯文件名生成，内嵌构建路径会让下载方 `sha256sum -c` 失败）→ notes 摘 CHANGELOG（awk 全角括号匹配，双 fail-closed：未发布段 exit 3、段缺失空文件 -s 兜底）→ `gh release create` 挂 jar + sha（github.token 经 env，写权限仅本文件）
- 触发 `v[0-9]*`（防 vfoo 类杂 tag 触发）；concurrency 按 ref 不取消进行中发版
- spec 外新增（均良性已记账于审查轮）：concurrency 块、sha256sum -c 自校验、SHA256 产物缺失显式断言

### 本地干跑记录（2026-10-01，checklist 第 6 项证据）

| 干跑项 | 输入 | 期望 | 实测 |
|---|---|---|---|
| 版本校验·错位 | TAG=0.99.0 vs pom=0.25.0 | exit 1 + 错位点名 | ✓ 检出（0.99.0 ≠ 0.25.0） |
| 版本校验·对齐 | TAG 剥前缀 = pom | 通过 | ✓ PASS |
| 版本校验·取值失败 | mvnw 不可执行（127） | 不静默通过 | ✓ 空值守卫拒发（修复后语义） |
| notes·正常段 | v0.25.0（已落日期段） | 完整摘录 | ✓ 14 行正文完整 |
| notes·未发布段 | v0.26.0（当前标未发布） | 拒绝 | ✓ exit 3 |
| notes·段缺失 | 无对应版本号 | 拒绝 | ✓ awk exit 0 空文件 → `-s` 兜底 exit 1（行级轴代实证缺口后修复闭合） |
| SHA256·下载方视角 | jar + .sha256 同目录（/tmp 拷贝模拟） | `sha256sum -c` OK | ✓ OK |
| workflow 语法 | python yaml 解析 | 可解析、步骤齐 | ✓ 8 步 |

### 审查轮（2026-10-01·四轴）

**覆盖**：release.yml 单文件 88→97 行全集，已审 1 + 跳过 0；Java 规范轴核实 Java diff 为空 0 违规

**阻断**：无

**建议（已修复 4 项）**：
- notes「段缺失」分支未 fail-closed（行级 medium + Standards 硬违规同发现；行级轴三分支代实证抓出）——awk 后补 `-s` 空文件兜底，错误消息按两分支分列
- SHA256 文件内嵌构建相对路径（Standards 硬违规；spec User Story 2「可下载可验证」抵触）——cd 进 target 纯文件名生成，下载方视角干跑 ✓
- 触发面 `v*` 收紧 `v[0-9]*`（行级低危；防 vfoo 类杂 tag）
- pom 版本取值空值守卫（实现期干跑发现 mvnw 127 静默空值）——空值显式拒绝

**记档（不修，附理由）**：
- ci.yml 失败诊断三步 + surefire 上传未随行（Standards 判断题）：tag 构建失败时 release 不发出本身即信号 + GitHub 原生日志可查，诊断步随行使文件膨胀，取舍记档
- Data Clumps：VERSION 剥前缀三处、jar 路径两处（Standards 低危）——各步局部自洽语义清晰，集中 env 反添间接层
- awk 动态正则 ver 点号未转义（两轴观察）：触发面 `v[0-9]*` + 全串等值校验前置，误配路径不可达
- notes 摘录钉全角括号比工单「`## <version>` 节」更窄（Spec 观察）：CHANGELOG 标题格式统一 `## x.y.z（日期）`，收窄方向是 fail-closed

**测试覆盖**：CI 配置类改动无 Java 逻辑；验证 = 本地干跑八项（见上表）+ 两轴独立代跑交叉印证；真打 tag 端到端按工单口径留收口期

# 06: 回归验证 + 发版 1.0.1——M33 阻塞例翻转 + 发版两查

## What to build

M34 收口单。①**回归验证**：重建 M33 隔离测试环境（工单 01 Comments 装配口径：隔离 DUO_HOME + 随机端口 + MCP echo fixture + git 工作区，subagent 行在档），内置浏览器复验 M33 报告的 **8 例 BUG-01 连带阻塞例**（STATUS-01/02/04/05/06/07/08 + CYCLE-07）——翻转记录回填 M33 报告附注；顺带抽验各缺陷回归锁的浏览器面行为。②**全量回归**：`./mvnw` 全量测试绿（含 01-05 新增回归锁）。③**发版**：CHANGELOG 1.0.1 段落日期 → tag v1.0.1 → release.yml 自动挂 Release → **发版两查**（M32 经验档：Release 实查非 draft + jar/sha256 资产齐 + main CI 末次 run 绿）。④limitations 页随修复回写（已修复状态 + M33 总报告「新发现」4 条核实结果）。⑤用户验收通过后合并 1.0.1 → main（红线 7）。spec 见 [spec.md](../spec.md)。

## Blocked by

01, 02, 03, 04, 05（全部修复与回归锁就位）。

## Status

in-progress（2026-10-02：回归验证 + 发版两查通过 + Release jar 本地双形态实测通过；**待用户验收确认**→ 合并 main → main CI 终查）

## Checklist

- [x] M33 隔离环境重建 + 8 例阻塞例复验翻转记录回填 M33 报告附注（STATUS-01/02/04/05/06/07/08 + CYCLE-07 全翻；另 5 例维持原判）
- [x] 各缺陷回归锁浏览器面抽验通过（BUG-02/04/06 + O15/O17 本批修复的验证证据即抽验；BUG-03 采样程序、BUG-05 采样程序入库）
- [x] 全量测试绿（pom 对齐后 clean test exit 0 + 发版前最后一轮 exit 0，含新增回归锁七用例）
- [x] CHANGELOG 1.0.1 段落日期（已落）→ tag v1.0.1 → **发版两查通过**（首打 release run 失败被两查逮到：导出测试锁释放竞态——修复重打后 Release v1.0.1 非 draft、jar 22,389,043B + sha256 资产齐；main CI 末次 run success）
- [x] limitations 页回写（M33/M34 节：双标签独占锁语义 + fail-closed 断连语义两条行为边界）
- [x] Release jar 本地双形态实测（agent 预检）：headless --json NDJSON 正确应答 exit 0；常驻 Web 启动 + MCP CONNECTED + 状态面五区块全数据（BUG-01 修复在 Release 产物上生效）
- [ ] 用户验收确认（jar 本地实测已由 agent 预检通过，用户目视/复跑确认）
- [ ] 验收通过后合并 1.0.1 → main（用户确认执行）→ main CI 终查

## Comments

- 2026-10-02 回归验证记录：M34 修复批 jar + 隔离实例浏览器面——8 例翻转明细见 M33 报告附注；STATUS-04 负路径以配错命令行二实例实测（CONNECTING 呈现非静默 + ERROR 日志）。
- **发版两查价值实证（本单首打）**：v1.0.1 首打 release run「全量构建与测试」失败——`WebSessionExportEndpointTest.unopenedSessionExportableById` 的既有锁释放竞态（导出响应流写完的 finally 释放 vs 客户端断言竞速，CI 慢 runner 显形、本地绿）——修复为锁重试等待后重打 tag，二查通过。无 gh CLI 环境 logs 403，诊断走 check-runs annotations 公开面（workflow 的失败诊断 emit 设计生效）。
- 执行环境自省：清场 rm -rf 连带删 authnone yml + clean 连带删 target jar 双断夹具——/tmp 夹具依赖 target 产物的 classpath 在版本改名/clean 后会断（fixture classpath 已改指 Release jar 副本，程序在案）。

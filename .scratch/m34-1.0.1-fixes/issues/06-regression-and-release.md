# 06: 回归验证 + 发版 1.0.1——M33 阻塞例翻转 + 发版两查

## What to build

M34 收口单。①**回归验证**：重建 M33 隔离测试环境（工单 01 Comments 装配口径：隔离 DUO_HOME + 随机端口 + MCP echo fixture + git 工作区，subagent 行在档），内置浏览器复验 M33 报告的 **8 例 BUG-01 连带阻塞例**（STATUS-01/02/04/05/06/07/08 + CYCLE-07）——翻转记录回填 M33 报告附注；顺带抽验各缺陷回归锁的浏览器面行为。②**全量回归**：`./mvnw` 全量测试绿（含 01-05 新增回归锁）。③**发版**：CHANGELOG 1.0.1 段落日期 → tag v1.0.1 → release.yml 自动挂 Release → **发版两查**（M32 经验档：Release 实查非 draft + jar/sha256 资产齐 + main CI 末次 run 绿）。④limitations 页随修复回写（已修复状态 + M33 总报告「新发现」4 条核实结果）。⑤用户验收通过后合并 1.0.1 → main（红线 7）。spec 见 [spec.md](../spec.md)。

## Blocked by

01, 02, 03, 04, 05（全部修复与回归锁就位）。

## Status

ready-for-agent

## Checklist

- [ ] M33 隔离环境重建 + 8 例阻塞例复验翻转记录回填 M33 报告附注
- [ ] 各缺陷回归锁浏览器面抽验通过
- [ ] 全量测试绿（含新增回归锁）
- [ ] CHANGELOG 1.0.1 段落日期 → tag v1.0.1 → 发版两查通过
- [ ] limitations 页回写（已修复状态 + 新发现核实）
- [ ] 用户验收：v1.0.1 jar 实测（常驻 + headless）
- [ ] 验收通过后合并 1.0.1 → main（用户确认执行）

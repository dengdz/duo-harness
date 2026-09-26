# 03: cwd 授权过滤——跨会话检索的目录边界

## What to build

跨会话检索结果逐条校验会话 cwd 与当前工作目录**相等才收**：项目 A 的会话里搜不到项目 B 的内容，防跨项目上下文污染；无 cwd 记录的 M26 前旧会话不进结果（老文件本体仍在、可打开，只是不进检索）。session_search 工具与 Web 搜索框两入口行为一致。

决策依据：[ADR-0028](../../../docs/adr/0028-M26会话数据与检索立项决策.md) 决策三（严格过滤；回填与显式跨目录开关为后续升级路径，本期不做）。DSH 对照：`recordAuthorized` 逐条校验形态。

## Blocked by
01, 02

## Status
in-progress

## Comments

- 2026-09-26 开工（0.21.0 分支，Blocked by 01/02 已完成）。

## Checklist
- [x] 检索管线按 cwd 逐条过滤（相等才收），消费 01 落盘的会话 cwd 字段
- [x] 无 cwd 的旧会话 fixture 不出现在检索结果（手写无头旧 JSONL 验证）
- [x] session_search 工具与 Web 搜索框授权边界一致（装配/集成用例）
- [x] CHANGELOG 记账：老会话不再出现在跨会话检索结果（用户可感知行为变化）

## Comments

- 2026-09-26 开工（0.21.0 分支，Blocked by 01/02 已完成）。
- 2026-09-26：**实现完成**。交付形态：
  - **过滤点在引擎内部**（FtsSessionIndex）：构造必传 cwd（授权边界与会话落盘侧同源，生产装配传进程工作目录）——两入口（session_search 工具 / Web 搜索框）共用同一服务实例，边界天然一致；SessionHit 不加字段（工具渲染契约不动，review-log 02 审查建议采纳）。
  - **persisted 路径**：sessions 表新增 cwd 列（schema v1→v2，SCHEMA_VERSION 升级触发旧库就地重建——迁移机制首次实战），reindex 解析头行 cwd 落库；查询 SQL `AND s.cwd = ?` 参数绑定，NULL（无头旧会话/未记目录）不进结果。
  - **live 路径**：`session.cwd()` 与查询侧字符串相等才参与匹配（null 排除）。
  - **测试**：新增 `legacyOrForeignCwdSessionsExcluded`（无头 + 异目录 persisted + 异目录 live 四形态只出同目录一条）；契约基类 session() 辅助改为带头行形态（授权边界是检索默认形态）+ badLines 用例改追加写；SessionSearchToolTest/WebSearchEndpointTest fixture 加头与双参适配。FTS 套件 27 用例全绿，全仓 13 模块 BUILD SUCCESS。
- 2026-09-26：CHANGELOG 记账（并入 FTS5 条目——授权边界同属检索域用户可感知变更）。**Status 停 in-progress（缺用户手动验证）**。
- 2026-09-26：**真实验证冒烟（agent 双向对照）通过**：/tmp/duo-m26-03-accept 双目录环境（proj-a/proj-b 各含同关键词"风铃草"会话 + 无头旧会话），从 proj-a 启动只出 a001、从 proj-b 启动只出 b001、无头旧会话两头不出。**边界发现记档（供审查轮评估）**：cwd 比较为字符串相等、不做路径规范化——macOS 的 /tmp 与 /private/tmp 双形态互不匹配；生产行为自洽（落盘与查询同源取 Path.of(user.dir) 物理路径），但手写/异构注入的会话头路径形态不同会被排除（fail-closed 方向，安全无害）。审查轮评估是否 normalize（toRealPath 对比）。

## 验收件（工单级，2026-09-26 备，agent 双向冒烟已过）

**真实验证（浏览器对照实验）**——环境已备 /tmp/duo-m26-03-accept（proj-a/proj-b 两工作目录 + 共享 home 含 A/B/无头旧三个手造会话，端口 18084）。两个方向各起一次（auth token URL 见终端）：

```bash
cd /tmp/duo-m26-03-accept/proj-a && DUO_HOME=/tmp/duo-m26-03-accept/home \
  mvn -f /Users/zhangyl/IdeaProjects/duo-harness/pom.xml -pl duo-harness-example -am package \
  exec:java -DskipTests -q -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args=/tmp/duo-m26-03-accept/web.yml
```

| 步骤 | 预期 |
|---|---|
| proj-a 启动 → 浏览器搜「风铃草」 | 只出「项目A的风铃草调研」 |
| Ctrl+C → 同命令改 `cd .../proj-b` 再起 → 搜「风铃草」 | 只出「项目B的风铃草调研」 |
| 两个方向对照 | 同一关键词、同一份会话目录，搜到的结果随启动目录切换——跨目录不串台 |

**测试路径**：`mvn -pl duo-harness-session-query -am test`——FtsSessionIndexTest 27 用例绿。

# 07: tool-stats 插件包化样板——第三方交货活样板

## What to build

既有 tool-stats（工具统计员，M27 demo）双形态交付：classpath 行保留不动，另提供插件包打包口——shade profile 一条命令打出自包含 fat-jar——加交货文档素材。它是"第三方作者怎么交货"的官方活样板：jar 放目录、页面点名、装载、`/toolstats` 生效，全程不重启。

验收标准：shade 产出的 jar 经目录扫描→点名→装载→功能生效→卸载干净的完整冒烟。

## Blocked by

02, 05

## Status

in-progress（实现与回归锁已完工待提交；提交后随 09 单端到端验收转 done）

## Checklist

- [x] 交货形态落钉：**v1 插件包 = 常规 `mvn package` 产物**（模块 jar 只含自有类）——shade profile 三轮试错后裁定移除（见 Comments）
- [x] 包内容排除宿主供给面验证：stats 类在包内 / core、tools、jackson 不在包内（zip 条目断言）
- [x] 冒烟：jar 行全链路激活（tools + commands 行喂依赖，commands 行须带 `config: {}`）+ `tool_stats` 工具在册；产物缺席 assumeTrue 自动跳过
- [x] 双形态共存：classpath 行保留不动，包化产物独立装载（同名服务互斥语义随容器在场）
- [x] CHANGELOG 记账（用户可见：示例包化，同 diff）

## Comments

- **shade 三轮翻车实录（2026-10-03，交货约定的实证来源）**：①按 artifact 枚举排除（core/tools/agent）→ 被排除件的**传递依赖**仍进包（shade artifactSet 排除不覆盖传递依赖），jackson 1201 类随包；②自优先加载器加载包内 jackson 副本 → 与宿主接口签名撞**加载器约束**（LinkageError 实测形态）；③改 `*:*` 排除 → 连自有类一起排空。**正解 = 常规模块 jar 即插件包**（自有类 + 宿主供给），交货约定随之定型："插件包不得打入宿主供给的类；自带独有库须 shade 并逐件排除宿主供给面"——08 单落文档站。
- **样板装配细节**：ToolStatsPlugin inject tools + commands；commands 行必须带 `config: {}`（CommandsPlugin 声明了 JsonNode config 类型，boot 严格绑定"声明即须提供"）。
- **交货文档素材（08 单落盘）**：`mvn -pl duo-harness-stats package` → `target/duo-harness-stats-<版本>.jar` → 放 `~/.duo/plugins/` → 页面点名 → 装。
- **验证**：ToolStatsPackageSmokeTest 2 用例 + stats 模块全量绿（mvn exit 0，-am 真实链）。

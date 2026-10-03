# 07: tool-stats 插件包化样板——第三方交货活样板

## What to build

既有 tool-stats（工具统计员，M27 demo）双形态交付：classpath 行保留不动，另提供插件包打包口——shade profile 一条命令打出自包含 fat-jar——加交货文档素材。它是"第三方作者怎么交货"的官方活样板：jar 放目录、页面点名、装载、`/toolstats` 生效，全程不重启。

验收标准：shade 产出的 jar 经目录扫描→点名→装载→功能生效→卸载干净的完整冒烟。

## Blocked by

02, 05

## Status

ready-for-agent

## Checklist

- [ ] shade profile：打出自包含 fat-jar（产物名带版本）
- [ ] 双形态共存验证：classpath 行与插件包装载互不干扰（同名服务互斥语义如实在场）
- [ ] 端到端冒烟：jar 放目录 → 扫描列待装 → 点名 → 装 → 工具计数与 `/toolstats` 生效 → 卸载干净
- [ ] 交货文档素材：从源码到 fat-jar 到装载的最短路径（文档站页在 08 单落盘）
- [ ] CHANGELOG 记账（用户可见：示例包化，同 diff）

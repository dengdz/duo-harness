# 07: 文档与收口

## What to build

web 工具族的用户可见面收口：参考文档、运行 Demo、CHANGELOG/limitations 记账全部就位，对账测试绿，里程碑验收件备好。全部行为工单（01-06）完成后执行。

## Blocked by

02, 03, 04, 05, 06

## Status

done

## Comments

- 2026-09-20（夜间自主实现）：工具目录（对账测试绿，11 工具全覆写）/ 插件配置参考 / 运行 Demo / demo yml / CHANGELOG / limitations 三条 / acceptance.md 验收件全部就位。全仓 `mvn test` BUILD SUCCESS。**待用户手动验收**后随里程碑收口置 done。

- 2026-09-20：用户手动验收通过（含 read-only ask 复验、baeldung 正文修复复验、Tavily 搜索接力），工单收口。
## Checklist

- [ ] `工具目录.md` 增 `web_fetch` / `web_search` 条目（工具目录对账测试绿）
- [ ] `插件配置参考.md` 增 `WebToolsPlugin` config 全字段（含缺省值与非法值语义）
- [ ] `运行Demo.md` 增 web 工具演示段 + demo yml 示例行（env 方式配 key）
- [ ] CHANGELOG 0.15.0 段 Added 记账（用户可见变更口径）
- [ ] limitations 新增三条：TOCTOU 重解析窗口 / URL 外泄通道不设防 / 表格 Markdown 降级
- [ ] 验收件准备（duo-acceptance）：工单级最小演示 + 里程碑汇总 Demo，含可复现步骤与日志

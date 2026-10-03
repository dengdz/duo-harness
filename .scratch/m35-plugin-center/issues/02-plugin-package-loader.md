# 02: 插件包装载器——fat-jar 行装载与类隔离

## What to build

第三方交来的自包含插件包（fat-jar），配合 yml 行（`jar:` 来源字段 + `name` 入口 FQCN，无 manifest 约定）即可被 Boot 装载激活——与 classpath 行完全同语义（config 绑定、依赖声明、失败点名整树回滚，既有 Boot 审计契约不变）。每包独立类加载器、插件间类互不可见；卸载 = 容器拔除 + 加载器丢弃，泄漏可检测并提示"需重启生效"。

验收标准：最小 fat-jar fixture 经 yml 行装载后服务在册、yml 回读断言通过；坏包各路径点名清晰。

## Blocked by

无硬阻塞（与 01 并行开工）；拔除/回落全链路联调依赖 01，本单先交静态装载断言。

## Status

ready-for-agent

## Checklist

- [ ] yml 行 `jar:` 字段解析（正向兼容：既有行零感知；`jar:` 与 `name` 组合校验，缺一实测报错点名）
- [ ] per-jar 类加载器装载（parent = 应用 classpath）；最小 fat-jar 测试 fixture（打包含最小插件的构建方式随 fixture 入库）
- [ ] 类隔离验证：两个插件包含同名类互不可见、装载结果互不串
- [ ] 卸载：容器拔除 + 加载器 close + 丢弃；泄漏检测提示"需重启生效"
- [ ] 坏包点名：缺文件/坏 jar/入口类缺失/构造失败——行 id + 原因 + 包路径，整树回滚
- [ ] S1 测试：jar 行装载 → 服务在册 → yml 回读断言（拔除/回落联调在 05 单收拢）
- [ ] CHANGELOG 记账（用户可见：jar 行装载能力，同 diff）

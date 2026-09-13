# 02: AGENTS.md 注入

**What to build:** agent 知道项目规矩：装配时加载 `~/.duo/AGENTS.md`（用户全局，可缺）与项目根 AGENTS.md（从 cwd 向上找 .git 定根；无 .git 则仅用户全局），按"用户全局 → 项目根"顺序拼接，64KB 总预算超限截断尾部并注明，注册为 `agents-md` 片段（排在用户配置片段之后、其他插件片段之前）进 prompt 注册表。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Checklist

- [ ] 加载器：.git 定项目根 + `~/.duo/AGENTS.md` 用户全局；两文件均可缺省
- [ ] 64KB 总预算：按序拼接、超限截断尾部、附加"（内容超预算已截断）"注明
- [ ] 注册为 `agents-md` 片段（source 名），组装顺序 = 用户配置片段之后
- [ ] 测试（ChatRequest 捕获 seam）：两文件齐 / 仅项目根 / 仅用户全局 / 全缺（不注册片段）/ 超预算截断
- [ ] 文档同步：CHANGELOG 未发布段记 AGENTS.md 注入；词汇表新增"AGENTS.md 注入"词条

## Comments

spec：[../spec.md](../spec.md)。嵌套目录链与 fs 操作后增量发现在 Out of Scope（M9+）。已知副作用：duo-harness 本仓库内运行会注入本仓库自己的 AGENTS.md（dogfood 噪声）——验收建议临时目录。

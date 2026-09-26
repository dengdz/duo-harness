# 01: 版本头与 cwd 字段——会话格式地基

## What to build

新会话文件首行携带格式版本头（`{type:"session", version:1}`）并落盘 cwd 字段；旧文件读时识别（无头即 v0 直读，磁盘一个字节不动）；迁移链机制建好备用——读端认出版本后在内存中逐级转换（vN→vN+1 接力，隔代可达）；全部"每行必是事件"的既有读者统一过"首行可能是版本头"分流，旧会话的标题静态读、权限档静态读、检索扫描、子会话事件端点、导出行遍历行为不变。

决策依据：[ADR-0028](../../../docs/adr/0028-M26会话数据与检索立项决策.md) 决策二（读时识别，磁盘永不重写）。spec：`.scratch/m26-session-data-search/spec.md`。

## Status
in-progress

## Checklist
- [x] `Session.create` 新会话首行落版本头，会话元信息落 cwd 字段；旧文件保持无头
- [x] load 与静态读路径对无头旧文件行为不变（v0 = 现有格式直读，无需转换）
- [x] 迁移链机制：读端按首行版本识别、内存逐级转换；用测试用迁移器验证 v1→v2→v3 接力与外来高版本拒绝语义
- [x] 既有逐行读者（标题/权限档静态读、检索扫描、子会话事件端点、导出行遍历）统一首行分流，全部既有测试回归绿
- [x] SessionTest 形态扩展用例：版本头往返、无头兼容读、cwd 字段往返、坏行语义不变
- [x] CHANGELOG 记账：会话文件首行新增版本头行（用户可感知格式变化）

## Comments

- 2026-09-26 开工（0.21.0 分支）。
- 2026-09-26：**实现完成**。交付形态：①新类 `SessionFormat`（session 模块）——头行序列化/解析/`isHeaderLine` 判定 + 迁移链纯函数 `migrate`（CURRENT_VERSION=1、链为空；首个真实迁移随 v2 格式变更在 load 激活点就位）②`Session.create(Path, Path)` 重载写头行（经持锁通道 force，与 persist 同一持久化承诺），旧签名委托传 null（测试与无 cwd 场景；生产装配必须传）③`load` 读时分流：首行是头 → parseHeader（version > CURRENT 拒绝为「请升级 harness」；头不进事件序列；重复头拒绝为坏数据）④`jsonlLines()` 补头行——与磁盘逐字节一致（同一序列化函数），v0 旧会话导出保持无头⑤11 处生产调用点传 cwd（WebPlugin×2 / CliPlugin×3 / SubagentManager×1 / example×5；取值 `System.getProperty("user.dir")`，与 MemoryPlugin/AgentsMdPlugin 惯例同源）。
- 2026-09-26：**静态读者兼容论证记档**：标题/权限档静态读、检索扫描（InvertedSessionIndex 白名单）、子会话事件端点（WebFace）均按事件 `type` 过滤，头行 `type:"session"` 天然不命中——零改动即兼容；显式分流仅在 load 的 parse 前（头行无 at/text，进 parse 会产出垃圾事件）。
- 2026-09-26：**存量测试适配 5 处**（均为预期行为变化——新会话首行有头）：SessionTest×2（磁盘行数断言改数头行/事件行）；治理 Prune/Spill×2（「治理不写会话日志」断言从"文件字节=0"改为"治理前后字节不变"——带头的空会话不再零字节）；ExportCommandTest×1（jsonl 导出 3 行含头）。
- 2026-09-26：**测试结果**：SessionFormatTest 8 用例（头往返含 cwd/省略 cwd、头判定容错、迁移链 v1→v2→v3 接力顺序、缺环拒绝、非相邻迁移器拒绝、方向异常、构造校验）；SessionTest 51→59 用例；全仓 13 模块 `mvnw test` BUILD SUCCESS（0 失败）。
- 2026-09-26：**Status 停 in-progress（缺用户手动验证）**。手动验收件：启动 CLI（按 docs 运行 Demo 命令）随便说一句话产生新会话，然后 `head -2 ~/.duo/sessions/<最新id>.jsonl`——首行应为 `{"type":"session","version":1,"cwd":"<启动目录>"}`、第二行起是事件；再开一个 M26 前的旧会话（/switch 或直接看旧文件）确认无头文件行为不变。待四轴审查轮记档（M25 先例：验收后审查）。

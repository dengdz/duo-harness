# 01: 版本头与 cwd 字段——会话格式地基

## What to build

新会话文件首行携带格式版本头（`{type:"session", version:1}`）并落盘 cwd 字段；旧文件读时识别（无头即 v0 直读，磁盘一个字节不动）；迁移链机制建好备用——读端认出版本后在内存中逐级转换（vN→vN+1 接力，隔代可达）；全部"每行必是事件"的既有读者统一过"首行可能是版本头"分流，旧会话的标题静态读、权限档静态读、检索扫描、子会话事件端点、导出行遍历行为不变。

决策依据：[ADR-0028](../../../docs/adr/0028-M26会话数据与检索立项决策.md) 决策二（读时识别，磁盘永不重写）。spec：`.scratch/m26-session-data-search/spec.md`。

## Status
done（2026-09-26 用户手动验收通过——三条对照全符：v0 旧会话续接正常（继续会话 20260912-160555-502c）、/new 新会话首行 `{"type":"session","version":1,"cwd":"/Users/zhangyl/IdeaProjects/duo-harness"}`、追加路径正常（user/message 含验收输入、先于 LLM 错误落盘）。验收过程暴露 LLM 404 一件，经排查与本工单零交集，另档 BUG-20260926-02 跟踪）

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
- 2026-09-26：**Status 停 in-progress（缺用户手动验证）**。待四轴审查轮记档（M25 先例：验收后审查）。

## 验收件（工单级三件套，2026-09-26 备，agent 已在隔离环境完整自跑通过）

**可运行演示**（真实环境，两条命令）：

```bash
# ① 启动 REPL：随便说一句话 → /new 开新会话 → 再说一句 → /exit
mvn -pl duo-harness-example -am package exec:java -DskipTests \
  -Dexec.mainClass=dev.duo.harness.example.chat.ChatReplMain

# ② 看最新会话文件首两行
head -2 ~/.duo/sessions/$(ls -t ~/.duo/sessions | head -1)
```

**预期对照表**：

| 标志 | 应出现 |
|---|---|
| ② 首行 | `{"type":"session","version":1,"cwd":"<启动时所在目录>"}` |
| ② 第二行起 | 事件行（如 `"type":"user/message"`），追加行为与从前一致 |
| 对照：M26 前旧会话 `head -1`（任选一个旧文件） | 首行直接是事件（无 `type:session` 头）——旧文件未被改动；REPL 重启对旧会话仍正常「继续会话」 |

agent 隔离自跑记录（DUO_HOME=/tmp 隔离实例）：新会话首行实测 `{"type":"session","version":1,"cwd":"/Users/zhangyl/IdeaProjects/duo-harness"}`；手写 v0 旧文件启动实测「继续会话 20260101-000000-v0old（已有 2 条消息）」且文件未被改动。测试路径套件叙述：SessionTest（59 用例，含 M26-01 版本头 8 用例段）与 SessionFormatTest（8 用例）@BeforeAll 叙述行，`mvn -pl duo-harness-session -am test` 可见。

## 审查轮（2026-09-26·第 1 轮·四轴）

**范围**：ce21787（f3fe32a..HEAD，17 文件）。**覆盖**：行级轴 9 主代码文件全 reviewed（OCR 委托名单）；Standards/Spec/Java 规范轴覆盖全部 17 文件（含测试与文档）。测试收口：全仓 `mvnw test` BUILD SUCCESS。

### 阻断（修复后清零）

- **`WebFace.handleSubagentEvents` 逐行透传无 type 过滤**（Spec 轴）——实现 Comments 里「子会话端点按 type 过滤天然不命中」的论证与代码事实不符（该端点全量透传），新子会话回放首帧会带出版本头对象。**已修**：读行处跳过头行；WebFaceTest.subagentReplay 用例文件加头行锁定（events 首帧仍是首个事件）。
- **`InvertedSessionIndex` lineNo 把头行计入**（Spec 轴）——新会话 eventIndex 全体 +1，与「Session.append 序号同义」注释矛盾。**已修**：indexFile 跳过头行且不计 lineNo；新增用例 `headerLineNotIndexedAndKeepsEventIndexAligned` 锁定。

### 建议（已修）

- **Javadoc 叠放**（Standards 硬违规 + Java 轴 CRITICAL-1 + 行级轴，三轴同抓）：`formatVersion()`/`cwd()` 插入位置隔断 `jsonlLines()` 的 Javadoc——已知模式（M23 工单 10 模式 1）第 2 犯，本次是「插入位置」型变体。已修：访问器移至 `jsonlLines()` 之后，注释归位并补 M26-01 句。
- **SessionFormat 字段名字面量重复**（Java 轴 CRITICAL-2，C-01）：`FIELD_TYPE`/`FIELD_VERSION`/`FIELD_CWD` 常量化。
- **load 非首行头行被静默吞**（Spec 低危 + 行级轴）：收紧为 fail-loud（「版本头不在首行（文件损坏）」），SessionTest 新增 `loadRejectsMidStreamHeaderLine`。
- **子会话 cwd 取进程目录**（行级轴）：改为继承父会话 cwd（父无 cwd 回退进程目录）——检索授权父子同域；SubagentManagerTest 新增 `childSessionInheritsParentCwd`。
- **ArrayList 容量**（COL-14）：load 的 eventLines 按行数上界初始化。
- **架构文档**：模块划分 session 包列举补 `SessionFormat`。

### 豁免记档（两处，发现级非轴级）

- **O-16 字段 final 化**（Java 轴 MAJOR）：`formatVersion`/`cwd` 只能在取锁读文件后赋值（lock 工厂 → 持锁通道读取的既有顺序），final 化需重构构造链；实际赋值完成于实例发布前（lock 经 `synchronized(LOCK_GATE)` 返回），理论可见性缝隙的读偏后果 = null cwd 不进检索（ADR-0028 决策三下漏收不越权）。成本与风险不成比例，记档豁免。
- **load 逐行双重解析**（Java 轴 MAJOR-1 + 行级轴 low）：`isHeaderLine` 与 `parse` 各一次 readTree——两段结构由迁移链语义决定（migrate 作用于原始行串，须先收集后解析）；load 为会话打开时的一次性低频路径。记档豁免。

### 记 backlog

- `Path.of(System.getProperty("user.dir"))` 全仓 16+ 处同形（本 diff 新增 11 处）——02/03 工单动检索与 cwd 消费侧时评估提取单一取值点（M24-08「重复到阈值即提取」先例）。

### 四轴分列

- **Standards 轴**：1 硬违规（Javadoc 叠放）+ 2 判断性（user.dir 重复→backlog；架构文档→已修）；过滤 4 项误报均给理由（迁移链投机泛化被 ADR-0028 文档化标准覆盖等）。
- **Spec 轴**：2 主发现（子会话端点未分流、eventIndex 错位——均为实现论证错误）+ 1 低危（非首行头宽容→已收紧）+ 轻微 scope creep 2 项（工单已记档）；11 处 cwd 落盘、迁移链边界、旧文件不动等核对通过。
- **行级规则轴**：9 文件全 reviewed，5 条 low（Javadoc 错位/双解析/边界宽容/线程安全理论性/cwd 继承），无 critical/high；核心新逻辑行级正确。
- **Java 规范轴**：CRITICAL 2（注释同步 O-13、魔法值 C-01）+ MAJOR 3（双解析/O-16/COL-14）；丢弃 9 项候选均给理由（中文测试方法名等仓库惯例优先）。

### 测试覆盖评估

核心路径（头写入/读分流/迁移链/导出等价）有用例；审查补 4 用例（子会话回放不含头、eventIndex 对齐、非首行头拒绝、cwd 继承）——审查发现的两个实缺陷均已有回归锁定。

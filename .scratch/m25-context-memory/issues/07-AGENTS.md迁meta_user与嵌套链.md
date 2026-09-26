# 07: AGENTS.md 迁 meta_user + 嵌套子目录链（M7#3 销账）

## What to build
AGENTS.md 加载链升级（ADR-0024 M25 第三块后半）：① 注入通道从现行迁移到 meta_user（与 cacheControl 断点的身份段划分同改——AGENTS.md 属稳定身份段）；② 嵌套子目录 AGENTS.md 链生效（子目录约定对相应目录下的操作可见）；③ fs 操作后增量发现（会话中目录/文件变更后链可更新）。同 diff 销 docs/limitations.md M7#3 条目并记 CHANGELOG（doc-standards 惯例）。

M7#3 原文口径：「仅用户全局 + 项目根两文件；嵌套子目录链与 fs 操作后增量发现未做 → M25 搭车」——本票把两件未做项都收掉。

## Blocked by
06

## Status
done（2026-09-26 用户双路径验收通过——套件 17 用例绿 + 真机子目录约定演示：模型以【子目录约定生效】开头作答并自识 AGENTS.md 链）

## Checklist
- [x] AGENTS.md 注入迁 meta_user 通道（与 cacheControl 身份段协同，同改同测）
- [x] 嵌套子目录 AGENTS.md 链生效（端到端：子目录约定对该目录下操作可见）
- [x] fs 操作后增量发现（会话内变更后链更新，行为口径工单期按 M7#3 记录定）
- [x] 同 diff 删 docs/limitations.md M7#3 条目 + CHANGELOG 记销账
- [x] tdd 红绿循环（链加载 + 增量用例，seam 见 Comments 记档）

## Comments
- 2026-09-26：**机制裁定（ZCode request-user-context 同构）**：①通道迁移——AgentsMdPlugin 从注册 prompt 片段（system）改为发布 "agentsMd" 链服务（AgentsMdChain，不可变无状态），`<agents-md>` 段以 user 角色置于请求消息序列最前（先于 `<memory>` 段：项目约定在前、记忆补充在后），附"可能不全面相关、以用户为准"免责语，请求视图专用不落会话日志；②嵌套链——用户全局 → 项目根 → 项目根到 cwd 每层 AGENTS.md 浅到深拼接（由泛到专，靠近根=全局约定、靠近 cwd=局部细化）；③fs 增量发现——**每请求现发现现读**（强于 M7#3 记录口径的"操作后刷新"：会话中新建目录/文件下一轮即入链，零刷新机制）；④cacheControl 协同——迁出 system 后稳定身份段按构造自动收敛（userPrompt+技能清单+记忆指南），meta_user 段落在动态段断点（末条消息）的缓存前缀内，"同测"= 段序用例 + system 负向断言（system 不含链内容/标签）。
- 2026-09-26：**story12 口径记档**：「子目录约定对该目录下操作可见」按 ZCode 同构参考语义落地为 **cwd 锚定链**——链按发起时 cwd 下探，工具操作发生在更深层子目录时不追加该层约定（cwd 不随工具调用移动）；链不缓存（每请求重扫），新会话/cwd 变更后的请求自然取到新链。若后续需要"read 深层文件时叠加该层约定"的按操作下探，属新工单（挂 08 收尾时评估是否入 backlog）。
- 2026-09-26：**TDD seam**（自主模式按 spec Testing Decisions 定）：①AgentsMd 纯函数 9 用例（既有 5 升级链语义 + 嵌套多层/中间缺失/fs 增量/metaUserSection 形态）②装配 3 用例（服务发布/段现读/零注入/budgetChars，duo.home 重定向隔离本机环境）③注入段序 5+1 用例（agents-md → memory → 历史倒插、不落会话、system 负向断言）。实现期一处 M7 旧语义用例改写（忽略子目录 → 链语义）。
- 2026-09-26：全量 992 用例 0 失败（2 既有 skip）。M7#3 销账（limitations 行删除，CHANGELOG 记账）；M7#4 子代理不注入口径不变（走旧构造恒 null）。
- **验收通过（2026-09-26，用户双路径实测）**：路径 A 套件——三行叙述（5+9+3 用例）精确出现、无 ERROR；路径 B 真机——subdemo/AGENTS.md 写独特约定后 cd subdemo 启动 CLI，问「按你收到的目录约定，回答应该以什么开头？」模型答「【子目录约定生效】——按 AGENTS.md 链中的约定…」——**行为与自我标识双对**（嵌套链注入生效 + 模型知道内容来自 AGENTS.md 链）。清场完成。工单转 done。
- **待用户验收（转 done 前最后一步）**：
  1. 测试套件（命令含旗标）：`./mvnw -q -pl duo-harness-agent -am test -Dtest='AgentsMdTest,AgentsMdPluginTest,MemoryInjectionTest' -Dsurefire.failIfNoSpecifiedTests=false`——3 套件（9+3+5=17 用例）全绿；
  2. 真机子目录约定演示：仓库 `docs/` 目录下建 `docs/AGENTS.md` 写一行独特约定（如"本目录文档一律先给结论"），启动 CLI（命令块整块复制）问「按 docs 目录的约定，回答应该什么样？」——模型应引用该约定（cwd=仓库根，docs 不是 cwd 子层——注意：CLI cwd 在仓库根，链只下探到 cwd，docs/AGENTS.md 不入链！演示要换 cwd：`cd docs && mvn ...`（mvn 从子目录跑 -pl 需 -f ../pom.xml）或简化为在仓库根下建子目录验证）。**修正后的演示**：`mkdir -p subdemo && echo "约定：回答以【子目录约定生效】开头" > subdemo/AGENTS.md`，然后 `cd subdemo` 并从那里启动 CLI（mvn 命令加 `-f ../pom.xml`），问「你的约定要求回答什么样开头」——应答【子目录约定生效】；验完 `cd .. && rm -rf subdemo`。
- 

## 审查轮（2026-09-26·第 1 轮·四轴两路合并）

**覆盖**：16 文件 = 已审 16 + 跳过 0（行级主代码 7/7 100%）

### 阻断
- **`docs/04-架构/模块划分.md:80` 机制表行漏改** [Standards，红线 3]（同文件包边界行已改、机制表行残留旧"注册为 agents-md 片段"）→ **已修**：链口径新行
- **`docs/05-参考/术语表.md` 新旧词条自相矛盾** [Standards]（旧词条"AGENTS.md 注入"描述废弃形态，新词条 _Avoid_ 恰列其为避用词——与 04 重复词条同族）→ **已修**：旧词条改写为链语义承载，删后加的重复词条（合一）
- **`AgentsMdPluginTest` 依赖本机 ~/.duo/AGENTS.md** [Standards]（零注入用例用户全局取真实 DuoHome，本机文件存在即假失败）→ **已修**：三用例统一 duo.home 重定向隔离（FsBashToolTest 先例）

### 建议
- **`CacheControl.java` 稳定身份段示例列 AGENTS.md 失真** [Standards+Spec 共振] → **已修**：javadoc 更新（AGENTS.md 已迁 meta_user 标注）
- **"同测"缺 system 负向断言** [Spec] → **已修**：段序用例补两断言（system 不含链内容/标签）
- **story12 cwd 锚定口径未记档** [Spec] → **已修**：Comments 专项记档（cwd 锚定链、按操作下探属新工单）
- **readQuietly 现读期 IO 抛UncheckedIOException 中止 send** [行级] → **已修**：IO 异常降级 null 缺层（链故障不破坏对话轮，MemoryBook 同款原则）
- **运行Demo/组装指南旧口径** [Standards] → **已修**：两处同步链口径
- **AgentsMdPlugin javadoc 版本叙述** [Standards（trim-cot-leakage）] → **已修**：删"历史形态"段（CHANGELOG 承载）
- **两参 load 零生产调用 + 魔法值 + candidates 可见性 + budgetChars 三次取值** [Java/行级] → **已修**：删两参 load（插件内联 DuoHome 解析）、FILE_NAME/BUDGET_CHARS_CONFIG 常量、candidates 收 private、局部变量单次取值
- 记档不修：budgetChars ≤0 静默回退（memory 轴已记仓库级议题）/ AgentsMdPlugin 对 inject() default 的同值覆写（风格项）

### 测试覆盖
- 三 seam 17 用例：纯函数 9（链拼接/嵌套/缺失/增量/形态）+ 装配 3（隔离环境）+ 注入 5（段序/不落会话/system 负向）
- 行级轴确认：candidates 路径边界（cwd==root/无前缀关系不可达）、双段倒插四组合终序、14 参重载链全部正确

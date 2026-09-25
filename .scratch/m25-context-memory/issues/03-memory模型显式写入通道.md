# 03: memory 模型显式写入通道（记忆写路径）

## What to build
记忆本的写路径端到端：会话中用户说"记住 X"，模型通过显式写入通道把内容落进 `.duo/MEMORY.md`，下次新会话即生效。写入留会话事件痕（可回放）。交付后的可感行为：本会话让 AI 记住偏好，下个会话不用重复交代。

设计要点：写入机制形态（专用工具 vs 指令约定）按探测工单 10 的 ZCode memory 机制工单期定；用户手改文件与模型写入的并发约定从简（后写覆盖 + 写前读全量）。写入语义 = grill 裁定三：最小版只做显式写，自动抽取挂账。

## Blocked by
02

## Status
done（2026-09-25 用户手动验收通过——写入正例无审批直达、下会话零工具答出验证码、手改不互吞、改删引导四项全过）

## Checklist
- [x] 模型显式写入通道落地（形态工单期定，探测定依据）
- [x] 写入留会话事件（可回放可审计）
- [x] 用户手改与模型写不互相吞（写前读全量）
- [x] 端到端演示：本会话"记住 X" → 下会话生效（自动化层已证；手动演示待用户，命令见 Comments）
- [x] tdd 红绿循环（seam 见 Comments 记档）

## Comments
- 2026-09-25：**形态裁定：专用工具 `memory_write`（追加式）而非指令约定**。依据：① append 在服务端机制层保证并发约定——模型没有整文件覆写面，用户手改不可能被吞（比"后写覆盖+写前读全量"靠模型自觉更强，工单字面的并发约定以此等价满足并记档偏差）；② 工具调用天然落 tool/call + tool/result 会话事件（可回放可审计，checklist 2 零成本满足）；③ 写协议经工具 description + memory-guide 规范段双承载（指令形态，BUG-20260925-02 经验）。治理面：免审批（仅追加用户显式要求记的条目）、独占工具（append 读-补-写三步非原子，走 ADR-0018 屏障串行化——审查四轴共振发现后由并发安全 true 改判）、plan 态白名单外（计划模式不可写记忆）、单条 2000 字符上限。
- 2026-09-25：**TDD seam**（自主模式按先例定）：①MemoryBook.append/entryCount 纯函数（@TempDir：创建即写/补换行/空白拒绝/手改不互吞）②装配级（Boot.from + tools 行：memory_write 在册 + 走真管线 execute 写入即生效 + 注入段联动）③agent 循环事件痕（捕获式适配器触发工具调用 → 断言会话事件流成对落 tool/call + tool/result）。17→18 用例先行红后绿。
- 2026-09-25：**02 挂账兑现**：①guide 注册条件放宽为"读路径可用（记忆本在场）或写通道可用（tools 在场）"——写路径让"首次记住即创建文件"成为正常入口；快照 vs 每请求现读的残余不对称保留（记档于 MemoryPlugin.apply 注释，影响仅"装配后建文件的首个进程内无指南"，工具 description 自足兜底）。②写协议文本已增补进 GUIDE_TEXT（何时写/去重/改删引导手改文件/工具缺席兜底）。MEMORY.md 格式口径 = 每行一条（guide 与工具 description 同步引导）。
- 2026-09-25：全量 954 用例 0 失败（2 既有 skip）。工具目录机械对账（ToolCatalogTest）抓到工具名/描述未同步文档——已补（描述按注册原文逐字）。
- **验收通过（2026-09-25，用户实测逐项对账）**：①写入正例 `[调工具] memory_write` 无审批直达，确认语带条目数；②同会话次问"记忆本里记了什么"零工具直接引用 `<memory>` 段（现读联动 + 采信修复持续有效）；③`/new` 后零工具答出验证码"青鸟-42"（跨会话生效硬判据）；④终端手写行与模型追加三行共存（append 不吞手改）；⑤"删掉某条"模型不写文件、引导用户直接编辑（写协议生效）。验证条目已由用户 sed 清场。工单转 done。

## 审查轮（2026-09-25·第 1 轮·四轴）

**覆盖**：11 文件 = 已审 11 + 跳过 0（行级轴主代码 3 文件，测试/文档由双轴与 Java 轴覆盖）

### 阻断
- **`MemoryWriteTool.java:19` + `MemoryBook.append`** [Standards/Spec/行级/Java 四轴共振]：并发安全 true 的论据"单次 CREATE+APPEND 系统调用"失实（append 实为存在性检查→读尾部换行→追加三步非原子），CON-09 并发修改未串行化 → **已修**：isConcurrencySafe 改 false（独占屏障串行化），MemoryWriteTool/MemoryBook/工具目录三处 javadoc 如实改述（跨进程最坏多一空行、读路径非空行计无数据丢失）
- **`MemoryPlugin.java` 类 JavaDoc 契约失效** [Standards]：仍称"记忆本缺席不注册规范段""写协议随工单 03"——与 guideDue 双条件实现矛盾且前向引用已交付项 → **已修**：类 javadoc 重写（双条件 + optionalInject tools + 写协议已交付），MemoryBook/package-info 同款前向引用一并清理

### 建议
- **`MemoryBook.entryCount` 吞 IOException 返 0 无日志** [Java E-04 BLOCKER/行级] → **已修**：补 LOG.warn 对齐 read() 惯例，失败模式写入 javadoc
- **content 无长度上限** [Java S-04 BLOCKER] → **已修**：MAX_CONTENT_CHARS=2000 硬上限，超限 PluginException 点名拆条
- **超 120 字符行** [Java F-09 CRITICAL] → **已修**：新增代码非文本块行全部折行（GUIDE_TEXT/schema 文本块内容行沿既有范式豁免）
- **"收敛 02 挂账""用户故事 18"注释变更叙述** [Standards/行级] → **已修**：改写为不变量陈述
- **事件痕无本 diff 测试断言** [Spec] → **已修**：MemoryInjectionTest 增 agent 循环用例（断言 tool/call + tool/result 成对落事件流）
- 记档不修：多行条目与"共 N 条"计数口径（写协议引导一句话一条，偏离才触发；entryCount javadoc 已注明按行计口径）/ ObjectMapper 每调用 new（AskUserTool/FsWriteTool 同款先例）/ memory_write 不受档位约束的观察项（专用工具范围窄于 write，档位语义由 workspace 管 bash/write 的格局不变）

### 测试覆盖
- 三 seam 18 用例：append 矩阵 5（创建/补换行/空白拒绝/手改不互吞/现读联动）、注入 4（序首/缺席/未装配/事件痕）、装配 4（服务+规范段/静默降级/预算/写通道+真管线写入）
- 边界：append 空文件/仅空白文件/多换行尾由行级轴逐一验证（实现正确，未单独立用例的分支已由补换行用例覆盖主路径）

---
name: duo-code-review
description: 审查 duo-harness 仓库的代码变更、提交或分支对比时使用。将审查者定向到本仓库的标准（文档同步要求、证据要求、依赖政策、目录分层标准）与代码本身看不出来的检查项，并给出行级意见与覆盖台账。当用户说"审查一下"、"review 这个改动"、"看看这次提交有没有问题"、"审一下这个提交"、"审查暂存/未提交的改动"、"对比分支看看有没有问题"时触发。
---

# 审查 duo-harness 代码变更

**本技能是指导，不是完整清单。** 先看清变更范围（`git diff --stat` / `git show <commit>`），读足上下文代码理解设计意图，再逐项检查。优先级排序：正确性 > 生命周期/并发 > 安全 > 破坏既有行为 > 风格。短而实证的审查优于冗长的 nit 清单。

## 开工前检查（任一不过，先修环境再审）

1. **ocr 在位且可用**：`which ocr` → `ocr llm test`。未安装则 `npm i -g @alibaba-group/open-code-review`；`llm test` 失败时**停下来找用户要凭证，绝不虚构或硬编码 key**（红线 2）。确实要开工就直落委托模式（不依赖 LLM 端点）。
2. **固定点可解析且 diff 非空**：`git rev-parse <fixed-point>` + `git diff <fixed-point>...HEAD --stat`（三点，比 merge-base）。坏 ref 或空 diff 在这里失败，不要留给下游子代理。
3. **双轴前提**：mattpocock `code-review` 需要 `docs/agents/issue-tracker.md`；缺失时提示用户跑 `/setup-matt-pocock-skills`。
4. **覆盖率基数**：`git diff --stat` 的文件全集——OCR 的 reviewable 列表不是全集，见「覆盖率台账」。

## 审查目标 → 命令

业务上下文一律传：本仓库的上下文就在文件里，**优先 `-B` 传 spec / 工单 Markdown**（`.scratch/<feature>/spec.md`、`.scratch/<feature>/issues/NN-*.md`），只有一句话背景才用 `-b`。`--audience agent` 恒用——`human` 会流式刷进度把输出冲烂。`-B` 内容**裁剪到 2000 字符内**（CLI 警告线）——超重背景每请求重复驮载，直接推高 token 与延迟（M18 实测：6438 字符 spec 全程拖带，两跑合计 ~1.85M token）；只传 spec 的"实现决策"节即可，不传全文。

**缺省审查单元 = 单提交 / 单工单**（`-c`）；整期 range（`--from --to`）是例外形态，仅用于合并前终审或用户点名——成本量级见「时间预算」与「何时值得跑 OCR」。

| 要审的东西 | 命令 |
|---|---|
| 工作区改动（含未跟踪） | `ocr review --audience agent -B <spec.md> --exclude '<批次外路径>'` |
| 单个提交（**缺省**） | `ocr review --audience agent -B <工单.md> -c <commit>` |
| 分支 / 提交区间对比（例外：终审或点名） | `ocr review --audience agent -B <spec.md> --from <base> --to <HEAD>` |
| 先看会审哪些文件 | `ocr review --preview`（人读）/ `ocr delegate preview --format json`（机读，带 mode/ref/排除理由） |
| 整文件（无 diff，如新模块） | `ocr scan` |
| 某文件命中哪条规则 | `ocr rules check <path>` |

提交模式下 OCR 用提交信息自动填背景，`-b` / `-B` 是叠加的需求上下文。长报告用 `-o` 落盘，`--format json` 供后续解析。

## 审查流水线（两段式，顺序固定）

1. **第一段——OCR 行级审查（先跑）**：按上表跑 `ocr review`，对 diff 出行级意见。端点大面积失败时按「ocr 执行策略」降级，**不得跳过本段**。
2. **第二段——`code-review` 技能双轴（后跑）**：调用 mattpocock `code-review` 技能（Standards/Spec 双轴并行子代理）。fixed point 取该工单改动前的提交（单工单审 `HEAD~1`，批次/里程碑审分支基点或用户指定点）；Spec 轴 spec 来源 = `.scratch/<feature>/spec.md` 与对应工单文件；spec 找不到时如实报「无 spec 可对照」，不臆造需求。
3. **合并报告**：OCR 行级意见 + Standards/Spec 双轴发现合并为一份报告，阻断与建议分级不变；两轴发现不重排不合并（双轴分离的本意）。

## ocr 执行策略（分批、续跑与降级阶梯）

用户的 ocr 配置有多个 provider / 模型（各约百万 token 上下文），端点会间歇性 403/400/超时。按代价从低到高逐级降，每步留痕：

1. **分批**：全量大 diff 一次跑 40+ 文件失败面大且难定位。用 `--exclude '<批次外路径>'` 按模块分批（先 core、再 tools、再 example），每批独立、续跑代价小。
2. **续跑**：`--resume <sessionId>` 续同一会话的失败项；resume 要求输入未变——审查后又有了新 commit 就重开会话，不强续。
3. **换端点 / 降强度**：`--provider <p> --model <m>` 换一次模型，或 `--effort low` 降强度；命中限流时下调 `--concurrency`。
4. **委托模式**（端点大面积失败，多为模型过期或 key 失效）：按名调用 `open-code-review-delegate` 技能，**不要写死插件缓存路径**（路径带版本号，升级即失效）：
   - `ocr delegate preview --format json` → 取 mode / from / to / merge_base / reviewable_files；
   - `ocr delegate rule --format json <path...>` → 规则按内容分组，**这份规则文本就是行级检查清单**（其中的 `file_read` / `code_search` 映射到宿主自己的读文件、搜代码工具）；
   - 按 mode 取 diff：range 用 `git diff <merge_base>..<to> -- <path>`，commit 用 `git show <commit> -- <path>`，workspace 用 `git diff HEAD -- <path>`（未跟踪文件直接读全文）；
   - 产出与普通模式同结构（path / content / start_line / end_line / category / severity），便于合并进同一份报告。
   委托模式 LLM-free，不依赖 ocr 的 LLM 端点；同时提示用户修复 ocr 模型配置，修复后回普通模式。
5. **兜底**：以上都不通时，缺口由手工维度补位**并在报告中标注**——不因 ocr 缺席静默放行。

## 时间预算（实测基线）

墙钟由**最慢一组的串行链**决定，不是文件总数——组数是并发的硬上限（≥4 个文件才分组；总改动 <200 行并成一组 = 完全串行），`--concurrency` 调过组数没有用。实测锚点出自会话留痕（`ocr session show <id>`，原始数据在 `~/.opencodereview/sessions/`）：

- 单轮延迟按端点/模型差 6 倍（kimi-k2.7-code 8.8s、qwen3.8-flash 16s、kimi-k3 55s）——快模型逐次显式指定，不依赖默认端点；glm-5.3-flash 为慢档（M18 实测：16 文件整期首跑 16m13s、resume 复跑 29m39s，planning 请求两度超 300s）
- 最贵的单次调用是 plan_task（均 123s、最高 268s；单文件 >50 行或组合计 >100 行触发）；`ocr review` 没有 `--no-plan`，只有 `ocr scan` 有
- 41 文件的 range 跑 30.3min，其中最慢一组（example 演示类 + yml）独占 28.2min；101 行的 app.js 单文件实测 9.9min；该 range 跑还有 13% 的工具调用是同参数重复（同一文件 file_read 5 次）
- 端点突发失败每次空转约 22s；8 并发同秒打同一网关 → 全部 429，整轮白跑

赶时间时的让步顺序（各条独立，可叠加）：

1. **排除低价值高消耗面**：`--exclude 'duo-harness-example/**,**/resources/**'`——demo 主类与前端资源是最慢组常客；被排除文件仍进覆盖率台账（附理由跳过或由手工维度覆盖）。
2. **按提交 / 工单审，不按整分支 range**：同一范围（`main..0.10.0`）历史上被重审过 6 次；工单落地即增量审。
3. **`--concurrency 3~4`** 适配网关限流——组数才是真上限，代价很小。
4. **`--max-tools 50`**：最慢组曾跑 93 轮（上限 100），链长直接决定墙钟；已产出的意见仍会收集，收尾有 grace round。
5. **`--no-filter`**：每组省一轮过滤（均 14.7s）；多出的假阳性由宿主手工维度把关。
6. **中断续跑不重跑**：`--resume`（用法见上节）；历史有 3 个 aborted 会话被整轮重跑。

组合示例（分支轮用；单提交换成 `-c <commit>`）：

```bash
ocr review --audience agent -B <spec.md> --from <base> --to HEAD \
  --provider <快端点> --concurrency 3 --max-tools 50 --no-filter \
  --exclude 'duo-harness-example/**,**/resources/**'
```

## 何时值得跑 OCR（决策指引）

**甜点区 = feat 提交的合并前门禁**：新鲜代码（未经双轴过筛）、单元小（单提交组少链短、planning 轻或不触发）、10 分钟级成本——此时 OCR 的行级规约面（并发、句柄、SLF4J 陷阱、死代码）是双轴之外的真增量。

**已双轴审过的代码再跑 OCR，边际产出只有边缘与琐碎**（M18 实测，2026-09-19：双轴过筛后的 16 文件整期二遍审，46 分钟 / ~1.85M token，产出 7 条 = 1 条双轴近亲漏网的边缘 bug + 1 条生态盲区（上游格式三值语义，仓库内审查结构性看不到）+ 5 条 IDE 档琐碎）。二遍审的合理预期就这个量级——值不值按"是否还想赌边缘与生态盲区"定，而不是按"再审一遍更保险"定。

**整期 range 仅两场景**：合并前终审（此时上述边际产出正是要买的东西）、用户点名。跑前按「时间预算」的组数 × 端点档估时，超 15 分钟量级先向用户报预算再开跑。

## M18 实测补遗（2026-09-19：16 文件 resume 复跑 29m39s 完成）

- **`--resume` 派生新会话 id**：旧 id 的 status 永久停在 failed/aborted，`session list` 的旧行是陈旧显示；判断存续以「进程存活 + 新会话 jsonl 增长」为准（本轮两度按旧 id 误报"进程已退出"）。新会话首事件带 `resumedFrom` 指回旧 id。
- **进程树是两层**：node 外壳（`~/bin/ocr`）+ 原生二进制（`.../bin/opencodereview review`）。外壳可先于 worker 退出；`pgrep -f "ocr review"` 只命中外壳——监控 worker 用 `pgrep -f "opencodereview review"`（其命令行不含 "ocr" 子串）。
- **首次尝试失败 ≠ 最终失败**：`llm_error`（context deadline exceeded）后内部立即重试并可能成功——本轮 21 请求 2 次首试失败（hooks 组 planning + core review），最终 16/16 全覆盖。判读覆盖以 `review_item_done` 清单为准，不以 retry 汇总的 "failed" 字样为准。
- **参数实测**：`--concurrency 3 --max-tools 50 --no-filter --exclude '**/pom.xml,duo-harness-example/**'` 下 resume 复跑（复用 11 + 重试 5）29m39s 完成、产出 7 findings；`--no-filter` 未观察到假阳性放大。
- **事件词汇表**：`review_item_reused`（缓存复用）/ `review_item_done`（本轮审毕，含 comments 摘要）/ `llm_error`（首试失败待重试）/ `session_end`（收尾）——监控进度按这四类算，不数 `session list` 的状态列。

## 覆盖率台账（防静默遗漏）

**基数取 `git diff --stat` 的全集，不是 OCR 的 reviewable 列表**——OCR 默认只审生产源码：`src/test/**`（排除理由 `default_path`）、文档与 `.scratch/**.md`（`unsupported_ext`）一律不在名单里，实测即使加自定义 rule 也捞不回来。测试与文档恰是本仓库证据链的重头，必须由手工维度接管。

每个文件两个终态之一，没有第三种：

- **已审**——行级意见或手工维度已覆盖，报告里能定位到该文件；
- **跳过**——附具体理由（端点失败 / 非本次变更 / 已由 XX 维度覆盖）。

报告给出`总数 / 已审 / 跳过 / 覆盖率`。跳过理由是空白的文件不允许出现在报告里。

## 事实来源（读原文，不要凭记忆转述）

- `docs/` 与 `README.md`：对外承诺的行为。**文档与代码不一致按阻断处理**——API 签名、默认值、行为描述对照源码核实，不信转述。
- 已知限制清单 `docs/limitations.md`：变更若消除了某条限制，文档必须同步删除该条。
- 决策记录 `docs/adr/`：历史决策的载体。与既有决策相抵触时是设计讨论，不是自动否决——但要在报告中显式提出。
- 通用 Java 规约（命名 / 异常 / 并发 / 日志）：交由 OCR 行级审查覆盖，不重复手工逐条过。

## 分级

**阻断**（未改不通过）：编译或测试不过；行为与文档、Javadoc `@throws` 与实现不一致；并发正确性（竞态、取消、句柄泄漏）；破坏既有公开契约；与根 AGENTS.md 红线冲突；引入未获同意的新依赖。

**建议**（带上即可，改不改由作者定）：命名与风格；包组织与类堆放；投机泛型与无关功能；可读性与注释表述；测试强度可加强处。

**丢弃**：已被绿灯测试覆盖的重复问题、抓不住上下文的 nit、纯格式——无声丢弃，不进报告。

文档核对类发现用词表：**错误**（编译不过 / 行为矛盾 → 阻断）/ **不准确**（措辞偏差 → 建议）/ **遗漏**（应有未覆盖 → 按影响归级）/ **确认**（抽查一致项，不占阻断位）。

## 手工检查维度

前七项 OCR 碰得到但判不了本仓库的标准；后两项 OCR 完全看不到（测试与文档不在 reviewable 名单），属于必查项。

- **意图与接口契约**：每个改动的接口两侧都追一遍。实现是否匹配意图；错误、取消、资源所有权是否处理。
- **并发与生命周期**：先识别本仓库的并发惯用模式（线程模型、串行化点、取消标志），再逐项核对：发布前竞态、await 期间取消、回调重入、`finally` 清理完整性、迟到回调是否被标志挡住。订阅/回调句柄必须在 `finally` 释放（内存泄漏）；回调链的 onNext/onComplete/onError 恰好一次且串行。
- **SLF4J 陷阱**：尾随 Throwable 参数不填充占位符，必须显式 `toString()`。
- **能力与消费方匹配**：新增公开方法若只有一个内部调用方，质疑是否应为 private；反之，通用服务上出现消费方专用行为也是泄漏。
- **范围与必要性**：每个新抽象/状态机/选项/兼容路径映射到当前生产消费方。挑战无关功能与投机泛型。
- **包组织与目录分层**：新类落位对照 [duo-project-structure](../duo-project-structure/SKILL.md)：按功能域分包、禁止 `controller`/`service`/`util`/`impl` 大筐、根包不放类、新包同 diff 带 `package-info.java`。数一下 diff 涉及的包的类数，越过约 10 个的拆包阈值时要求按功能边界拆子包（不按文件类型拆）。堆放类问题按建议报，不与语义问题争位。
- **配置默认值**：每个默认值问"什么当前消费方证据或先例支持它"。没有证据时要求显式决策或推迟。
- **测试强度**（OCR 盲区）：断言应在预期回归上失败；验证外部状态（事件顺序、流接收顺序）而非复述实现。mock 必须回放真实组件的异步语义——同步 mock 会让超时与竞态逻辑永不生效。新增生产代码是否带上了覆盖它的用例，对照 [duo-pre-push-checks](../duo-pre-push-checks/SKILL.md) 的证据要求。
- **中文注释与文档**（OCR 盲区）：新注释是否泄漏推理过程（审查编号残留、"本次修复"、变更叙述）？用 [duo-trim-cot-leakage](../duo-trim-cot-leakage/SKILL.md) 判定。

## 报告发现

陈述：缺陷、位置（文件:行号）、影响、证据。局部缺陷给到最紧的 diff 范围；跨切面问题（架构/范围）用总评。**阻断与建议分离**，已由绿灯测试覆盖的问题不再重复。收到别人的审查意见时逐条技术性核实或反驳，不做表演性认同。

```markdown
## 审查报告：<范围，如 m18 工单04 / 0.13.0 分支>

**覆盖**：N 个文件 = 已审 X + 跳过 Y（覆盖率 Z%）
跳过：<path> — <具体理由>

### 阻断
- **`path:line`** — 问题陈述
  - 影响：…
  - 证据：<命令输出 / 代码 / 文档原文>

### 建议
- **`path:line`** — 问题陈述（可选：修法）

### Standards / Spec（双轴，原样并列，不重排不合并）
### 事实核对
- 确认：…（抽查一致项）
```

无发现时收口：「审查完成：N 个文件，阻断 0 / 建议 M。」——不写填充段落凑篇幅。

## 收尾核对

- **看退出码不够**：读 stderr 的 warning 定位失败文件；`--max-tokens-budget` 耗尽时部分结果也 exit 0，别把 exit 0 当全覆盖。
- **回看实际覆盖**：`ocr session list` / `ocr session show <id>`；修复前后两轮可用 `ocr session compare` 对比。
- **修复权限闸门**：用户只说"审查"就不动手改代码；说了"审查并修"才改，且改完经用户确认再提交（红线 1：未经确认不 commit）。

## 顺手的杠杆与陷阱

- **输出别过管道缓冲**：`| tail` / `| head` 会把进度攒到进程退出才吐——中途黑盒直接导致误判挂死误杀（M18 首跑 14 分钟误判即此因）。长跑一律 `-o 落盘 + 输出直接重定向到日志文件`，进度判读走日志尾部与 `session show` 的事件流（见「M18 实测补遗」）。
- workspace 模式把**未跟踪文件**也卷进来：`.scratch/` 下的草稿工单会被当新文件审，按目录 `--exclude` 或改用 `-c` / `--from --to` 收窄。
- 大 diff 会被 token 上限截断：`--max-tokens` 调单组上限，`--max-tokens-budget` 限总量。
- 时延与并发：`--timeout` 单位是**分钟**（默认 15，组级）——实测罩不住单请求挂死（曾有一次 plan_task 请求 24 分钟无响应），按请求设硬上限用环境变量 `OCR_LLM_TIMEOUT`（单位秒）；`--concurrency` 默认 8，`--max-tools` 最小 50 轮。
- 意见像是被过滤过头时，加 `--no-filter` 看原始结果。
- 规则解析优先级：`--rule` > `<repo>/.opencodereview/rule.json` > `~/.opencodereview/rule.json` > 内置。本仓库暂无 `.opencodereview/rule.json`，仓库标准由本技能承载。
- 意见语言跟随 ocr 配置的 `language`（当前为中文），不要在提示里另指定语言。

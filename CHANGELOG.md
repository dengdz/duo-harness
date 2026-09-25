# Changelog

本文件记录 duo-harness 的用户可见变更。版本号规则见 `.agents/skills/duo-workflow/references/版本号.md`。

## 0.19.0（未发布）

### Added

- **标签级会话绑定（M24 工单 07，ADR-0026 决策五延伸）**：浏览器每标签生成持久 tabId（sessionStorage，F5 刷新保留同会话）随全部 /api 请求上报（X-Tab-Id 头 + SSE 查询串双通道，同鉴权令牌口径），服务端按标签多会话并存——新标签打开默认新建会话（不弹选择页，互踩隔离优先）、服务端重启后旧标签（tabId 无记录）等同新标签；会话事件流按标签路由，A 标签的对话与审批/提问卡片不弹到 B 标签，审批挂起 fail-closed 按标签选择性拒绝（A 离场不牵连 B 的待答审批）；/new 与 /switch 只改发起标签（resume 仍走侧栏手动入口），该标签 turn/命令互斥执行中拒换绑（busy 守卫，防换绑关闭正在写入的会话）；无 tabId 请求（curl/缓存页）行为同单会话时代。M8#4「多标签互踩」销账，完整多会话协调仍属 1.0 后

- **计划模式硬禁（M24 工单 04，ADR-0026 决策三）**：plan 态非白名单工具定义不注入模型请求（模型不可见）+ pre-execute deny 兜底（异常路径到达即拒，理由经 tool/result 回模型，成对落日志无悬置态）；白名单 = 只读探索四件（read/glob/grep/read_image）+ web_fetch/web_search（维持只读档 ask 语义不放开）+ exit_plan_mode（批准闭环恒在）+ ask_user/todo_write（无副作用交互/状态件）；bash 经只读判定器参数级裁决（plan 态只读命令放行、写命令拒、判定器缺席 fail-closed）；批准（exit_plan_mode 获批）后工具全量恢复、续接恢复激活态场景同源生效；M7#1「引导式不硬禁」销账（术语表口径已改写）

- **/effort 思考等级四行映射（M24 工单 10，ADR-0026 决策六/七）**：CLI `/effort` 四档归一 off/low/medium/high（缺省 medium，无参显示当前档与映射说明、非法档拒切）；切换落 `model/effort` 会话事件、swap 换链下一轮对话生效；请求参数按 provider 四行映射——anthropic `thinking+budget_tokens`（off 关闭；low 2048 / medium 8192 / high 16384，max_tokens 保持不小于 budget+1024 以满足协议约束（low 档维持缺省 8192））、openai-compat `reasoning_effort` 直传（off 不带该字段）、glm `thinking.type` 开关二值化（off=disabled，low/medium/high 均=enabled）、deepseek 显式降级标注（档位不落任何参数，提示「思考请切 reasoner 模型」）——不支持不静默；辅助性请求（标题生成）强制 low 档（请求级覆盖直达适配器），不随用户 high 档烧大钱

- **/model 白名单切换与 resume 意图（M24 工单 09，ADR-0026 决策六）**：`~/.duo/config.yml` 的 `llm:` 段新增 `models` 白名单清单；CLI `/model` 无参列出清单与当前模型、带参白名单内切换（清单外拒切——模型名决定成本面）；切换落 `model/intent` 会话事件（保存意图）、swap 换链下一轮对话生效（首期限同 provider）；resume 续接时意图 ≠ 当前模型仅横幅提示不自动切（防成本意外）

- **MCP 命名哈希与耗尽终态（M24 工单 05，ADR-0026 决策四）**：MCP 远端工具名一律规范化 + 原始名短哈希后缀（`mcp__<server>__<tool>__<hash8>`）——清洗坍缩的异名工具共存不抛错，名字跨重启/跨服务器组合稳定；重连预算耗尽后通知注入收件箱（模型与用户可见）+ Web 状态面新增「连接器」区块标注各服务器连接状态

- **Web 鉴权令牌（M24 工单 06，ADR-0026 决策五）**：Web 面默认开启鉴权——启动生成随机令牌（SecureRandom 192 位）并在控制台打印带 token 的 URL，浏览器首载存 localStorage、后续 HTTP 头 + SSE 查询串携带，校验失败（含静态资源）一律 403 fail-closed；`web.auth: none` 可显式关闭，关闭时启动横幅警示；token 进程生命周期一次一发无过期。子资源 URL 由服务端注入 token（link/script 不继承父页查询参数）

- **provider 声明与 Anthropic-messages 适配器（M24 工单 08，ADR-0026 决策七）**：`~/.duo/config.yml` 的 `llm:` 段新增 `provider` 四值声明（openai-compat 缺省 / anthropic / deepseek / glm）——决定适配器选型、鉴权头形态与思考等级映射策略，provider 不再由 baseUrl 隐式表达；新增 Anthropic-messages 协议适配器（SSE 流式、system 单列、tool_use/tool_result 块映射、`x-api-key` + `anthropic-version` 鉴权头，max_tokens 缺省 8192）；不声明零改兼容现状，非法值启动即 FAILED 点名

- **审批卡「总是允许」与规则生成（M24 工单 02，ADR-0026 决策一）**：审批卡四值决策——CLI 键位 y=允许 / a=总是允许（项目）/ s=仅本会话 / n 或空回车=拒绝，Web 卡片对应四按钮；按 a/s 生成 allow 规则（bash 取命令首词粒度、非 bash 工具级；项目级写 `.duo/settings.json`、会话级落 `permission/rules` 事件并 resume 恢复）；高危十根命令（sudo/su/doas/rm/dd/mkfs/chmod/chown/shutdown/reboot）双拦——卡片不出现总放行键、手写 allow 运行时也不生效（命中走 ask/档位并日志说明）；Web 审批回填改按卡片 id 精确关联（销 M23 按位置回填的错卡坑）

- **只读 bash 免审批（M24 工单 03，ADR-0026 决策二）**：38 个 allowAnyArg 只读命令（cat/ls/grep/head/tail/lsof 等，逐条核对无写旗标，探测建议「约 30」的落地清单）+ git 四件套 status/log/diff/show 自动免审批放行——只读探索不再弹审批卡；git 必叠 cwd `.git` 存在性信任分类（防 `git -C` 逃逸）；命令词只认裸名（`/bin/ls`、`./cat` 等路径前缀不认，防同名二进制借道）；管道、命令替换、重定向、变量展开等复合结构 fail-closed 照常审批；deny 规则恒优先压过只读放行（`deny bash ls*` 仍拦得住 ls）

- **权限规则引擎地基（M24 工单 01，ADR-0026 决策一）**：两级作用域的持久审批规则——项目级持久于项目根 `.duo/settings.json` 的 `permissions` 段（首个项目级设置文件，重写保留文件内其他键），会话级随会话事件流持久（`permission/rules` 事件 + resume 投影恢复）；`Bash(prefix:*)` 词边界前缀匹配（`ls:*` 不误吞 `lsof`）；裁决序落审批链最外层——deny 手写恒优先（查全部命令）、命中短路不再弹审批卡；`/permission rules` 命令面（list 两级清单 / rm 删除，项目级重写文件、会话级落事件快照）；新挂 `permission-rules` 插件行启用，不挂载装配零感回退

### Changed

- **C1 统一重放演练与收口（C1-12，C1 终局）**：统一重放演练——选案 c98e7c7（BUG-20260923-01 Web 抢占会话修复）四轴全轴重放（外部 diff 首跑，11 项发现零阻断，Java 规范轴 11 候选全被惯例优先过滤）+ bug-ledger 补档回放（BUG-20260923-01 当时未建档，按新模板四步判据以历史事实回填）；**新旧行为对照**：该修复当时未走四轴、未建档，新流程重放给出 11 项当时未记录的检出——改写后流程的增量检出能力实证。机械核对全绿：16 SKILL.md YAML 两键无分流字段、显式调用目标 16 个全可达、Avoid 四词清零、路由双向 8 组抽样全命中、ADR 对账 27+1、docs:build 通过。工单 01–13 全 done 零遗留；统一推送（12+1 笔）按用户裁定执行；C1 全程零 Java/yml/pom 触碰，与发版完全解耦

- **duo-release-workflow 改写 + 词汇治理收口（C1-11，改写组末单）**：release-workflow——description 169→62 字、八步各带完成判据（共 9 处）、 operative 引用统一显式调用（4 处：duo-code-review / duo-doc-standards / duo-pre-push-checks / 合并重复）、租约守卫改指针承接唯一硬护栏、需求分级与审查方式两表资产无损、引导态 fail-fast；词汇治理收口——domain.md 新增「技能用词」节（8 行首选 + _Avoid_ 表，覆盖对比报告 §5.4 清单）、**AGENTS.md 新增红线 9**（SKILL 用词受术语表「技能写作域」约束，不引入表外同义词）、路由行补"准备发布"（1.4 对账）；**词汇抽查 seam 全库达成**——Avoid 清单四词（硬判据/任务卡/渐进式加载/按需加载）grep 15 技能 + AGENTS.md 清零（"按需加载"改"按需载入"、ticket 4 处为技能名专名豁免）；规范 3 处锚点快照化；审查 11 项（修 10 / 记档 2）

- **duo-skill-evolution 瘦身 + duo-java-review 入口适配（C1-10，改写组第六单：全库最大 sediment 手术）**：duo-skill-evolution **328→57 行**（-83%）——删除第六节两个使用示例（与三、五节换皮重复）、4.1/4.2 操作步骤并为"选择进化方式"一节（A 默认/B 谨慎 + 三条件 + 查重 + 组合使用）、输出模板"达，"人格前缀 5 处清零（分层解耦声明：全局规则改动时本技能零修改）、"不要过度 X"三连否定并一句正向原则（"进化以『下次少犯一个已记录的错误』为唯一收益标准"）、红线 8 机制复述改指针形态；description 92→73 字；复盘六维表语义回补（结果质量/顺畅度/错误处理/体验/复用/一致性）；自身 experience.md 增独立"复盘维度"段（红线 8 首跑触碰点，Spec 轴抓回的规范位置缺失）。duo-java-review——description 153→68 字、概述节增与 duo-code-review 的衔接段（Java 规范轴显式调用 + 三约束同源声明），L0/L1/L2 结构与全部规则文件零触碰（spec Out of Scope：不增删阿里手册条款）；重放 seam——本单收尾复盘即新版首跑（真实任务、无前缀输出、方式 A 落盘）

- **duo-tracker / doc-standards / prose-standard / project-structure 四技能改写（C1-09，改写组第五单）**：tracker——description 181→72 字、三级外链改"读它并按其执行"显式指引（M2）、"提示用户显式调用"升门禁形态（出示并等键入）、"不留副本"改"只留指针"正向形态、场景表 5 行保留、新增完成判据；doc-standards——description 144→77 字（分工句迁正文）、三处跨技能引用改显式 Skill tool 调用（trim×2 + prose）、导航对账写死"N=24 份 ADR"改差值判据（"判据是差值不是 N"，防过期——机械命令校验时发现）并补完成判据；prose-standard——description 191→80 字（分工句删，协作节承接）、输入与排除节 fail-fast 化（scope 缺失即停/空产物即结束，两个否定性保证正向承载）、**协作节增《技能写作规范》指针行**（ADR-0027 决策一，Spec 轴阻断抓回的漏项）、末尾换行符修复；project-structure——description 159→79 字（禁令删，正文拆包阈值承载）、结构审查节补 fail-fast 边界确认、判例节 5 条无损、弱指针改显式调用；三 seam 完成——手写工单 13（tracker"顺手记"分支真实走通，.scratch/c1-skill-writing/issues/13）、文档归属咨询按 doc-standards 上站范围条款判定（对账 27+1 实测）、结构评审对 .scratch/c1 目录归属判定（可辩护形态）；审查 8 项全修（含阻断 1）

- **duo-bug-ledger / duo-pre-push-checks / duo-research 三技能改写（C1-08，改写组第四单）**：bug-ledger——"反模式（不要做）"节四条禁令改为四步完成判据 + 建档边界节（否定性保证清单法首用：症状原样引用/机制层根因/防复发具体动作/NPE 继续承诺全数正向化，禁令 0），diagnosing-bugs 改显式 Skill tool 调用，description 224→72 字（触发按概念收敛）；pre-push-checks——失败处理判据化（环境举证三件：命令/失败点/平台差异 + 绕过须用户同意）、推送流程四步补判据、"裸 --force"标注唯一硬护栏、触发改概念收敛（94→73 字）；research——`../../../` 三级外链清零（改显式指引与 Skill tool 调用）、锚点核对新增两类条款（git 检出类拉最新记 SHA / 插件缓存类版本号锚点核对文件数——C1-01 mattpocock 先例入条款）、description 176→75 字；AGENTS.md 路由行 11/13 同 diff；三重放——BUG-20260925-01 真实建档归档闭环（本单审查发现的保命题缺陷现场走新流程全四步）、推送证据表挂账工单 12、研究锚点流程以 C1-01 为回放案例四要素全中；审查 10 项全修（防复发三问定义丢失、平铺先例例外丢失两处保命题由审查抓回并建档）

- **duo-acceptance 与 duo-comprehension 改写（C1-07，改写组第三单："补缺口不推倒"）**：acceptance（判据范本标杆）——description 181→79 字、第一步补完成判据、第三步交用户运行升阻塞步骤（等用户答复，未获答复不进入收尾）、"不得以测试是绿的反驳"标唯一硬护栏（正向目标同句）、演示形态补文档工单等价形态（机械核对命令），三件套判据/IDEA 等价路径/字面示例资产逐字无损；comprehension——description 203→69 字、"课程清单先给用户过目"括注升格为第二步阻塞步骤（规范 M4 反例本体消除）、四步扩六步各带完成判据（共 6 处）、闸门句标唯一硬护栏，题目四规则/产物模板/好题示例资产无损；AGENTS.md 路由行 19/20 同 diff 补触发词；双重放完成——本单按新 acceptance 三件套文档工单形态走（机械核对命令出示 + 用户确认即证据）、comprehension 走到课程清单阻塞点出示并等确认；审查 7 项（修 4，两处边界记档：口语变体单列、自造领先词不配英文锚）

- **duo-code-review 改写（C1-06，改写组第二单：治理最强/指针最弱/沉积最重的三重样本）**：SKILL.md 六面改写——①新增第 0 步基点预检 fail-fast（rev-parse + diff 非空才派发，失败止于主审，补齐 M10）；②收口改证据先行（逐项出示四轴报告落盘路径，缺一列出缺失轴并停止）；③豁免条款三重否定改两条件合取正向判定（双处记档出示位置 + 用户确认可指认 + 每次独立判定，治理三要素无损，M6 最大反例消除）；④跨技能引用三处弱指针改显式 Skill tool 调用（code-review / open-code-review-delegate / duo-java-review，轴定义材料随 brief 原文下发，M2）；⑤description 210→71 字（M7）；⑥变更历史 6 段 40 行无损迁入 references/experience.md（SKILL.md 零历史节，M8 sediment 消除）；全步骤补完成判据（共 10 处）、禁令 7+ 收敛至 1 处唯一硬护栏；brief 填空模板/四轴结构/豁免治理保留资产无损；本单重放完成新旧流程行为对照（预检出示/路径出示/报告形态），外部 diff 行为验收按 spec 决策 5 挂账工单 12；《技能写作规范》6 处 code-review 锚点快照化（改写票固定回查项首跑）

- **duo-workflow 与根 AGENTS.md 路由表改写（C1-05，改写组首单——全库首个按《技能写作规范》改写的技能）**：duo-workflow SKILL.md 重写——①新增判级表（三级的判定标准 + 首个落盘物）与两条兜底（介级取高并说明理由、用户点名较低级以用户为准——补齐矩阵 M10 fail-fast 缺口）+ 判级依据随首个落盘物留痕（L1 并入 spec 背景 / L2 并入 CHANGELOG 条目 / L3 并入提交信息——补齐 M15 判级无落痕反例）；②L1 七步 / L2 四步 / L3 三步全部带"完成判据"（原 L1 七节点箭头链零判据为最大反例——M3）；③设计访谈门禁改 ADR-0027 判据形态（"出示 /grill-me 并停止，等用户键入"），删除"模型不可自动触发"正文散文声明（M1 落到最强层）；④跨技能引用改显式 Skill tool 调用 11 处（tdd/diagnosing-bugs/duo-code-review/duo-acceptance/duo-comprehension/duo-release-workflow/git-commit-gen/duo-tracker）与三门禁步判据形态（M2）；⑤description 三规则重写（77 字，预算 ≤80），收口（wrap-up）中英锚点，验收与理解关卡独立节删除（定义归各自技能，单一事实源）；根 AGENTS.md 路由行同 diff 对齐（+实现效果/+微调/L1-L3 注记）；重放 seam 8 种口语说法机械层全绿（工单 05 对照表），行为层留使用周期观察

- **《技能写作规范》本体（C1-04，M25 起技能写作前置约束）**：新增 `docs/05-参考/技能写作规范.md`——五支柱条款化（上下文指针与 description 三规则及预算 / 完成判据与证据先行 / 检查点阻塞化 / 领先词与词汇一致 / 否定收敛与剪枝）+ 失败路径 fail-fast + 体量治理 + 保留资产清单，每条款配 duo 真实正反例（正例：acceptance 判据、trim-cot-leakage 保留规则、doc-standards 对账命令、release-workflow 红线不抄录等；反例：workflow 箭头链、code-review 弱指针与三重否定、bug-ledger 反模式节等），附录 M1–M15 机制面覆盖对照表供新技能收尾自查；同 diff 落地词汇治理首批——术语表新增「技能写作域」8 词条（工单/收口/验收/完成判据/上下文指针/领先词/证据先行/渐进式披露，首选+_Avoid_ 格式），domain.md 补 SKILL 用词约束；sidebar 与 index 导航同步，docs:build 通过

- **ADR-0027 技能写作规范移植与调用权分流（C1 决策段，含 harness 能力探测）**：立 `docs/adr/0027-技能写作规范移植与调用权分流.md`——①移植 writing-for-agents 五支柱为中文《技能写作规范》（工单 04 交付，附 duo 正反例池，与 duo-prose-standard 分工不改）；②调用权分流探测结论记档：ZCode skills frontmatter 白名单五键（`adapters/src/skills/index.ts:21-27`，锚点 872ad96）不含 `disable-model-invocation`，技能清单全量注入无过滤（`sections/skills.ts:17-37`），写入该字段唯一副作用是 safeToAutoLoad=false 且其唯一消费方是 /skills 展示——**硬隔离不具备条件，按语义兜底落地**（AGENTS.md 显式命令表唯一门禁面 + 技能 frontmatter 禁写该字段防误加 + 流程门禁强制力改判据承担），ZCode 未来支持时按记档升级；③改写组纪律（description 与路由表同 diff、显式调用形态、保留资产清单、变更历史迁出）；sidebar 补 0027 条目，导航对账 27+1 全绿

- **C1-02 duo 侧逐技能对照与对比报告 + 讲义**：以 C1-01 机制清单 15 主题为对照系，一手通读 duo 侧 16 技能（15 duo-* 共 1458 行 + release-notes），落 `docs/research/mattpocock-skills/对比报告.md`——逐技能 gap 对照（每条带双方行号锚点）+ 15 机制覆盖矩阵（duo 侧 3 项 ✓：brief 填空模板/模板文化/状态机自发长出；9 项 ◐；3 项 ✗：调用权分流/显式调用/词汇治理全缺）+ Downloads 轻量分析三态终审（采纳 7/修正 6——含 description 均值实测 163 字为 Matt model 档 4-5 倍、部分推翻"体量治理有自觉无制度"——doc-standards 对账命令已是制度形态，缺的是剪枝方向制度）；随报告交付 4 课讲义（指针与调用权/完成判据与领先词/否定措辞与体量治理/中文补偿策略），每课双方原文对照

- **C1 技能写作体系改造开工：Matt 侧机制级精读清单（C1-01 底稿）**：全量一手通读 mattpocock-skills 插件 1.2.3（37 个 SKILL.md + 4 份元文档，2639 行），落 `docs/research/mattpocock-skills/机制清单.md`——15 个机制主题（调用权分流/显式工具调用/完成判据/检查点/领先词/否定治理/信息层级/剪枝/词汇治理/fail-fast/子代理纪律/路由分层/模板防陈旧/格式纪律/状态机并发）每条带原文行号锚点；含与 Downloads 两份轻量分析的逐条比对（核心结论印证成立，8 处事实修正——含 user-invoked 实为 22 个而非 8 个）与 Matt 自家 5 处例外张力点。工单 C1-02 对比报告与讲义以它为机制底册

- **M24（0.19.0）启动规划落盘：grill 二十问收敛 + ADR-0026 六裁定（spec 期对账增补决策七）+ spec 与 11 张工单**：`docs/adr/0026-M24权限与安全深化六裁定.md`——①权限规则引擎（项目 `.duo/settings.json` permissions 段 + 会话事件两级、`Bash(prefix:*)` 词边界、deny 恒优先、高危十根命令生成+运行时双拦）②只读 bash 免审批（38 命令 + git 四件套、复合结构 fail-closed、capability seam 接入）③计划模式硬禁（注入收缩 + pre-execute deny 兜底，M7#1 销账）④MCP 命名哈希防坍缩 + 重连耗尽终态通知⑤Web 鉴权令牌 + 标签级会话绑定最小版（M8#4 销账）⑥/model 白名单切换 + /effort 四行映射（保存意图/执行绑定分离、辅助请求降档）⑦Anthropic-messages 适配器 + `llm.provider` 显式声明（spec 对账裁定从 1.0 后菜单提进本期）；spec `.scratch/m24-permission-security/spec.md` + 11 张工单（02/03 依赖 01 地基、04 复用 03 判定器、06 先于 07 串行减冲突、11 收尾）；术语表 10 词条增改（新增权限规则/只读命令/鉴权令牌/标签会话绑定/思考等级/模型切换/工具名规范化/provider 声明，计划模式改写硬禁口径，审批小队列扩四值）；backlog 三条销账于收口（/model 运行时切换、Web 鉴权令牌、Anthropic-messages 适配），新增跨 provider 路由与 git 破坏子命令排除两条挂账

- **Web 停止入口并入发送按钮（工单 07 验收反馈）**：发送/思考中/停止一钮三态——受理后同钮转【停止】（危险色，点按协作式中断，收口后复位【发送】），运行中注入保持停止态；移除独立停止按钮，键盘回车始终为发送（执行中即注入）

### Fixed

- **停止在纯流式输出段不生效，且中断可能击落会话日志通道（M23 残留缺陷，工单 07 验收实测发现）**：两层根因——① 适配器流式读阻塞在 socket 流的 readLine 上对线程 interrupt 免疫，输出期间点停止只能空等到整轮生成完；② interrupt 即使落地也会击中 send 线程正在进行的会话落盘 IO，InterruptibleChannel 语义直接关闭唯一持锁的 lockChannel（POSIX 语义连坐释放独占锁且不可恢复），中断标记落盘失败、页面弹红色异常卡。修复 = 流式 chunk 回调作为收敛点（置位后下一个数据块毫秒级收口：已流出文本保留并打中断标记、后续块不落日志）+ `inLlmStream` 相位门（流式段 requestInterrupt 只立标志不打断线程——该段没有需要唤醒的等待，工具段的线程打断语义不变）；Web 中断改中性标记呈现（「⏸ 已中断」灰字，不再弹红色执行异常卡——用户主动停止不是异常），流式气泡定格、按钮复位

- **Web 面抢占会话致 CLI 永远无法续接（M24 工单 09 验收实测发现，BUG-20260923-01）**：web 行先于 cli 行装配（回答者路由契约），Web 面启动 `latest()` 抢走目录最新会话，CLI 随后撞同进程持锁注册表被顶开新建——每次启动都「会话已被占用 → 新建」，resume 续接（模型意图横幅）永远失效；修复 = Web 面改为启动自建全新会话（不续接不抢占，恢复上次 Web 对话走 `/switch`），`Session.latest` 同步跳过 0 字节空会话（Web 自建的空文件不再成为 CLI 的续接目标，续接目标恒为最近一次真实对话）

## 0.18.0（2026-09-22）

### Added

- **迭代上限感知（M23 工单 10，ADR-0025）**：agent 距迭代上限剩 2 轮（含本轮）的那次请求，组装时附加 `<system-reminder>` 提醒段引导模型主动收敛（"剩余 2 轮，请收敛并交付结论"）——不落会话日志、不改变上限值与达限行为（超限仍 completed=false，可见化不变）；提醒位于治理投影之后必达（compaction 不吞）、恰好一次不刷屏

- **技能热加载（M23 工单 09，ADR-0025）**：修改 SKILL.md 或新增/删除技能免重启即生效——watch 监视四发现根（含根目录首次创建），变更触发重扫描（内容有变 revision 递增），技能清单片段按内容 sha256 digest 去重：变化才重发、只重发一次；模型侧 skill 工具与清单即时读到最新内容；watch 不可用（环境限制）降级为启动扫描 + 日志警告，不阻断技能发现

- **.gitignore 忽略判定器：三消费点同口径（M23 工单 08，ADR-0025 决策三）**：glob/grep/@file 补全共用自研忽略判定（不捆绑 rg）——逐级堆叠解析各目录 .gitignore 与根 .git/info/exclude，git 同语义（! 反选 last-match-wins、** 跨层、字符类、尾 / 目录限定、前导 / 锚定、\ 转义含转义行尾空格，坏行静默跳过）；判定取 .gitignore ∪ 硬编码产物目录（node_modules/target 等）∪ VCS 目录并集——.gitignore 目标从三处结果同时消失，口径分裂消灭；祖先目录被忽略即整树剪枝（目录内反选救不回，git 同义）；已知姿态差异记 limitations（未闭合 [ 按坏行、判定按会话缓存）

- **headless --json：脚本化任务驱动（M23 工单 07，ADR-0025）**：`DuoMain --json [--session-id <id>] [装配.yml] <任务文本...>` 一次性跑任务——stdout 输出逐行 JSON 事件流（词汇：session/status/text/tool_call/tool_result/error/final，工单计"七类"以列举为准；text 仅在 assistant/message 提交点发射，final 帧承载无损答案豁免截断、必发为消费锚点；status 帧带实测 token 用量、缺样本省略），诊断只走 stderr；退出码即成败契约（completed→0 否则 1、SIGTERM→0、SIGINT→130、usage→2）；headless 流内禁交互——审批/提问自动拒绝并发显式 error 帧（审计落会话、final 必达不挂死）；`--session-id` 恢复既有会话续跑（事件流连续不重放）；装配 yml 自动禁用 cli/web 呈现位行（headless 自身即第三呈现位）；中间帧 8K/32K 截断降级链（超限 `truncated:true`，duo 帧字段恒为标量故无"降级标量"中间级——与 DSH 四段链的记档差异）

- **bash 输出三层与 spill（M23 工单 05，ADR-0025 决策二）**：bash 输出分层——内存尾窗（默认 30k 字符）+ 懒 spill 落盘（超 inline 预算才落盘，`task-output`/回传路径可回读全量）+ 64Mi 字符超帽显式告警不静默；三层预算与 task-output 尾窗（32k）经 `fs-tools` 行 config.output 段 yml 可配；前台后台共用分层机制，后台 settle 刷 spill 缓冲防回读缺尾，插件停止清理 spill 无残渣

- **后台任务可见化（M23 工单 06）**：CLI 提示符带后台状态段——有运行中任务时显示 `[后台 N 个运行中]`，全部完成显示 `[后台已完成 bg-N]`，无任务零噪声；Web 状态面新增「后台任务」区块（id/命令/状态/退出码，终态保留呈现）；任务按发起呈现位归属过滤（复用 presenterId）——CLI 与 Web 双开时互不串显（验收实测修正：CLI 触发的任务不再出现在 Web 新会话状态面，完成通知同样各归各位），无归属任务（子代理/直调）双面均可见

- **bash run_in_background 与 task 面（M23 工单 04，ADR-0025 决策二）**：bash 工具新增 `run_in_background` 参数——立即返回任务 id（bg-N）转后台运行（后台不受 timeoutMs 约束，暂停/中断不杀后台，进程退出全杀防孤儿）；新增 `task-output`（block/timeout 等待或快照读输出尾部窗口）与 `task-stop`（杀进程树，幂等）两工具；完成通知必达——agent 空闲自动开新轮消费、执行中挂收件箱 next-turn 收口合并消费（first-wins 每任务至多一条）；CLI 与 Web 双呈现位同款路由

- **暂停：协作式中断与恢复（M23 工单 02，ADR-0025 决策一）**：运行期可随时暂停 agent 任务——CLI 运行期 Ctrl+C 单击触发协作式中断（当前工具终止、已流出文本保留、再按一次强制退出 130，空闲单击退出进程；`System.console()` 门控，测试/headless/管道环境保留默认终止语义），`/stop` 行命令为兜底入口；Web 输入框旁停止按钮（执行中出现）+ `POST /api/stop`；中断时已流出文本落 `assistant/interrupted` 会话事件（投影带 `[已中断]` 前缀，重放/续接可见中断点）、本轮未派发的工具调用补合成结果（日志可重放无悬空）；会话停在可恢复态，下一条消息即续接。不做原地冻结

### Fixed

- **glob 锚定相对模式永不命中（M23 工单 01 验收实测发现）**：`glob` 以绝对路径直接匹配用户模式，`docs/adr/*.md` 等锚定相对模式零结果（仅 `**` 前缀形态因可吞绝对路径前导段而侥幸可用）；修复 = 先相对化到搜索根再匹配，并补 Java glob `**/` 零目录段变体（根下直接文件靠去前缀匹配器命中）——锚定/任意深度两种形态一致可用，`path` 参数语义同步明确为「模式相对该目录解析」

### Changed

- **审批小队列（M23 工单 03，ADR-0025）**：Web 回答者由单待答升级 FIFO 排队——并发到达的第二个请求不再被拒（防御性升级，串行架构下不悬空）；CLI 审批呈现加本轮序号（第 2 项起显示「本轮第 i 项审批」，turn 边界归零）；Web 审批卡支持 Esc 键拒绝（与 FIFO 最旧完成语义对齐）；中断余项合成 deny——应答等待被打断按 fail-closed 收口且不残留线程中断标志（防审批审计事件落盘被 NIO 炸）；CLI 应答等待期斜杠命令逃逸闸门（/stop 在审批等待时可执行，不再被吞作应答）

- **CLI 事件驱动主循环与两级收件箱（M23 工单 01，ADR-0025 决策一）**：终端 REPL 从"阻塞读 + 同步执行"改为读者线程与 turn 线程拆分——agent 执行期间键入的普通文本注入收件箱 next-step 级并回显「已插队」，模型下一步边界即见（修复"执行期输入被静默当新输入消费"缺陷）；执行期斜杠命令照走注册表（busySafe 即行、非 busySafe 得到等待回应，ADR-0020 决策 4 在 CLI 真正生效）；审批/提问应答行经应答闸门路由、EOF/停止即时 fail-closed；EOF 不腰斩执行中的 turn；agent 域注入 seam 升级两级（新增 next-turn 收口排干，多条合并为一条生效，供后台通知消费）；Web busy 注入 202 行为不变

- **M23（0.18.0）启动规划落盘：grill 十二问收敛 + ADR-0025 三裁定 + spec 与 11 张工单**：`docs/adr/0025-M23执行与CLI体验三裁定.md`——①CLI 事件驱动主循环与两级收件箱（虚拟线程常驻读 stdin、next-step step 边界注入/next-turn 收口消费、暂停协作式中断+恢复：Ctrl+C 在跑先停再按退出/空闲即退、`/stop` 兜底、Web 停止按钮、不做原地冻结）②后台任务注册表与输出三层（inline 30k/spill 64MiB/task-output 尾窗 32k、超帽告警不静默、yml 可配）+ task-output/task-stop 两工具 + 完成通知 first-wins 必达（busy 挂 next-turn 收口合并、暂停不杀后台）③.gitignore 自研判定器（红线 4 拒捆绑 rg、全常用子集、.gitignore∪产物目录∪VCS 目录三源并集、glob/grep/@file 同口径）；spec `.scratch/m23-cli-experience/spec.md`（34 条用户故事、七组既有测试 seam）+ 11 张工单（01-06 主线依赖链：主循环→暂停→审批队列→后台→spill→可见化；07-10 零依赖并行：headless --json/.gitignore/技能热加载/上限感知；11 收尾）；术语表 11 词条增改（新增收件箱/暂停/审批小队列/后台任务/spill/忽略判定/NDJSON 事件流/技能热加载，steer 升两级语义、迭代上限增感知提醒、发现根去「不做热加载」）；backlog「CLI 运行中 steer 入口」转 M23 正式范围（ADR-0025 决策一）

## 0.17.0（2026-09-21）

### Changed

- **全库 MD 文档审计（duo-doc-standards/prose-standard）**：①文档站 sidebar 补挂 ADR-0022/0023/0024 三条（0022 起持续漏挂，导航不可见）；②修复探测文档相对链接 46 条断链（域目录层级模板错误 `../../模块手册.md`→`../模块手册.md` 等）；③research 索引两条状态失真修正（核心功能全景转历史存档、subagent 对照标注 DSH 锚点陈旧）；④docs/index.md 05 参考行补漏「工具目录」；⑤duo-doc-standards 审计命令修正（`text: '000` 前缀匹配对双位数 ADR 漏计，改用 link 计数+1 核对）。核对通过：工具目录/插件配置参考含 M20/M21 内容属新鲜、插件化架构 b150a551 引用为合法变更注记、257 条相对链接仅 1 处格式描述误报、docs:build 构建通过
- **ADR-0024 修订（用户补充三项会话控制能力）**：M23 增「暂停（协作式中断+恢复）」——duo 主代理此前无任何中断手段，中断点为 M23 线程改造原生产物，已流出文本保留+未派发工具补合成，不做原地冻结；M24 增「/model 动态切换」（同 provider 换名+跨 provider yml 预声明+resume 保存意图/执行绑定分离）与「/effort 思考等级」（归一化档位→provider 映射，诚实降级，辅助请求强制降档）；backlog /model 销账转排期、1.0 后菜单移除 reasoningEffort 分级
- **ADR-0024 落盘：M23 至 1.0 版图重排（探测后完整规划）**——替代 ADR-0023 决策三。五期：M23（0.18.0）执行与 CLI 体验（线程模型/run_in_background+spill/steer/审批队列/headless --json/.gitignore+技能热加载与上限感知搭车）→ M24（0.19.0）权限与安全深化（规则引擎/只读识别/计划硬禁/MCP 加固/Web 鉴权+标签会话绑定）→ M25（0.20.0）上下文与记忆（memory 域/microcompact/cacheControl/嵌套 AGENTS.md）→ M26（0.21.0）会话数据与检索（FTS5 双表/export 增强/格式版本头/watermark）→ M27（1.0.0）收口。规划原则：缺陷优先→控制面跟上能力面→长会话质量→数据完备→收口；每项按探测对照设计落地不做简化版。对账：limitations 五处去向更新、backlog 两条转已排期、ADR-0023 版图段加替代注记
- **M22 探测里程碑收口：ADR-0023 落盘 + 四处对账**（工单 23/T-27）：`docs/adr/0023-沙箱出栈与M23+里程碑规划.md`——①沙箱出栈决议（ZCode 撤除先例/零 native 红线/审批兜底；1.0 安全基线按「决议已做出」闭合）②M22 改道记录（探测里程碑 22/22 完成，44 份五段文档）③M23+ 版图：M23（0.18.0）ZCode 对齐·权限与执行深化（规则引擎/只读 bash 识别/计划模式硬禁/run_in_background+CLI steer）→ M24（0.19.0）Web 呈现协议化与补全（含 Web 鉴权）→ M25（1.0.0）收口。对账：limitations M12#1 改判长期已知边界、M20#1 措辞更新；术语表沙箱口径；ADR-0016 M22 行改道注记；backlog 三项入账（.gitignore 建议 M23 搭车/Web 鉴权建议 M24/工程化探测 1.0 后）
- **M22 工单 22（T-26 API/遥测/SDK 面）落盘**：`docs/research/DSH/网络与数据/API遥测与SDK面.md`、`docs/research/ZCode/网络与数据/API遥测与SDK面.md`——DSH Typert @Remote 契约冻结（namespace/method 对账+args 严格校验+错误按 code 跨线）+双 SDK 成本证词+session-stats 成本投影；ZCode Channel RPC 一套服务面复用三传输+OTel 八类 span（model-api-recorder 五类 token/三段时延/不记正文）+基数防护；启示覆盖 duo /api 契约冻结路径、usage 日志增强模板、OTel 成本判断
- **M22 工单 21（T-25 会话检索与导出）落盘**：`docs/research/DSH/网络与数据/会话检索与导出.md`、`docs/research/ZCode/网络与数据/会话检索与导出.md`——DSH 五工具结构化检索面（模型 query 字面化防注入+FTS5/live 双表 UNION+cwd 双重授权）+deliverables 交付声明+流式 ZIP 导出；ZCode ReadSessionContext（relevant/handoff 双 strategy+lite 模型分块抽取+预算钳制）+分享链（存在性隐匿/逐行降级解码）；启示覆盖 duo FTS5 双表、交付声明入导出、检索 token 成本控制
- **M22 工单 20（T-24 存储配置与凭据）落盘**：`docs/research/DSH/网络与数据/存储配置与凭据.md`、`docs/research/ZCode/网络与数据/存储配置与凭据.md`——DSH settings 四件套（namespace/schema/base/用户文档）live 热发布+credentials 独立层（env→file→.env resolve+遮蔽拒写+0600）；ZCode 六 scope 压栈合并（逐字段合并语义）+AES-256-GCM 凭据+损坏 .bak 备份拒静默重建+workspaceIdentity 键；启示覆盖 duo 配置热更新路径、凭据加密最小形态、M12 root 增强
- **M22 工单 19（T-23 网络与远程工具族）落盘**：`docs/research/DSH/网络与数据/网络与远程工具族.md`、`docs/research/ZCode/网络与数据/网络与远程工具族.md`——DSH LSP 封闭四操作+SSH 三 provider 同 seam 透传（helper 白名单 RPC+每流 PSK）+独占 provider 槽；ZCode 出网双层防线（工具层字面量拦截+适配层 public-egress DNS 双查禁代理）+文件信箱 mailbox（rename 原子确认）+SSH/Docker/WSL 远程 backend；启示覆盖 duo LSP 最小价值、跨 harness 委派骨架、建连二次 DNS 校验
- **M22 工单 18（T-22 桌面端）落盘**：`docs/research/DSH/呈现与UI/桌面端.md`、`docs/research/ZCode/呈现与UI/桌面端.md`——DSH 三进程（壳内 dsh-app:// 协议渲染+同 exe Node host+4 消息最小协议）+强制更新全链；ZCode 五进程（MessagePort RPC+BroadcastHub 多窗）+内置浏览器 IAB（WebContentsView+CDP=agent browser-use 后端）+zcode:// 四路由+scheduler 独立进程；启示：duo 桌面壳非必需，判据是「受控宿主」需求（IAB/托盘/deep link/常驻调度）
- **M22 工单 17（T-21 终端呈现）落盘**：`docs/research/DSH/呈现与UI/终端呈现.md`、`docs/research/ZCode/呈现与UI/终端呈现.md`——DSH 无 TUI（形态裁定）而有三协议面：headless json-stream（commit-point 投影+final 无损豁免 bounding+退出码契约）/ACP stdio/浏览器拉起；ZCode opentui-react TUI（30fps+键盘优先级链+Promise 化审批队列+模式乐观回滚）+headless 三输出；启示覆盖 duo headless --json 通道、事件归约 reducer 分层、审批小队列升级
- **M22 工单 16（T-20 Web 呈现）落盘**：`docs/research/DSH/呈现与UI/Web呈现.md`、`docs/research/ZCode/呈现与UI/Web呈现.md`——DSH 事件→节点 Definition 注册表+工具卡 keyed 插槽+composer 接管协议+IncrementalMarkdownParser 防闪烁；ZCode v4 wire 帧→投影 store 三规则（整体替换/断档不猜/水位重订）+toolIdentity 两级渲染器注册表+乐观命令三路对账；启示即 M24 蓝本：family 枚举+fallback 替代魔法串、composer 输入位协议、snapshot+overlay 先落不变量
- **M22 工单 15（T-19 插件与装配）落盘**：`docs/research/DSH/扩展机制/插件与装配.md`、`docs/research/ZCode/扩展机制/插件与装配.md`——DSH 分层 patch 合成（bundle→profile→home→旗标）+稳定判定用全树落定审计而非超时+StandardSchema 逐插件启动前校验；ZCode 一包一 manifest（技能/命令/agents/hooks/MCP）+known/installed JSON+原子激活+qualifiedName 防撞；DSH 插件化架构三件套增量补扫至 ddefc45f（42 处变更，关键=loader/include 事务回滚移除）；启示覆盖 duo 两层 patch 最小形态、awaitStartup 改审计式、组件化插件包
- **M22 工单 14（T-18 MCP）落盘**：`docs/research/DSH/扩展机制/MCP.md`、`docs/research/ZCode/扩展机制/MCP.md`——DSH 工具名有损规范化+哈希后缀防坍缩+重连预算/稳定窗/耗尽注销；ZCode 连接池 lease（connectionKey=config 指纹+scope）+空闲 30s 宽限+Job Object/进程组双路杀树+OAuth 自愈；启示覆盖 duo 工具名截断哈希、重连终态语义、lease 形态
- **M22 工单 13（T-17 skills 与命令）落盘**：`docs/research/DSH/扩展机制/skills与命令.md`、`docs/research/ZCode/扩展机制/skills与命令.md`——DSH rank 合并表+watch revision+digest 热失效+`<skill_content>` 统一渲染；ZCode 多根步进优先级（user 压 project 反直觉默认）+metadata 20k 预算降级名单+保留名两级门禁前置 load；启示覆盖 duo 多源 rank、热加载、metadata 预算制
- **M22 工单 12（T-16 hooks）落盘**：`docs/research/DSH/扩展机制/hooks.md`、`docs/research/ZCode/扩展机制/hooks.md`——DSH 两桥 7/5 事件+审计对+Codex 五处分歧；ZCode 7 事件+项目级钩子信任链七态（授权逐 dispatch 重验）+PermissionRequest hook 结构化应答与 broker 竞速；启示覆盖 duo 事件面扩展优先级（UserPromptSubmit/Stop）、载荷补齐、信任链防供应链
- **M22 工单 11（T-15 压缩治理）落盘**：`docs/research/DSH/LLM与上下文/压缩治理.md`、`docs/research/ZCode/LLM与上下文/压缩治理.md`——DSH 四事件+表面 replace 遮蔽（内容永不删）+shadow-price 对账+summary 复用路由前缀省 KV；ZCode microcompact 免模型本地裁剪/熔断+rapid-refill 防连环/summary 预算隔离（独立 20k 上限）；启示覆盖 duo pruner 同构计量、压缩失败计数器、microcompact 先落地
- **M22 工单 10（T-14 系统提示与上下文工程）落盘**：`docs/research/DSH/LLM与上下文/系统提示与上下文工程.md`、`docs/research/ZCode/LLM与上下文/系统提示与上下文工程.md`——DSH order 中心位表+注入以带 source 的 user 消息入日志+动态快照取代语义；ZCode 三段 cacheControl ephemeral（身份前缀/稳定身份/动态段）+meta_user 通道（AGENTS.md+MEMORY.md 索引）+memory 域三件套；启示覆盖 duo prompt 具名槽位、AGENTS.md 迁 meta_user、cacheControl 三级断点
- **M22 工单 09（T-13 LLM 调用层）落盘**：`docs/research/DSH/LLM与上下文/LLM调用层.md`、`docs/research/ZCode/LLM与上下文/LLM调用层.md`——DSH 双协议子适配器+请求从日志纯函数重建+usage 采信需≥启发式下界；ZCode 工具装配 end-gate 状态机+失败分类有序降级链+reasoning 签名拒绝一次性免费修复；启示覆盖 duo 双协议形态、重试状态持久化、半截 JSON 防执行
- **M22 工单 08（T-12 只读命令识别与规则，ZCode 单侧）落盘**：`docs/research/ZCode/交互与呈现/只读命令识别与规则.md` + `docs/research/DSH/交互与呈现/只读命令识别与规则-DSH注记.md`——ZCode 三层（unbash 解析器/20 文件三态只读策略表/capability 运行时覆盖）+git 运行时上下文安全+`Bash(prefix:*)` 规则建议与高危根命令排除；DSH 无对应如实注记；duo 工程量评估：解析+判定约 300-400 行可控，策略表首期建议 30 命令+git 四件套
- **M22 工单 07（T-11 审批与提问交互）落盘**：`docs/research/DSH/交互与呈现/审批与提问交互.md`、`docs/research/ZCode/交互与呈现/审批与提问交互.md`——DSH 答案者瀑布（认领/委托/fail-closed 三态分离）+审计事件对+UI 卡片优先级分层；ZCode hook 与 broker 竞速（应答通道先于 UI 可见的时序契约）+项目规则 SQLite 持久化+ask_user 走 modify 回注；启示覆盖 duo 亲和路由的显式委托态、审计 id 对、项目/会话两层规则
- **M22 工单 06（T-10 任务管理）落盘**：`docs/research/DSH/子代理与编排/任务管理.md`、`docs/research/ZCode/子代理与编排/任务管理.md`——DSH goal CAS+轮上限续跑/jobs wait-kill-output+wakeup 预算/schedule 会话内提醒；ZCode todo 整表替换事务+cron/off-peak 双形态（misfire 不补跑 vs 顺延）/plan 落盘计划文件；启示覆盖 duo goal 平移、定时提醒双形态、todo 投影权威顺序
- **M22 工单 05（T-09 任务编排 workflow）落盘**：`docs/research/DSH/子代理与编排/任务编排.md`、`docs/research/ZCode/子代理与编排/任务编排.md`——DSH 宿主 API 最小六件+四帽+agent()落 subagents+ralph 固定脚本；ZCode 编译期类型门+lowering+入口文件 spawn+NDJSON 桥+journal 确定性重放与 amend 缓存导入；启示覆盖 duo 编排引擎形态、宿主 API 最小集、持久化底座
- **M22 工单 04（T-08 子代理）落盘**：`docs/research/DSH/子代理与编排/子代理.md`、`docs/research/ZCode/子代理与编排/子代理.md`——DSH spawn/fork 种子语义+continuable settlement notice+ActivationPool 槽位；ZCode profile 文件驱动（frontmatter 全字段）+独立持久化子会话+TaskOutput/TaskStop+ReadSessionContext 跨会话抽取；启示覆盖 duo fork 形态、模板 opt-in 参照、完成通知必达
- **M22 工单 03（T-07 本机执行工具族）落盘**：`docs/research/DSH/工具系统/本机执行工具族.md`、`docs/research/ZCode/工具系统/本机执行工具族.md`——DSH 流式大文件读/OutputCollector 保尾+spill/PTY 六件套 owner 绑定与 jobs 回读；ZCode run_in_background 全链（含超时自动转后台）/WASM ripgrep Worker+JS 回退/输出预算四层/读前写闸门+expectedRevision 乐观锁；启示覆盖 duo 的 spill 落盘、后台任务蓝本、大文件流式读
- **M22 工单 02（T-06 工具注册与执行管线）落盘**：`docs/research/DSH/工具系统/工具注册与执行管线.md`、`docs/research/ZCode/工具系统/工具注册与执行管线.md`——DSH 六段管线+单调 guard+finalizeContent 呈现/事实分离+调度失败不伪造结果；ZCode registry/scheduler/executor 三层+拓扑排序分层并行+四元组并行判定+输入归一前置 hook；启示覆盖 duo 的单调终裁、多维并行声明、拒绝也是结果
- **M22 工单 01（T-05 投影、恢复与分页）落盘**：`docs/research/DSH/Agent循环与会话/投影、恢复与分页.md`、`docs/research/ZCode/Agent循环与会话/投影、恢复与分页.md`——DSH watermark/「checkpoint+尾部重放」冷读加速/回翻 ordered-baseline 合并/标题链 latest-wins；ZCode resumeFromStore 全量重建链/rowId 游标分页（尾窗 60、上限 200）/断线三规则与陈旧权威恢复；启示覆盖 duo M10/M13/M19 的 seq 水印、代际戳防陈旧页、保存意图与执行绑定分离
- **M22 转型为参考项目深度探测里程碑并转 spec 工单正轨**（spec 已定稿并拆单：`.scratch/m22-reference-probe/spec.md` + `issues/` 23 张工单（01-22 探测、23 收尾 ADR-0023；仅收尾张有阻断边））：沙箱出栈（决议随收尾工单 T-27 落 ADR-0023），M22 = DSH/ZCode 按功能项逐一探测（24 张工单：T-03 至 T-26 探测 + T-27 规划收尾），每项两家各一份五段模板文档、逐项落盘登记；`.gitignore` 语义与 Web 鉴权移出 M22 入 backlog 由规划排期。批 3（Agent 循环与状态机）、批 4（会话事件模型与持久化）已按新规范落盘
- **M22 探测批 3（Agent 循环与状态机）落盘**：`docs/research/DSH/Agent循环与会话/Agent循环与状态机.md`、`docs/research/ZCode/Agent循环与会话/Agent循环与状态机.md`（五段模板：机制全貌/关键流程/接口参数要点/边界与坑/对 duo 的启示）——DSH inbox 两级队列落库可重放、resume 抢写柄、取消保留部分输出并补合成工具结果；ZCode 命令队列三档优先级+整批合并、steer 行内消费与外层排队双车道、abort 三层语义、rewind 绕队列防自锁；各附 duo（Java 虚拟线程）转译启示
- **M22 探测批 4（会话事件模型与持久化）落盘**：`docs/research/DSH/Agent循环与会话/会话事件模型与持久化.md`、`docs/research/ZCode/Agent循环与会话/会话事件模型与持久化.md`——DSH jsonl 帧校验+撕裂尾截断恢复、格式 v3 相邻迁移链、ignorable 兼容标记；ZCode「事件流内存、投影落库」双层、sqlite 22 迁移账本+checksum 冻结、message/part 序列模型；启示覆盖 duo 会话文件的撕裂恢复/版本头/迁移器最小闭环
- **DSH/ZCode 模块手册落盘**（`docs/research/DSH/模块手册.md`、`docs/research/ZCode/模块手册.md`，锚点同总览）：除沙箱与权限两域（另有专册）外全模块逐一探查，统一六段模板（用途/核心接口/关键参数/调用方式/依赖/注意事项），默认值全部抄自源码、未核实处如实标注——DSH 覆盖核心循环/LLM/持久化投影/八工具域/上下文治理/任务编排/交互扩展/API 装配呈现约 40 条；ZCode 覆盖 contracts 25 port/runtime 内核/adapters 九族/bootstrap 装配/tui/dynamic-workflow/根 packages 十二条；供后续开发（M23 ZCode 对齐等）直接查阅
- **DSH/ZCode 两参考项目五批次全景探查落盘**（`docs/research/DSH/总览.md` 重写至锚点 ddefc45f、`docs/research/ZCode/总览.md` 新建于锚点 872ad96）：按维度分批（代码结构与模块划分 / 核心功能与业务逻辑 / 整体设计与架构风格 / UI 界面与组件 / 页面路由与交互流程）源码级探查后汇总——DSH 87.6 万行 TS、Cordis「一切皆插件」+ seam 三元组 + 事件溯源 JSONL、React SPA 无 URL 路由（slot+dockkit 驱动）、七层测试哲学；ZCode 83 万行 TS、ports & adapters 六边形（25 port + architecture-policy AST 门禁）、AgentRuntime 组合内核 + SQLite 22 迁移 + v4 协议投影、桌面 Electron/Web/TUI 三形态真并行多会话；另刷新 DSH 总览陈旧锚点（b150a551 → ddefc45f）
- **DSH/ZCode 沙箱与权限研究落盘并按项目分家**（`docs/research/DSH/沙箱与权限.md`、`docs/research/ZCode/沙箱与权限.md`，锚点 DSH ddefc45f / ZCode 872ad96）：M22（ADR-0016）grill 前置调研——DSH 侧：沙箱子系统全文（`confine` = spawn 前 argv 前缀替换；bwrap/Landlock/Seatbelt/Windows-ACL 四方言链；fail-closed 无 unconfined 回落；沙箱内免审批 + 升级一次性重试；`writableRoots` = workspace + `/tmp` + `tmpdir()` 无 extra 配置；e2b 实为云远程执行后端）；ZCode 侧：无沙箱（源码注释证载已撤除，仅残留契约与遥测位），安全靠五档权限引擎 + 只读 bash 命令运行时识别免审批。研究文档惯例修订： duo-research 多项目调研默认"两家都看、文档分家"，不再建合并目录（参考权重 ZCode 为主、DSH 为辅）；跨项目对照结论归 ADR。另修正注册表 DSH 语言标注（实为 TypeScript monorepo，此前误标 Java）

## 0.16.0（2026-09-21）

### Fixed

- **MCP stdio 子进程不再泄漏为孤儿进程**：server 侧（`MiniFileSystemServer` 与测试夹具 `MinimalStdioServer`）由 `sleep(MAX_VALUE)` 纯保活改为 stdin EOF 即自退——父 JVM 退出或被强杀后管道断流，子进程自行终止，不再依赖父进程显式销毁；client 侧 `ConnectionSupervisor` 增 JVM 退出钩子兜底——宿主正常退出但未 dispose（demo 主流程抛错、测试收尾）时关连接级联销毁 server 进程（强杀场景钩子不跑，由 EOF 自退兜底）。实测背景：泄漏孤儿最长驻留 >90 分钟
- **构建/测试 JVM 强制 headless**：根 pom surefire 补 `argLine=-Djava.awt.headless=true`——测试 JVM 不再向 macOS AppKit 注册为 GUI 应用，Dock 不随构建闪现 Java 图标（`BufferedImage`/`ImageIO` 等 headless 照常可用）

### Changed

- **duo-research 注册 ZCode 为第二参考项目，功能调研默认两家对照**：项目注册表（`docs/research/index.md`）新增 ZCode（zai-org/ZCode，v3.14.0，TS/pnpm monorepo：桌面端 / Web / Agent CLI，本地 `/Users/zhangyl/IdeaProjects/ZCode`，源码锚点 872ad96）；SKILL.md 落"多项目对照（默认）"规则——同一功能先在 DSH 与 ZCode 各自定位实现，结论按"两家怎么做 → 对照启示"合并落 `docs/research/<别名A>+<别名B>/`，点名单家或另一家无实现才走单家三件套；DSH+ZCode 子代理对照条目补注 ZCode 侧原为安装产物（app.asar）实证、源码级待复核
- **duo-code-review 第二轮审查切换委托模式**：OCR 行级审查由 `open-code-review`（LLM 端点，一次近 40 分钟）改为 `open-code-review-delegate`（委托模式，约 5 分钟）——OCR 仅做文件选取与规则解析，行级审查由 agent 亲自执行；名单、覆盖率口径与报告模板不变
- **技能自我进化机制改版——全局强制化**：复盘触发从 13 个 duo- 技能各自 SKILL.md 末尾的"任务结束后"章节（约 260 行模板复制，实测极少触发）收敛为 AGENTS.md 红线 8——"duo- 技能任务收尾必须先调用 duo-skill-evolution 复盘再输出最终总结，无进化点也须明确说'无需进化'"，并全局要求执行任何 duo- 技能前先读其 `references/experience.md`；各技能 SKILL.md 不再保留任何复盘章节（触发全靠红线 8 与 Stop hook），领域复盘维度（任务摘要/关键点/反馈/自我感知口径）迁入各自 `experience.md` 头部"复盘维度"段；duo-skill-evolution 2.1 接入规范同步改为新机制（新技能一步接入——仅在 experience.md 写复盘维度，勿再改 SKILL.md）；workspace 另配 `.zcode/config.json` Stop hook 每回合注入收尾自查提醒作机制兜底（本地生效，该目录不入库）
- DSH 输入面与会话工具研究落盘（`docs/research/DSH/输入面与会话工具/` 三件套，锚点 ddefc45f = release 0.1.6-alpha.2）：附件内容寻址库（SHA-256 硬链接去重/0400 只读/无 GC）、read_image 多模态三层链路（存储规范化/请求变体/Files API 与非视觉模型三层降级）、@file 路径提及模式（补全索引 + system 指南，零内容注入——修正 ADR-0016"注入"措辞）、会话检索（FTS5"服务在、索引熄火"双层 opt-in）与 /export（ZIP 流式下载而非 Markdown/JSON，同修正 ADR 措辞）——供 M21 设计访谈对照

### Added

- **附件与视觉多模态**（M21，ADR-0022）：新插件 `attachment.AttachmentPlugin`（yml 一行 opt-in）——内容寻址图片库落 `~/.duo/attachments/v1`（SHA-256 硬链接去重、0400 只读、无 GC 永不自动删除）；Web 输入框拖拽/粘贴图片上传（vision 闸门：`llm.vision: false` 缺省时收图即拒 409、read_image 执行前即拒，非视觉部署零感知），消息图片经授权读取端点渲染（先验证会话日志确实引用该 id 再回字节）；`read_image` 工具（fs 工具族追加，附件行在场即注册）读本地图片先入库再返回引用；图片多部件化进请求（消息附件与 read_image 结果以 base64 图片部件随请求发出，附件引用块经框架过滤不进子代理上下文）；请求前按目标尺寸确定性缩放并缓存变体（variantId = sha256(附件+目标+编码版本)）省 token；`llm.imageDelivery: files` 时图片变体经 DeepSeek 形态 Files API 上传换 file_id 进请求（本地索引去重、配额满回收最旧自有文件、上传失败回退 inline），缺省 inline。新依赖 TwelveMonkeys ImageIO + Thumbnailator（纯 Java 零 native）
- **@file 路径提及**（M21，ADR-0022）：消息里 `@路径` 即工作区文件引用（零内容注入——内容永远由模型 read 工具自取）；Web 输入框 `@` 补全下拉（候选/目录下钻/引号路径 `@"含 空格"`；索引懒构建 + tool/result 后台重建 + 未命中重建重试覆盖 IDE/终端带外改文件；排除 .git/node_modules/target 等）；system 指南约束"要内容调 read；未 read 不得声称已看过"（仅 read 工具在册的部署注入，双呈现位去重）；CLI 文本直打
- **会话检索**（M21，ADR-0022）：新插件 `sessionquery.SessionQueryPlugin`（yml 一行 opt-in）——`session_search` 工具（模型侧）+ Web 侧栏搜索框（用户侧）共享后端无关检索服务：内存倒排索引懒构建（首次搜索才扫 + 文件戳增量，启动零成本、不装零感知），分词 AND（英文整词 + 中文单字）、汉字原词主排序键 + 摘录锚定原词；索引内容 = 消息/工具调用与结果/todo/turn 错误（reasoning 物理不入），子代理会话不索引
- **/export 会话导出**（M21，ADR-0022）：双面命令 `/export [markdown|json]`（busySafe，缺省 markdown 人读记录：角色/时间戳 + 工具摘要行 + 尾部附件引用清单；json 为日志原样副本）——CLI 写盘当前目录 `duo-session-<id>.md/.jsonl`，Web 自动触发浏览器下载；附件字节不打包（库内永不删除，引用清单已覆盖）

## 0.15.0（2026-09-20）

### Added

- **web 工具族**（M20，ADR-0021）：新插件 `tools.web.WebToolsPlugin`（yml 一行 opt-in）——`web_fetch` 抓取公网网页返回干净 Markdown 正文（头行带最终 URL 与状态码，正文前有不可信数据声明；非 2xx 是结果不是错误；SSRF 三道防线拒绝内网/回环目标与跨源重定向；仅文本/HTML/JSON/XML，深度护栏防病态页面，三层限额超时 30s 均可配）与 `web_search` 全网搜索（Tavily 首发，单 query 返回 Sources 列表，上限 8 条可配；key 解析链 apiKey 字面量 → TAVILY_API_KEY 环境变量）。**配置驱动注册**：search 段未配置的部署呈 fetch-only，web_search 不出现在模型工具清单。权限按网络读档位：read-only 档联网一律 ask（只读语义不出网边界）、workspace-write / danger 档放行；两工具并发安全进并行池，hooks/工具卡/子代理模板零特化生效。新依赖 jsoup（HTML 解析清洗，单 jar 零传递依赖）

### Changed

- **技能经验文档归位 references/ 并全量预建**：duo-* 技能的 experience.md 从 SKILL.md 平级迁至 `references/experience.md`（对齐技能规范格式，references 承载辅助文档）；经验文档 14 技能全量就位——duo-code-review / duo-skill-evolution 迁移保留既有内容，其余 12 个按元技能规范预建模板；全部 SKILL.md 增补"经验参考"段（执行前读经验、任务后复盘追加同一文件）并统一新路径引用——修正自我进化收尾流程不被触发的问题
- DSH web 工具族研究落盘（`docs/research/DSH/web工具族/` 三件套，锚点 ddefc45f = release 0.1.6-alpha.2）：ctx.web seam 双注册表与执行期选路（配置点名三态 / 唯一可用自动选 / 多可用报歧义）、web-fetch-http 的 SSRF 纵深防线（URL 字面预检 → DNS 全地址集公网校验含 NAT64 → 连接 pinning 防 rebinding → 重定向同源且每跳重校验）、tool-web 的 web_fetch（turndown GFM 转换 + 深度护栏 + 三层限额 + 不可信数据声明）与 web_search（多 query 并发合并、provider 可配、无 key 时工具仍注册执行期报结构化错误）及 deepseek/exa/perplexity 三 provider 对照——供 M20 web 工具族（ADR-0016）设计访谈对照

## 0.14.0（2026-09-20）

### Fixed

- **Web 侧权限档恢复与 /permission 不可用**（M19 缺口，M20 验收实测发现）：WebPlugin 未声明 workspace 可选依赖——内核"错误前移"拒绝读取，Web 侧新建/切换会话时权限档恢复被跳过（WARN"权限档恢复跳过"）、浏览器 `/permission` 不可用；补 `optionalInject` 声明修复（CLI 侧自 M19 起即正常；纯对话 Web 装配缺席视为无档位语义照常启动）
- **双开重启后权限档恢复被覆盖**（BUG-20260919-03，工单 06 验收实测）：恢复语义未分档——CLI 占用被迫改开的新会话把另一呈现位刚恢复的档位重置回缺省；修复分两层：启动续接只恢复不重置、显式换绑（/new/新话题）无记录才重置缺省；占用改开的新会话继承被占会话最后切定档并落事件（治理态不因呈现位轮转而丢）
- **压缩切分在工具对收尾形态下永远放弃折叠**（BUG-20260919-01，工单 03 验收实测）：投影尾部为 [助手(工具调用), 工具结果] 收尾（带工具调用轮次的常态）时，切分点向后找 USER 边界一路推到末尾，手动与自动压缩都误判"近端不足"放弃——切分点改为向前回退到最近 USER，近端多留一轮换配对完整，含 USER 消息的投影总能折叠

### Added

- **斜杠命令注册表**（工单 01，ADR-0020）：agent 域发布 "commands" 服务——命令由插件代码注册（名 + 描述 + 适用呈现位 ANY/CLI/WEB + busySafe 缺省 false），CLI 与 Web 共享同一入口顺序：命令 → 技能直调 → 未知命令报错附可用命令与技能清单。CLI 的 /new、/permission、/plan、/exit 迁入注册表（行为不变）；执行落 `command/run` / `command/done` 审计两事件（投影排除——命令操作 harness 不进模型历史，崩溃断口可观测）；/permission 声明 busySafe，agent 执行期间切档立即生效，其余命令空闲才执行。boot yml 装 `dev.duo.harness.agent.commands.CommandsPlugin` 行即启用（cli 插件硬依赖本服务）
- **还账三件**（工单 06，ADR-0020 决策 10/11/12）：① 权限档持久化——`/permission` 切档落 `permission/mode` 会话事件，重开该会话恢复最后切定档、新会话回 yml 缺省（档位跟对话走，不跨会话惊吓）；② `/title 新标题` 改名命令（双面可用，busySafe——纯事件写，侧栏/标签页即时生效，latest-wins 覆盖自动标题）；③ web 插件 config `pageSize` 可配首屏/每页消息数（缺省 50 不变）
- **/compact 手动压缩 + 压缩点事件化**（工单 03，ADR-0020 决策 6）：上下文逼近窗口时 `/compact` 手动压缩（双面可用，CLI 与浏览器都可敲）——远端历史折叠为四小节摘要并落 `context/compacted` 压缩点事件（触发方式 manual/auto 随事件可审计），投影按最后压缩点拼接（之前以总结替换、之后照常——总结输入为压缩点之前全部历史），刷新/重开压缩态天然恢复；预算触发的自动压缩同事件化——一处语义两处触发，且事件化后**不再每轮重复总结**（现状为请求期纯变换、每轮重复折叠调用 LLM）；`governance.keepRecentRatio` 随事件化停用（字段保留解析兼容，值被忽略）。"上下文为何变小"在日志可审计（M9-M11#3 销账）
- **Web 斜杠入口**（工单 02，ADR-0020 决策 3/5）：浏览器输入框识别斜杠——与 CLI 共享同一入口顺序（命令 → 技能直调 → 未知报错）：命中命令在 Web 进程内执行（不进模型历史），命令行与结果经既有事件流渲染（刷新/回放可见）；未命中 `/xxx` 报未知命令附可用清单（按发起面过滤适用性）；CLI 专属命令提示"该命令仅在 CLI 可用"；agent 执行中 busySafe 命令照常执行、其余明确提示等待空闲。斜杠文本不再透传给模型（M12-03 验收事故销账）。`/permission` 为双面命令（handler 只依赖全局 workspace 服务，浏览器直接切档——M19 用户故事 1）
- **呈现位亲和路由（谁发起谁作答）**（工单 05，ADR-0020 决策 7）：工具执行携带发起呈现位标记（presenterId），审批/提问/计划呈交的 ask 请求优先路由给发起方的回答者——双开部署下 CLI 发起的审批在终端 y/n 作答，不再跳 Web 卡片、终端零提示（M12-02 事故销账）；发起方缺席/放弃才轮注册序（单呈现位部署零感）。交互工具（exit_plan_mode）的会话供给按发起方亲和——批准/打回事件写进发起方会话，计划状态不再串位（limitations 交互工具绑定条销账）；hooks 载荷增 `presenter_id`（载荷上下文透传部分消化）
- **父级 steer（运行中消息注入）**（工单 04，ADR-0020 决策 8）：Web 执行中发消息不再 409——进 agent 注入收件箱，send 循环在迭代边界排干为普通 `user/message`（下一轮请求即可见，不打断飞行中的工具组，多条照排），页面 toast"已注入，待当前步骤完成"；与子代理 send_message 的"下一轮生效"语义对称。CLI 不接（行缓冲天然排队，ADR-0020 决策 9）
- **duo-code-review 技能改为"两轮审查即修复"流水线**：第一轮 mattpocock code-review 双轴审查 → 审查报告 → 修复，第二轮 open-code-review 行级审查 → 审查报告 → 再修复，最窄测试收口（不自动复跑 OCR）；技能正文收敛为步骤与调用指向，两段审查的具体用法分别以对应技能为准；仓库静态审查标准迁出技能，沉淀为持续维护的审查总结文档 `.scratch/review-log.md`（已知模式种子 + 逐次审查追加），报告按轮落盘（工单「审查轮」小节 / `.scratch/<feature>/reviews/`）

## 0.13.0（2026-09-18）

### Added

- **hooks 生态兼容扩展**（工单 03/04，ADR-0019）：复用 Claude Code/Codex 的 hooks 配置格式——`~/.duo/hooks.json` 与两家同形（Claude Code settings.json 整文件粘贴即用，未知键宽容、Codex 扁平条目兼容），`PreToolUse` / `PostToolUse` 两事件挂工具三段管线：exit 2 阻断且 stderr 回给模型（PostToolUse 为结果改写、不假装撤销副作用）、exit 0 stdout JSON 裁定三形兼容（`permissionDecision` 三值——deny 阻断 / allow 放行 / ask 交审批段裁决；兼容 legacy `decision`）、matcher（全匹配 / 精确名多选 / 正则）、条目级 `timeout` 缺省 600s。失败语义 fail-open 全线（钩子超时/崩溃/起不来一律放行 + WARN）——**钩子不是执法边界**，硬闸门需求由 guard/审批承担；boot yml 装 `dev.duo.harness.hooks.HooksPlugin` 行即 opt-in，不装行零感知。载荷一期含 hook_event_name / tool_name / tool_input / cwd（PostToolUse 增 tool_response）
- **插件可选依赖**（工单 01，ADR-0019）：插件新增 `optionalInject()` 声明"就绪则用、缺失不拦"——可选服务缺席不再永久 PENDING，服务出现/消失自动重载（升级↔降级双向对称）；CLI 支持纯对话装配：boot yml 不装 fs 工具行照常启动聊天，`/permission` 降级提示"未挂载"
- **awaitStartup 可配超时**（工单 02）：编程挂载新增 `awaitStartup(Duration)` 重载——超时抛点名异常（含缺失服务清单）、零时长即立即探测；超时后插件保持 PENDING、服务到达照常激活；无参版语义不变（无限等待），但进入等待打 INFO 日志点名在等谁——编程挂载的静默卡死从根上消除
- **duo-code-review 技能重写并瘦身**：保留本仓库特有内容——开工前检查（固定点可解析、diff 非空、双轴前提）、覆盖率台账（基数取 `git diff --stat` 全集，每文件只有"已审 / 跳过(附理由)"两个终态）、阻断 / 建议 单一分级与文档核对词表、九项手工维度、报告模板与修复权限闸门；OCR 的用法描述不再自行复述，收敛为一句"按照官方 `open-code-review` 技能执行"（环境检查、命令参数、降级与陷阱以官方技能为准，委托模式同 `open-code-review-delegate`）；保留实测得出的非官方事实——OCR 默认不审 `src/test/**` 与文档（自定义 rule 也捞不回来），测试与文档划归手工必查项；新增报告持久化要求——审查结束即落盘（单工单并入工单「审查轮」小节，批次/分支审落 `.scratch/<feature>/reviews/日期-范围.md`），修复轮同处续写处置结果

### Fixed

- **管线超时双呈现位叠挂**（M17 backlog 双挂债）：cli + web 双开共享工具域时管线超时监听器被各挂一次（嵌套超时、语义含混）——挂载查重先到先得，第二次挂载跳过

## 0.12.0（2026-09-18）

### Added

- **并发工具调度**（工单 01，ADR-0018）：单轮多个 tool_calls 按并发安全性分流执行——`read`/`glob`/`grep` 等纯只读工具进虚拟线程并行池同时跑（调研类轮次总耗时接近最慢者而非逐个累加），写/命令/交互/子代理等独占工具作顺序屏障单独执行（屏障期间不与任何工具同飞），需审批调用永不进池；无论完成先后，工具调用与结果事件严格按 model 序成对提交——事件日志形态与串行时代同构，断线重连、刷新恢复、崩溃闭合行为零变化。并发度 `maxParallelToolCalls` 缺省 10、`web`/`cli` 插件 config 可配，配置为 1 即完全串行（排障开关）
- **工具执行超时推广为管线缺省**（工单 02，ADR-0018）：任何工具最多执行 120s（`pipelineTimeoutMs` 可配）——卡死的工具以超时错误结果回填、循环继续，会话不再被一个挂死的读取永久占住；`ask_user` 等人回答豁免不限时；bash 保留模型可传 `timeoutMs` 的协作式超时与杀进程树语义（管线上限放宽到协作式之上只兜挂死）
- **todo_write 任务分解抓手**（工单 03，ADR-0018）：多步任务开工前先拆成结构化清单（全量整表替换，pending / in_progress / completed 三态）——Web 输入框上方常驻折叠面板（圆圈状态实时流转、刷新恢复、新话题自动清空）+ 会话流工具行摘要 + CLI 计数行；模型只收一句计数回显，完整清单只走事件流，不重复撑大上下文
- **迭代上限 Web 可见化**（工单 04，BUG-20260917-03 验收遗留）：agent 达迭代上限返回未完成时，Web 面直推一帧错误卡（含失败说明）——页面不再无提示地停住（CLI 原有 `[异常终止]` 行为不变）

### Fixed

- **超时后迟到结果覆盖错误**（工单 02 验收实测）：超时中断工具后，被中断工具的迟到返回值可竞态覆盖超时错误（flaky 形态，时序运气下偶现正常）——超时结果终局冻结，迟到的真实结果一律丢弃
- **todo 面板跨会话残留**（工单 04 验收实测）：新建会话后上一会话的任务清单面板残留在输入框上方——整窗替换基线补清空动作，新建/切换会话两路径同治

## 0.11.0（2026-09-18）

### Added

- **会话崩溃恢复——悬空工具调用合成闭合**（工单 08）：进程在工具调用落盘与结果落盘之间中断（崩溃/审批等待被中断），会话留下无结果的悬空调用，后续每轮请求被 provider 以协议错误拒绝、永久无法续聊；现打开会话取锁后自动探测并追加合成闭合（结果注明"因进程中断未知，只可重试只读/幂等操作"），日志自包含、历史损坏会话打开即自愈
- **LLM 流式空闲超时**（工单 05）：连续 90s（`llm.streamIdleTimeoutSeconds` 可配）无新字节即中止流——provider 半开连接不再永久挂死 agent 循环（CLI 卡死/Web 单飞占用）；尚未输出内容时按可重试错误走重试链，已输出内容后中止并保留已生成文本
- **子代理审批钉死**（工单 03，M15 已知限制消除）：子代理调用声明需审批的工具不再挂起等待人工——确定性拒绝并回传理由（附交回父代理指引），子代理循环继续、在最终回答中说明限制；策略为装配处注入的恒否对象，放宽只换注入。**行为变更**：需审批声明涵盖区内写（档位闸门原会静默放行）——子代理的写产出步骤现在回退父代理执行（M15 的 researcher 写报告流程改由父代写）
- **子代理运行环境段**（工单 04，M15 已知限制消除）：子代理 system 携带工作目录/操作系统/当前时间（spawn 时现场生成），模型不再对运行环境两眼一抹黑
- **Web 入口栅栏**（工单 02）：Web 面全部端点前置两级校验——全请求 Host 头白名单（回环地址+端口，封死 DNS rebinding）+ POST 端点 Origin 空/同源校验（跨站 POST 一律 403）；本地 curl 与同源浏览器不受影响，GET/SSE 不校验 Origin
- **日志规范统一**（工单 06）：llm/session/agent/web/cli 五模块诊断日志迁入 SLF4J（级别可关断）——agent 降级诊断 warn、Web 异常与连接观测 info/debug；REPL 交互输出与启动横幅保持 stdout 不变
- **迭代上限呈现位可配**（BUG-20260917-03）：`web` / `cli` 插件 config 新增可选 `maxIterations`（正整数，缺省 10 不变）——单轮对话的 LLM 往返上限从此可按部署调节；非正整数启动即 FAILED 点名

### Changed

- **WebFace 路由表拆分**（工单 07）：注册方法从 333 行内联收敛为 13 行路由表 + 11 个端点处理器 + 统一响应 helper，新增端点不再嵌进巨方法
- **回答端点结构化协议**（工单 07）：`POST /api/answer` 改 `{decision: "approve"|"reject"}` 与 `{answers: [...]}` 两种互斥形态（前端同 diff 切换、无兼容层）——旧实现以自然语言"拒绝"作隐式协议，用户在提问卡输入"拒绝"二字会被误判为审批拒绝；新协议按字段判定，根治该误判
- **静态资源禁缓存**（工单 07）：单页与 /web/ 资源响应加 `Cache-Control: no-cache`——前端更新刷新即生效，不再依赖强刷

### Fixed

- **计划模式在真实任务上被迭代上限硬停**（BUG-20260917-03）：计划模式的引导式探索（连读多份文档/技能再设计）在真实仓库上常超十轮，循环在 `exit_plan_mode` 呈交前以"已达最大迭代轮数"终止、计划卡永不出现；现可经 `maxIterations` 显式调高预算（缺省行为不变，防失控硬停保留）
- **双呈现位下计划呈交卡不可达**（BUG-20260917-04）：CLI 发起的计划复核此前无审计留痕——双开部署（CLI 与 Web 必为不同会话）下浏览器收不到任何计划事件、卡永不渲染，请求又被优先路由给 Web 回答者致终端也无提示，阻塞至超时；现计划复核与审批同通道留痕（`approval/requested` 携 `exit_plan_mode` 身份与计划全文），浏览器计划卡正常渲染与作答，纯 CLI 部署改由终端呈现计划全文与复核选项

## 0.10.0（2026-09-17）

### Added

- **子代理任务分解**（M15，ADR-0015）：父 agent 可经 `spawn`（全新）/ `fork`（带父对话背景播种）把子任务交给同进程内嵌的子代理——立即返回 agent id，子代理在后台虚拟线程用主 agent 同款循环（LLM ↔ 工具执行回填）独立运行，完成后最终回答自动回流父对话；子代理事件独立成档（`~/.duo/agent-sessions/subagents/`，不进侧栏），完成后可打开回放全程；fork 播种父日志平衡完成轮前缀并落种子边界（继承背景与子代理自身行为可区分）
- **子代理控制面**：`send_message`（运行中纠偏——指示写入子会话下一轮生效；空闲/失败则开新轮）/ `interrupt_agent`（协作式中止，子会话留可审计的中止痕迹）/ `list_agents`（状态四态：运行中/空闲/失败/已中断）
- **子代理模板制装配**：yml 的 `subagent` 插件 config 定义子代理能力边界（工具清单 + 可选专属提示 + 可选迭代上限，缺省 30），spawn/fork 时模型只点名模板——工具配置权在部署者；交互工具与控制面五件强制不进子模板（框架过滤不可绕过）；未配置模板的部署零变化
- **子任务卡呈现**：Web 对话面子任务卡三态（运行中/完成/已中断）随 SSE 实时流转，完成态携结果概要折叠与"查看子任务全程"入口——右侧抽屉复用主对话渲染器回放子会话全程（含 fork 播种的背景段，种子边界居中标注）；CLI 打印 `[子任务]` 派生/完成过程行（完成行携最终回答）
- **子代理框架基线**：子代理 system = 框架基线（无跨任务记忆、只做交接的一件事、范围外不深挖、结果被截断改精确查询、结论即交付）+ 模板专属提示——通用纪律归框架一处维护，部署者模板只写角色

### Fixed

- **子代理治理管线未生效**（0.10.0 验收期实测发现）：子代理此前在 yml 未配 governance 段时完全不治理（与父的"缺省常量治理"不一致）——大量工具结果全量灌入上下文；现与父严格同配置（恒建治理实例）
- **治理过程日志刷屏**（M9 既有）：修剪/计量逐轮 println 在子代理后台长跑时淹没对话流——默认静默，诊断用 `-Dduo.governance.verbose=true` 开启
- **子代理未完成时成果丢失**：达迭代上限时父会话只收到一句"未完成"——现回流部分成果（工具调用摘要 + 末次结果摘录）与续跑指引（`send_message` 可带完整上下文续轮）；CLI 同步呈现摘要摘取

## 0.9.0（2026-09-16）

### Changed

- **limitations 收编定稿**（M14 工单 06）：已知限制清单恢复"唯一权威来源"完整性——"M7/M8（未发布）"陈旧标题改正为 0.3.0，补录 M9-M11（0.4.0-0.6.0）留档限制三条（CLI idle 无热恢复、交互工具会话绑定先到先得、compaction 无手动入口），过期去向标注清理；配合分页投影优化，"每次全量投影"限制条目消除（见下）
- **事件快照读侧零拷贝**（M14，ADR-0014）：`Session.events()` 从每次锁内全量拷贝改为共享不可变快照——追加在锁内重建、读侧 O(1) 返回同一引用；对外语义不变（调用时刻稳定视图、与追加并发隔离、遍历无 CME），投影/回放/分页等读侧消费方自动受益
- **分页定窗单趟化**（M14，ADR-0014）：消息窗口计算从三趟全量遍历收敛为单趟（O(max) 下标环形缓冲 + earlier 基线扣减回折前移量），窗口边界语义逐字不变；80K 事件会话"定窗+投影"实测 17.1ms → 7.6ms（-56%，达标线 15ms），基准转正为 `SessionPerfBenchmarkTest`（默认跳过，`-Dperf.benchmark=true` 启用）——已知限制"分页与尾部快照每次全量投影"就此消除
- **agent 按域拆包**（M14 工单 05，duo-project-structure 达标）：根包 27 类收敛为"循环契约门面（6 类）+ 四域子包"——`governance` / `skills` / `plan` / `prompt`；**注意**：yml 插件行中的插件类为全限定名反射加载，prompt/skills/plan 域插件类名随之带子包路径（如 `dev.duo.harness.agent.prompt.PromptPlugin`、`dev.duo.harness.agent.skills.SkillsPlugin`），自写 yml 需同步更新（demo yml 与文档示例已迁移）
- **装配测试密闭化**（M14 工单 01）：DuoHome 解析链新增最高优先级的系统属性 `duo.home`（`duo.home` > `DUO_HOME` 环境变量 > 缺省 `~/.duo`，部署侧 `DUO_HOME` 语义不变）；两处装配用例改临时目录自足——`mvn test` 不再依赖本机 `~/.duo/config.yml`

## 0.8.0（2026-09-16）

### Added

- **会话尾部窗口快照**（M13，ADR-0013）：Web 面首连/刷新/切换不再全量回放——服务端投影取尾部 50 条消息的事件区间下发（头帧携 hasMore 与更早计数），大会话秒开；游标增量回放语义不变
- **历史向上分页**：新端点 `/api/session/page`（按事件序号向前取每页 50 条投影消息，携 hasMore 与更早计数）；前端滚动到顶自动加载更早一页，顶部占位"更早还有 N 条"，视窗不跳屏，耗尽后占位消失
- **切换与新建无刷新**：侧栏切换 / ＋新话题不再整页重载——断流 → 换绑 → 重连收新会话尾部快照整窗替换，页面状态不再因重载丢失（切换/新建时输入框按语境显式清空）
- **治理阈值 yml 化**（ADR-0013）：`web` / `cli` 插件 config 新增可选 `governance` 段——spill/修剪阈值、压缩比例、窗口 tokens、保留比、最小折叠数六字段可省（缺省即 0.7.0 行为）；未知字段、类型与数值越界启动即 FAILED 点名；双开两段互不同步为已知取舍
- **侧栏占用标注**：`/api/sessions` 逐会话占用探测（occupied 字段）——被占会话灰显标"使用中"，只提供预期，点击仍可尝试（撞锁报错保留）
- **会话标题（精简版）**：首条消息落日志后异步生成一次（独立限时直答，失败/超时降级为首条前 20 字），以 `session/title` 事件落会话日志；侧栏显示标题（无标题回退 id）、浏览器标签页实时同步；不重生成、不可改名

### Fixed

- **流式输出中途刷新丢内容**（0.7.0 既有）：刷新/断线重连后，进行中回复的已输出部分不再空窗，流结束整段覆盖不重复、不碎片化
- **切换会话后发消息必报错**（M13 验收期发现）：会话变更回调单槽被标题接线覆盖导致 agent 不重建（分脑回归），已合并为单次注册

## 0.7.0（2026-09-15）

### Added

- **本机 fs 工具族六件**（M12，ADR-0012）：新插件 `tools.fs.FsToolsPlugin`（yml 一行装配）——`read`（三帽窗口 + 精确总行数 + 自描述续读 footer + 二进制拒读）、`write`（原子替换）、`edit`（LF 归一匹配域 + 四态结构化失败 + `replace_all`）、`glob` / `grep`（Java 自实现，跳 VCS 目录，截断回收）、`bash`（每次调用全新进程、工作目录固定 workspace 根、env 硬化、stdin 接空设备、超时 clamp 缺省 120s 上限 600s 并终止进程树、每流 100K 字符护栏超限报省略量、非零退出以 `[exit code: N]` 进正常结果而非错误）。agent 从 MCP 沙箱演示级文件能力升级为 workspace 约束的真实项目操作能力
- **三档权限预设**（M12，ADR-0012）：`read-only` / `workspace-write`（默认）/ `danger-full-access`——路径感知的审批裁决：区内写放行、越界写与 bash 及 read-only 档写一律 ask（档位闸门 `WorkspaceGatePolicy` 前置短路，ask 委托既有审批管线）；CLI 新增 `/permission [档位]` 运行时查看与切档（重启回 yml `mode` 缺省）
- **读前写闸门**：`write` / `edit` 覆盖已有文件须本会话先用 `read` 读取（未读拒绝并提示先读，新建豁免）——不盲改未见过的文件
- **档位审批插件 `WorkspaceApprovalPlugin`**：与 `ApprovalPlugin`（always-deny / auto-approve）、`InteractiveApprovalPlugin`（无档位全 ask）三选一的审批策略，ask 落既有回答者瀑布（Web 卡片 / 终端 y/n）

### Changed

- agent demo（agent-demo.yml）移除 MCP files 沙箱挂载与写保护演示行——本机 fs 工具族取代，模型工具清单不再有 `mcp__files__*` 双写选型噪音；`AgentReplMain` 收敛为纯启动入口（无编程挂载段，启动命令不变）；MCP 机制演示保留在 DemoMain 的 M2 段（`demo-m2.yml`，mcpfs 模块保留）
- Web 状态面工具清单标题改"工具（本机 + MCP 远端）"；运行Demo / 组装你的第一个 agent / 插件配置参考 / 工具目录 / limitations 文档对齐 M12 装配

## 0.6.0（2026-09-15）

### Added

- **CLI 呈现位插件**（ADR-0011）：新模块 `duo-harness-cli`——终端 REPL 成为与 WebPlugin 对称的 Boot 插件（yml 一行启停，`disabled: true` 可保留配置地关闭），`/exit` 只结束终端呈现（会话锁释放、回答者摘除，插件树与 Web 面不受影响）；交互行为与既有 CLI 一致；纯 CLI / 纯 Web / 双开三种部署形态均成立（双开时回答者按 yml 行序路由，详见插件配置参考）
- **通用启动器 `DuoMain`**（ADR-0011）：Boot 装载 + 非守护保活 + shutdown hook 级联 dispose——Ctrl-C 确定性释放全部会话锁；不含业务装配，demo 专属挂载（MCP 沙箱等）经回调注入；`AgentReplMain` 瘦身为兼容壳（启动命令不变）
- **呈现位共享装配器**（agent 模块 `presenter` 包）：CLI 与 Web 的执行链装配单点（LLM 执行链工厂 `llm.LlmAdapters` / 治理 / ChatAgent / 交互工具查重注册），消除双份装配漂移

### Changed

- Web 面重试行为对齐配置：`llm.retry` 段现对 Web 面生效（此前仅 CLI 装配读取，Web 用固定默认）——未配置该段时行为不变（缺省值两侧一致）

## 0.5.0（2026-09-14）

### Added

- **上下文占用可视化**（M10，ADR-0009）：Web 状态面新增「上下文」行——`N / 窗口 tokens（占比 %，压缩阈值 M · 实测/估算）`，与治理判定**同源同口径**（provider 真实用量优先、本地估算兜底，口径显式标注），超阈值整行变红；治理触发不再只存在于终端日志
- **provider 真实 token 用量**（M10，ADR-0009）：LLM 请求携带 `stream_options.include_usage`，流末用量统计作为 `assistant/message` 事件可选字段落会话日志（历史会话向后兼容）；治理判定数据源切为真实值优先、估算兜底——阈值不再被 ±10-20% 估算误差干扰
- **SSE 增量回放**（M10，ADR-0010）：事件帧携带日志序号游标，浏览器断线重连自动经 `Last-Event-ID` 只补缺失段；首连与游标越界仍全量快照（宁可重放不可丢事件）；`replay/start` 帧标注模式，前端快照清空重建、增量保留页面
- **Markdown 渲染**（M10）：助手回复按 Markdown 整段渲染（代码块 / 列表 / 标题 / 引用 / 表格），marked + DOMPurify 单文件 vendor 入库（零 CDN、离线可用）；模型输出经消毒防注入；流式期间保持纯文本（避免半截语法闪烁）
- **交互反馈与错误可见化**（M10）：发送按钮「思考中…」状态机（本轮处理完成才恢复，与服务端单飞精确对应）；页面顶部 toast 提示（网络/服务故障、会话操作失败 5 秒可见，持续故障不刷屏）；原先静默吞错的五处路径全部改为可见处理
- **会话独占锁**（M10）：打开会话即取得文件独占锁——同一会话被第二个进程打开时明确报错（Web 面启动失败点名会话、CLI 提示后可改开新会话、页面切换提示占用），彻底消除"两个进程静默分脑共享同一日志"
- **纯 Web 部署 HITL 补全**（M10）：`WebPlugin` 装配链自行注册 `ask_user` 与计划呈交工具——无终端环境下提问卡与计划卡照常工作
- **Web 服务端加固**（M10）：会话切换 id 白名单校验（目录穿越防护）、POST 请求体 1MB 上限、错误响应不回显内部异常细节（仅服务端日志留痕）
- **fail-closed 语义钉死**（M10）：悬空交互的拒绝判据精确为"是否仍有人能看见它"——全部页面关闭且宽限期内无新连接入列才拒绝，**刷新页面不再误杀悬空审批**（卡片保留可继续作答）

### Changed

- Web 静态单页拆分为 `index.html` + `theme.css` + `app.js` 三件（无构建链维持，`/web/` 前缀白名单资源服务）
- `/api/status` 响应新增 `context` 字段（无治理装配时省略）；LLM 请求增加 `stream_options`（provider 不支持时无用量字段、治理自动回退估算）

## 0.4.0（2026-09-14）

### Added

- 上下文治理四件套（M9，agent 域）：`ContextGovernance` 读侧治理管线——spill（超大工具结果落盘 + 预览定位符）→ 工具结果修剪（超 8K 头尾收窄）→ token 计量（本地估算）→ compaction（超窗口阈值时远端历史折叠为四节摘要，近端原文保留）。**治理只影响模型看到的请求，会话 JSONL 日志永远完整**；各阈值常量集中于 `ContextGovernance`，演示装配默认启用
- `ToolCallingAgent` 新增治理构造器：投影 → 治理管线 → 请求；旧构造器保留（null = 不治理，零行为变化）

## 0.3.0（2026-09-14）

### Added

- 文档站补全（M8.5）：04-架构《设计主线》（框架叙事 + ADR 导览）、02-指南《组装你的第一个 agent》（可照抄教程）、05-参考《插件配置参考》《会话事件类型表》《工具目录》（含对账测试防漂移）与《术语表》（CONTEXT.md 迁入，仓库根留指针）——框架描述 / 设计思想 / 使用方式三层齐备，五章节骨架首次全量
- LLM 适配器（`duo-harness-llm` 新模块）：provider 中立流式调用契约 + OpenAI 兼容适配器——`baseUrl/apiKey/model` 配置化，DeepSeek/通义/Kimi/vLLM 等兼容 provider 开箱即用
- 聊天演示 `ChatReplMain`：REPL 交互（`你> `/`AI> `、`/exit` 退出）、流式打印、错误原样呈现；多轮对话有上下文记忆，启动自动继续最近会话（`/new` 开新话题），会话落 `~/.duo/sessions` 可回放
- `LlmConfig` 支持 `llm.systemPrompt` 可选配置（缺省内置指令；组装注册表属 M6）
- 会话模块（`duo-harness-session` 新模块）：会话事件溯源——`append` 唯一写入原语 + JSONL 同步落盘，`deriveMessages` 投影多轮上下文；旧格式会话文件向后兼容（M4）
- agent 模块（`duo-harness-agent` 新模块）：`ToolCallingAgent` 工具循环——Function Calling 闭环（模型自主发起工具调用 → 经三段管线与治理链执行 → 结果回填 → 最终回答），迭代上限防失控；审批拒绝 / guard 拦截结果原样回填，模型自行调整行为（M5）
- agent 演示 `AgentReplMain`：LLM 驱动 MCP 文件工具的完整闭环——读文件真实生效；写文件被审批拒绝后模型理解原因并向用户解释，过程叙述全程可见（M5）
- 思考模型支持：流式捕获 `reasoning_content`，工具调用链中按 provider 要求回传——DeepSeek thinking 模式下多轮工具调用不再 400（M5）
- 交互 seam（M6 工单 01）：`answers` 交互服务（回答者注册制 + 注册序遍历 + fail-closed）与 `interactive` 审批策略（`InteractiveApprovalPlugin`，inject answers）——ask 三态首次可由人作答；会话新增 `approval/requested` / `approval/decided` 审计事件（可选字段向后兼容，投影跳过）
- 交互审批 fail-closed 语义（ADR-0008）：无回答者、人未作答（EOF/中断）一律拒绝；不做"永久放行"
- prompt 注册表（M6 工单 02，agent 域 "prompts"）：插件经 `register` 贡献提示片段（随作用域摘除、按注册序动态组装）；yml `llm.systemPrompt` 为最前用户片段，全空落内置缺省——M7 技能指令段的挂载点
- `ask_user` 提问工具（M6 工单 03，tools 域）：模型发起的交互——参数最小 schema（question + 可选 options/multiSelect），执行本体经交互 seam 等人作答，回答即工具结果；走六段管线；无人应答 fail-closed 收敛为错误结果
- `RetryingAdapter` LLM 重试装饰器（M6 工单 04，llm 域）：对网络故障与 429/502/503/504 指数退避重试（默认 3 次）；协议与凭证错误（400/401）直通不重试；流式安全——已交付增量后不再重试。消除 M5 已知限制"无自动重试"
- 重复调用提醒插件 `RepeatReminderPlugin`（M6 工单 04，example 治理插件示范）：同一工具相同参数连续重复达阈值（默认 3/5/8）时在结果尾部附加逐级加码提醒（advisory，非 guard）；阈值可配置
- AgentRepl 升级为 HITL 演示（M6 工单 05）：写操作终端 y/n 逐次审批（console answerer + 审计桥，决定落会话）、模型 ask_user 提问（选项序号/自由文本）、`/new` 开新话题；`llm.retry` 段可配重试参数；prompt 注册表演示片段
- 会话 `tool/call` 事件持久化思考内容（reasoning 字段，可选向后兼容）：思考模型长工具链的历史请求天然完整（provider 要求回传），跨进程恢复可回放思考过程（M6 验收缺陷根治，BUG-20260913-03）
- Web 双面地基 API（M8 工单 01）：core `Context.snapshots()` 只读插件状态快照（M8 状态面数据源）+ session `Session.addListener` 事件订阅（M8 事件流推送源，注销器摘除）——纯新增向后兼容
- 对话面（M8 工单 04）：`POST /api/message` 装配全套 M5-M7 对话执行者（工具循环 + 重试 + prompt 注册表）异步执行——chunk 与工具调用/结果经 SSE 实时推送渲染为合一工具卡（状态徽标三色：运行中/成功/失败）；`POST /api/session/new` 开新会话；单飞串行（执行中再发 409）
- 会话事件 `run/error`（M8）：运行错误直推帧（不落会话历史），页面渲染 [错误] 卡
- Web 面接入 demo 装配（M8 工单 06）：agent-demo.yml `web` 行——一条命令同时具备 CLI 与浏览器双入口（会话目录单入口约定）
- HITL Web answerer（M8 工单 05）：`WebAnswerer` 实现交互 seam（M6）——待答审批经 SSE 推送为页面按钮卡片，点选后 `POST /api/answer` 完成；**SSE 断连/超时一律 fail-closed**（悬空请求自动拒绝，人不在环 = 不批准）；ADR-0008 验证：呈现位零改动机制核
- Web 单页重构为三区布局（M8）：会话侧栏（`/api/sessions` 列出 + `/api/session/switch` 切换 + 当前会话高亮 + ＋新话题）/ 对话面 / 状态面，样式对齐冻结原型（亮色 DSH token）；EmptyHero 新会话初始态；合一工具卡（状态徽标 + 可折叠结果 + 治理提醒独立标注）；审批 / 计划呈交 / 提问三张交互卡（事件委托，`approval/decided` 回放冻结）
- `AuditingAnswerer` 审计桥提升至 agent 模块（会话经 Supplier 延迟解析）：CLI 与 Web 装配共用——Web 装配经它把审批请求/决定落会话事件，驱动页面审批卡（M8 工单 05 接线修复）
- Web 双面骨架（M8 工单 02）：`WebPlugin`（Boot yml 一行，只绑 127.0.0.1，默认 18080）+ 静态单页 + `/api/status` 状态 JSON + `/api/events` SSE 会话事件流（存量回放 + 实时推送，虚拟线程执行器）
- 技能系统（M7 工单 01，agent 域 "skills" 服务）：SKILL.md 目录包与单文件 `<name>.md` 双形态，四根发现（`.duo/skills` → `.agents/skills` → `~/.duo/skills` → `~/.agents/skills`，同名高优先根胜），启动加载、清单片段进 prompt 注册表；`skill` 工具供模型按名加载指令全文（走六段管线）；yml 禁用配置
- prompt 注册表插件化（M7 工单 01）：`PromptPlugin` 发布 "prompts" 服务（config.systemPrompt 为最前用户指令片段）——技能清单、AGENTS.md 等装配级片段的注册点
- AGENTS.md 注入（M7 工单 02，agent 域）：`AgentsMdPlugin` 加载 `~/.duo/AGENTS.md`（用户全局）+ 项目根 AGENTS.md（.git 定根），64KB 预算超限截断，注册为 agents-md 片段进 prompt 注册表——项目约定对运行时 agent 自动可见
- 技能用户直调（M7 工单 03）：AgentRepl 输入 `/技能名 [任务]` 即注入该技能指令全文——点名的能力立即生效；未知名提示可用技能
- 计划模式（M7 工单 04，引导式）：`/plan [任务]` 进入（挂计划指导片段：先探索再设计、不做修改性操作）、`exit_plan_mode` 工具呈交计划、用户批准后执行 / 打回带反馈继续；状态存 `plan/mode` 会话事件（续接恢复）；复核无人应答 fail-closed 保持计划模式
- AgentRepl M7 装配（M7 工单 05）：内置演示技能 release-notes（.duo/skills，dogfood 形态）；三路触发与计划模式端到端可验收

### Fixed

- 已知限制清单对账：删除"Web 双面界面未开始"（M8 已交付失效）；补记多标签共用服务端当前会话的边界（与 BUG-20260914-01 同源）
- 切换会话后消息"丢失"（BUG-20260914-01）：`/api/session/switch` 换绑后现重建对话执行者——此前 agent 仍写旧会话，消息落错会话（页面看不到自己的消息、刷新"丢失"、切到别的会话反而可见）；`Session.events()` 改快照语义，流式追加期间的并发回放不再中断
- 计划呈交在 Web 无卡片可答（BUG-20260914-02）：`exit_plan_mode` 现渲染为计划呈交卡（批准/打回带反馈），回答口径对齐工具判定——此前呈交后永久挂起至超时；`ask_user` 结果同样按工具名冻结提问卡

### Changed

- `LlmConfig` 从 `dev.duo.harness.llm.internal` 移至 `dev.duo.harness.llm` 根包（平铺契约约定对齐：跨模块消费的配置 record 属公共契约）
- DSH 核心功能全景研究落盘（`docs/research/DSH/核心功能全景.md`，锚点 c291e796 = release 0.1.5）：agent 循环能力、内置工具目录、交互与 HITL 机制（审批 seam / ask-user / 权限预设 / plan 模式）、会话与上下文治理、扩展生态（skills / subagent / hooks / bundle / preset）、LLM 适配——含与 duo-harness 现状的事实映射表，供后续里程碑对齐参考

## 0.2.0（2026-09-11）

### Added

- 新模块 `duo-harness-llm`：provider 中立的 LLM 调用契约（流式 chunk 回调）+ OpenAI 兼容适配器（SSE 流式）；配置加载自 `~/.duo/config.yml`（`DUO_LLM_*` 环境变量可覆盖）——agent 能力第一块基石
- `DuoHome`（core）：用户级默认目录 `~/.duo` 约定（`DUO_HOME` 可重定向），会话/配置等运行时数据统一收在其下
- 新模块 `duo-harness-mcp`：经官方 MCP Java SDK 连接 MCP 服务器（stdio）——断连自动重连（指数退避 + 稳定窗口 + 预算耗尽）、远端工具自动同步进工具域（`mcp__<server>__<tool>` 命名，`list_changed` 自动重同步）；插件停止即断连并注销工具
- 审批策略服务：pre-execute 决策三态（allow / deny / ask）——治理插件或工具声明需审批，策略服务裁决；预设 `always-deny`（缺省，未配置即拒）与 `auto-approve`（白名单）；审批决策审计日志
- 输出契约：`ToolDefinition.output()` 声明结果 JSON Schema，违约转 error 结果点名原因；MCP 远端 `outputSchema` 同标准（双轨制，未声明宽松透传）
- guard 单调否决：`ToolsService.guard(registrant, check)`——审批之后、本体之前的动态拒绝，理由即拒、null 放行、拒绝无法翻回；随注册作用域销毁自动摘除
- demo 扩展 M2 段：一条命令演示 MCP 连接、远端工具调用真实文件、审批拒绝与 guard 拦截、拔连接后工具消失
- 文档站上线：`https://dengdz.github.io/duo-harness/`（VitePress 构建，push main 自动部署）；docs/ 即站点源目录，内部开发文档不上站（ADR-0005）

### Changed

- MCP Java SDK 0.10.0 → 0.18.1（传递依赖 mcp-core + mcp-json-jackson2，networknt json-schema-validator 2.0.0 随之引入）

## 0.1.0（2026-08-25）

### Added

- 引入 agent 工程规范体系：`AGENTS.md` 路由总纲（需求分流、显式命令、红线）
- 引入 9 个 `.agents/skills/` 技能：duo-workflow（含版本号规范）、duo-code-review、duo-tracker、duo-pre-push-checks、duo-release-workflow、duo-prose-standard、duo-project-structure、duo-trim-cot-leakage（含示例与检索模式集）、duo-doc-standards
- 引入领域文档 `docs/agents/`：`domain.md`（术语表）、`issue-tracker.md`（本地工单规范）
- 引入 `.gitignore`（构建产物、IDE、密钥环境、日志；`.scratch/` 随库入库）
- 引入 duo-research 技能与 `docs/research/` 研究资产：首份产出为 DSH 项目总览（架构、模块、数据流、技术栈、目录结构）
- 新增 DSH 插件化架构研究三件套（`docs/research/DSH/插件化架构/`）：Cordis 容器核心（Context/Fiber/Registry/Reflect/Events）、profile→bundle patch 层序合成、工具/MCP/页面三类插件的插拔机制、生命周期与依赖驱动启停、双面模块系统——为从零构建插件化框架提供架构参考
- 完成插件化框架方案 grilling：建立术语表 `CONTEXT.md`（13 个领域术语）与四份 ADR（自研插件容器内核、同步 API 与虚拟线程、Jackson 统一序列化与配置绑定、三支柱先行路线图）
- 发布 M1 spec（`.scratch/m1-plugin-kernel/spec.md`）：插件容器内核 + 示例插件的 25 条用户故事、全部实现决策（含配置行结构与六态状态机浓缩形状）、双接缝测试方案
- M1 spec 拆分为 7 张 tracer-bullet 工单（`.scratch/m1-plugin-kernel/issues/01~07`）：依赖拓扑 01→{02,03}→04→05→07、06 旁路可并行，每张含验收清单
- 工单 01 落地：duo-harness 首个代码模块 `duo-harness-core`（插件容器内核最小闭环）——Plugin/Context/Disposable 公共 API、config record 的 Jackson 绑定（失败即加载失败、点名插件与字段路径）、副作用栈逆序回滚与级联停子、销毁后拒绝注册；9 个接缝 B 用例全绿；docs 立起 index 导航与架构篇（模块划分）
- 审查修复（行为变更）：config 绑定启用严格模式——record 缺字段由 Jackson 默认补 null/0 改为绑定失败（错误前移）；声明了 config 类型却未提供配置由静默传 null 改为点名报错；父作用域并发销毁时已启动子实例不再泄漏；销毁后注册/加载的拒绝行为与 null 参数快速失败写入 JavaDoc 契约
- 工单 02 落地：事件总线五种分派模式（emit 隔离 / parallel 虚拟线程并发聚合 / serial·bail 顺序投票 / waterfall 洋葱管线——否决、参数改写、返回值包装）；监听器表全树共享、注册即作用域副作用随插件停止自动摘除；引入 SLF4J（api + simple test），清理与隔离错误改为可观察的 warn 日志；30 用例三连跑全绿
- 工单 03 落地：服务注册表与视图接口寻址——provide/Service 基类（构造即发布）、`ctx.as(视图)` 动态代理（方法名即服务名、惰性解析、类型校验）、`Plugin.inject()` 依赖声明（未声明读取点名拒绝）、依赖缺失挂起 + awaitStartup 真实阻塞语义、服务注销与提供方插件停止的级联传导（依赖方自动停止）；注册表 (服务名, 作用域) 二阶键 isolate 预留；44 用例三连跑全绿
- 工单 04 落地：依赖驱动生命周期——六态状态机（PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED）与 epoch 依赖指纹：依赖消失回 PENDING、服务回归自动重启、换实现自动重启；状态迁移经 `plugin/status` 事件广播、`PluginHandle.state()` 可查询。行为变更：启动失败不再阻断 plugin() 调用，错误统一经 handle（awaitStartup 重抛 / state=FAILED），对齐 DSH fiber 语义；54 用例三连跑全绿
- 工单 05 落地：配置驱动 boot——`Boot.from(yml)` 单文件引导（行结构 id/name/config/disabled，行序无加载语义），收尾审计点名（FAILED 带原始错误、PENDING 列缺失服务、类不可加载点名），任何失败整树回滚后抛带阶段标签的 `BootException`；`Context.hasService` 存在性查询；66 用例三连跑全绿——M1 核心链路（配置 → 插件树 → 服务/事件/生命周期）闭环
- 工单 06 落地：`duo-harness-tools` 模块——工具域骨架与三段执行管线：`ToolsPlugin` 挂树发布 "tools" 服务，`register(registrant, def)` 注册即注册方作用域副作用（插件停止自动注销），execute 走 `tools/pre-execute`（准入否决）→ `tools/execute`（around 本体）→ `tools/post-execute`（结果改写/转错误）瀑布管线；工具异常收敛为 error 结果不上抛；76 用例三连跑全绿
- 工单 07 落地（M1 收官）：`duo-harness-example` 模块——示例插件集（服务提供者/消费者对、工具插件、管线拦截者、disabled 行）+ demo 配置 + DemoMain；验收命令 `mvn -pl duo-harness-example -am package exec:java` 一条命令输出 34 条状态/事件叙述，覆盖配置驱动 boot、行序无关、视图寻址、三段管线否决与治理、拔服务级联停止、整树回滚；`Boot.from` 补 prepare 钩子（对齐 DSH）；docs 立起 01-入门章节；77 用例三连跑全绿
- duo-code-review 技能补目录分层审查维度：新类落位对照 duo-project-structure（按功能域分包、禁 `controller`/`service`/`util`/`impl` 大筐、根包不放类、新包同 diff 带 `package-info.java`），diff 涉及的包越过约 10 个类的拆包阈值时要求按功能边界拆子包；堆放类问题按 suggestion 报
- 终审复检（行为变更）：boot 配置解析新增配置行 id 重复检查（重复 id 即审计锚点失效，按解析错误点名拒绝）；ToolExecution 构造器 null 契约自足；已知限制唯一清单 `docs/limitations.md` 建立（七条 M1 限制归口）；duo-code-review 技能固化 ocr 分批/换模型/续跑执行策略；79 用例三连跑全绿
- 文档审计修复：ADR 约定收紧为落卷即冻结（`docs/agents/domain.md`：Status 只标记立卷时点，决策被取代时新开 ADR 注明取代关系，旧文件不动）；README 与 CONTEXT.md 能力枚举统一（工具 / MCP / 页面 / agent 循环），README 版本口径对齐 CHANGELOG 锚点（0.1.0-SNAPSHOT，未发布）

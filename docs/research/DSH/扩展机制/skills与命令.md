# skills 与命令（DSH）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 13（T-17）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

DSH 把技能拆为「发现/合并」与「加载/呈现」两层：`ctx.skills`（SkillRegistry）只做 provider 合并与同名取胜，具体来源由插件供给——skill-filesystem（项目/自定义/用户/内置目录）、skill-badge、skill-office（内置资产）。命令面独立于模型：`ctx.commands`（CommandRuntime）是插件-owned 的人用命令注册表，execute 明确「不发送给模型」，仅留 command/run+done 审计日志（packages/interaction/commands/src/index.ts:332-343）。

- registry 合并：全局层+scope 链按远→近覆盖，同名近层直接胜出；层内按 rank→provider 注册序→局部序取胜（skill/src/index.ts:551-565, 807-811）。
- 命令不进模型历史：log-only 追加，无 turn 包裹（commands/src/index.ts:457-467）。

## 关键流程

① **skill 发现**：目录两种形态——`<dir>/SKILL.md` 或根下平铺 `*.md`（skill-filesystem/src/index.ts:728-733）。frontmatter 必填 name+description，可选 whenToUse、metadata；布尔字段 `disable-model-invocation`（缺省模型可调）、`user-invocable`（缺省 true），legacy 键直接报错（:1000-1016）。项目根以 `.git` 向上定位（:945-955）。热更新：chokidar watch 各根（depth:1，stabilityThreshold 200ms/poll 100ms，最多 128 项目，:41-43, 491-506），事件经 `queueInvalidation`→`control.invalidate`→revision+1 清缓存并发 `skills/change`（skill/src/index.ts:621-625）；根目录缺失时降级为 watchFile 祖先轮询（:456-470）；模型 edit/write 还会经 `fs/observed` 主动失效（:143-146）。provider list 失败→「不完整观察」不缓存可重试（skill/src/index.ts:530-548）。

② **skill 加载**：模型走 `skill` 工具（tool-skill/src/index.ts:81-161），入参仅 `name`；execute 先 list 校验存在与 modelInvocable，再 `ctx.skills.get` 取全文，输出经 `renderSkillContent` 渲染为 `<skill_content name="…"><skill_resources>…</skill_resources><skill_instructions>正文</skill_instructions></skill_content>`——**全文进 tool result**，resourceBase 目录提示附带相对路径解析规则（skill/src/index.ts:170-214）。目录呈现：agent/pre-step 监听器对 modelInvocable 技能发 `<system-reminder><available_skills>` 目录消息，按 entries 的 sha256 digest 去重、变化时发「替换目录」更新（tool-skill/src/index.ts:213-251, 328-335）；description 截断 500 字符（:27, 391-394）。

③ **命令注册**：`commands.register({definitionId?, name, description, input?{hint,attachments?}, recordInput?, handler})`（commands/src/index.ts:61-78, 285-292）。执行流 `execute(agent,line,attachments,signal)`：parseCommand→解析失败/未知名返回 undefined（不入日志）→先写 `command/run`（含 args，recordInput:false 可省）→附件声明校验→handler→`command/done`（:361-431）。内置命令如 /compact、/plan、/goal、/feedback 各自插件注册（如 compaction/command-compact/src/index.ts:101-108）。

④ **GUI/CLI 入口**：客户端 CommandUiRuntime 向 inputTriggers 注册 `/` 触发源，候选=host 目录（`commands.list` 远程拉取）+客户端贡献，Enter 走 matchEnter 决策表（声明 input 则 claim 收参，否则 detached execute）（client/ui-commands/src/client/service.ts:96-104, 283-330, 387）；API/CLI 面直接 `remote.commands.execute(sessionId, line, [])`（api/session-controller/src/client/sessions/session.ts:373）。**与 `/技能名` 的关系**：技能不是命令——只有命中命令注册表的 `/xxx` 在客户端被截走，其余整行作为 user 消息进模型；tool-skill 的 pre-step 用正则扫描 user 来源消息（防伪造），命中 user-invocable 技能则把渲染正文作为 instructions 注入追加到消息末尾；这是 `disable-model-invocation` 技能的唯一入口（tool-skill/src/index.ts:171-204, 409-431）。

## 接口与参数要点

- 名称正则：skill `/^[a-z0-9]+(?:-[a-z0-9]+)*$/`（skill/src/index.ts:21）；command `/^[a-z][a-z0-9_-]*$/u`（commands/src/index.ts:32）。
- rank 表：project-dsh 100 < project-agents 200 < custom 300 < user-dsh 400 < user-agents 500 < bundled 600（skill-filesystem:36-40）；runtime 250（skill:25）。
- skill 工具入参 `{name: string, required}`；frontmatter 布尔容错 true/false/1/0/yes/no/on/off。

## 边界与坑

- 技能名必须 kebab-case，非法即 provider 抛错/文件忽略（skill:711-712, skill-filesystem:820-823）。
- skill-office 启动即校验资产：assetRoot 绝对路径 + 必含 `scripts/check_office.py`（skill-office/src/index.ts:29-35, 44-48）。
- 同名 rank 冲突不报错：低优先级被丢弃并 warn（skill:574-580）；runtime 同名 first-wins+空 disposer（:443-446）。
- 命令附件未声明 `input.attachments:true` 即 error 结果，在 handler 之前拦截（commands:391-393）。
- watcher 失败仅降级为 incomplete 观察，不阻断发现（skill-filesystem:186-202）。

## 对 duo 的启示

duo 现状：M7 技能启动扫描无热加载、/技能名直调（M19 命令注册表）、单源无 rank。对照四条：

1. **多源 rank 合并**——DSH 用 100-600 的 rank 表让项目/用户/内置同名可预期取胜且只 warn 不炸，duo 多来源技能可借鉴此静默优先级。
2. **watch 增量失效**——DSH 的 revision+digest 模式（清缓存→`skills/change`→目录按 sha256 决定重发/替换）可让 duo 会话中途装/改技能即时生效，目录消息只重发一次。
3. **注入形态统一**——DSH 让工具加载与 /技能名直调共用同一 `renderSkillContent`（模型看到规范 `<skill_content>` 块），duo 两条路径若渲染不一致易致模型行为分叉。
4. **人用/模型用分流**——DSH 命令绝不进模型历史、技能绝不进命令注册表，两个封闭命名空间避免了 `/xxx` 归属歧义，duo 的 /技能名 与命令注册表宜明确声明优先级。

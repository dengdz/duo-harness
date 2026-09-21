# skills 与命令（ZCode）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑工单 13（T-17）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

技能发现走「多根合并→一层扫描→frontmatter 解析→按名注入 metadata」链：`resolveDefaultSkillRoots` 把配置 extraRoots、user 级 `~/.zcode|~/.agents/skills`、project 链（cwd 上溯到 .git worktree 根，每层双生态目录并存）、插件 extraResolvedRoots 合成一列，优先级以步进 10 递增（apps/zcode-cli/packages/adapters/src/skills/roots.ts:8,28-56,99-105）。`scanSkillFilesUnderRoot` 只认根自身 SKILL.md + 一级子目录（scan.ts:58-72），ENOENT 视为常态吞掉、EACCES 等上抛（scan.ts:144-155）；plugin-scope 内容不可信，根/子目录/SKILL.md 三粒度一律拒绝 symlink（scan.ts:16-23,47-49,64,105-106），用户级根默认跟随以保留 symlink 导入。NodeSkillAdapter 用自研扁平 YAML 解析 frontmatter（支持 `>`/`|` block scalar，index.ts:299-346），产出 SkillMetadata。Skill 工具（core/src/tool/handlers/skill.ts:18-71）按名加载正文并注入会话；metadata 清单则经 context builder 以 `skills_listing` meta_user system-reminder 注入（core/src/context/builder.ts:180-189,285-293），带 20k 字符预算。自定义斜杠命令是另一套并行体系：`.zcode|\.agents/commands` 下 markdown 文件→`/name` 展开 prompt（bootstrap/src/custom-command-prompt.ts），内置保留名门禁拦截冲突。

## 关键流程

① **扫描**：roots 按 priority 升序遍历；去重键是 SKILL.md 绝对路径而非 name——同名技能（不同生态/版本）共存（index.ts:80-84），最终按 name 排序输出（index.ts:89）。frontmatter 必须有 name（无 frontmatter 时回退目录名，index.ts:183-184）与 description（≤1024，index.ts:20,206-215）；仅含 SAFE 键（name/description/when_to_use/license/metadata）才 safeToAutoLoad（index.ts:21-27,231）。

② **Skill 工具**：入参 skill（裸名或 `plugin:skill`）+args；loadSkill 先全量 discover 再 find（index.ts:101-111），未命中抛 `Skill not found`；正文剥 frontmatter、100KB 截断（skill.ts:16,113-118），包成 `<skill_content name>` 并附 baseDirectory 说明、替换 `${CLAUDE_SKILL_DIR}|${ZCODE_SKILL_DIR}`（skill.ts:58-75）。工具 readOnly、免审批、permission "skill"。

③ **自定义命令**：根为 `~/.zcode|~/.agents/commands` + project 链同构目录（adapters/src/commands/roots.ts:101-104）；文件名（去 .md、支持 `ns:name`）即命令名，需匹配 `^[a-z0-9][a-z0-9_:-]{0,63}$`（commands/index.ts:22）；frontmatter 六键 allowed-tools/argument-hint/description/disable-noninteractive/model/skills（index.ts:26-33）；展开时 `$ARGUMENTS` 整体替换（contracts/src/commands/index.ts:114,145），有 executionPort 时再插入 `!` shell 展开（custom-command-prompt.ts:51-78）。同名先到先赢+`custom_command_duplicate_name` 警告（index.ts:71-81）。

④ **保留名门**：清单① builtin help entries 的名+别名（packages/shared/src/zcode-slash-command-help.ts）、清单② EXTRA_RESERVED=`compress`/`plan`（bootstrap/src/slash-command-surface.ts:19-26），归一化为去斜杠+小写后判定（slash-command-surface.ts:28-33）；清单③是会话级 disabledCommandNames（灰度关 `workflow`，custom-command-prompt.ts:16-24,42-44）。命中后：prompt 展开直接返回 undefined（原文交模型，custom-command-prompt.ts:36-38）、App 目录剔除（bootstrap/src/zcode-protocol/slash-commands.ts:44）——即内置命令恒赢。门禁在 load 之前，被拦命令连文件都不读。

## 接口与参数要点

- Skill 入参：`skill`、`args`；resultBudget 100KB truncate head（skill.ts:125-133）。
- frontmatter 全表：name、description（≤1024）、when_to_use、license、metadata；插件技能另得 pluginName/qualifiedName=`plugin:skill`（index.ts:250-263）。
- 根优先级表：extraRoots(10)→user .zcode(20)/.agents(30)→project 链每层 +10（.zcode 先、.agents 后，cwd→worktree 根递增）→plugin 根殿后（roots.ts:34-56）。数值小者先扫描；同名时先扫者居前，loadSkill 的 find 取首个——**user 会压过 project**，方向与常见「项目覆盖用户」相反。
- metadataBudget 默认 20_000 字符（core/src/context/sections/skills.ts:9,23），超限整体降级为 `- name (also loadable as x) (file: path)` 仅名列（skills.ts:50-57）；描述行截 250 字符（skills.ts:10）。注入以 guidanceToolNames 含 Skill 工具为前提，无工具的子代理不注入（builder.ts:177-189）。

## 边界与坑

- plugin 扫描三粒度拒 symlink 是唯一防线（放弃 realpath containment，scan.ts:16-23）；EACCES 上抛由 adapter 转 `skill_scan_failed` 警告诊断（index.ts:144-156），外层 catch 不得吞一切否则诊断成死代码。
- main agent 同名技能无 ambiguous 检查、静默取排序首个，仅子代理路径抛 "Skill name is ambiguous…use the fully qualified name"（core/src/runtime/methods/subagent.ts:811-816）。
- 命令名归一化去斜杠小写三处各自实现（custom-commands.ts:100-102 等）。
- 禁用技能路径同时比对 resolved 与 realpath 防 symlink 绕过（index.ts:239-248）。
- App `/` 面板排序唯一收口在 pinWorkflowAfterGoal，UI 不维护排序白名单（slash-commands.ts:60-76）；TUI 补全过滤要求草稿以 `/` 开头且无空白、按名/别名子串匹配（tui/src/app-input.ts:31-42）。

## 对 duo 的启示

duo 现状=M7 SkillRegistry 启动扫描+catalogFragment 全量注入、M19 命令注册表+保留名。对照三条：

1. **metadata 预算制**——duo 全量注入 description 无上限，可借 ZCode「20k 超限降级仅名单」的兜底形态。
2. **多根与同名语义**——ZCode 双生态目录合并、path 去重但同名先扫者赢（user 压 project），duo 若引入多根需显式选定覆盖方向并注释，避免 ZCode 这一反直觉默认。
3. **保留名单一事实源+两级门禁**——ZCode 保留名派生自 builtin help 条目（含别名），并区分全局保留与会话级按名门禁且前置到 load 之前，duo 的 M19 可对照收敛；自定义命令的 `$ARGUMENTS`/argument-hint/disable-noninteractive 字段形态亦值得 duo 命令文件化时参照。

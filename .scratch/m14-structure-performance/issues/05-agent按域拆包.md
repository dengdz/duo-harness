# 05: agent 按域拆包

## What to build

框架维护者获得职责单一、类数达标的 agent 模块（spec 分支 A，Q2 裁定方案一）：四个域入子包——governance（3 类）/ skills（4 类）/ plan（2 类）/ prompt（6 类）；根包保留循环核心与装配辅助 6 类（工具循环门面）+ 既有 internal/ presenter/。纯机械搬移：只动 package 声明与 import，零逻辑改动；波及面 web/cli 两模块（example 对 agent 零 import，已实查）。拆包后根包 8 类、各子包 2~6 类，全部达 duo-project-structure 阈值。

## Blocked by

None（可立即开工；实施顺序建议放 04 后——先小后大）

## Status

done（2026-09-16 用户验收通过——agent 全量 + 下游装配链 + 全量绿；yml 类名迁移验证）

## Checklist
- [x] 四子包搬移 + 根包保留（归属清单见 spec Implementation Decisions 分支 A）
- [x] web/cli 两模块 import 更新（无深引 internal）
- [x] 全量测试绿（搬移随类带测试改 import）+ diff 审查确认零非 import 逻辑变更
- [x] duo-project-structure 审查通过（根包与各子包类数达标）；拆包后模块图落验收件

## 拆包后模块图（验收件）

```
dev.duo.harness.agent（根包：循环契约与门面，6 类）
├── ChatAgent / AgentListener / AgentReply / ToolInvocation      ← 循环契约
├── AuditingAnswerer / SessionTitles                             ← 装配辅助
├── internal/     ToolCallingAgent, Messages                     ← 循环实现（不对外）
├── presenter/    PresenterAssembly                              ← 呈现装配（web/cli 共用）
├── governance/   ContextGovernance, ContextBudget, ContextOccupancy          （3 类）
├── skills/       Skill, SkillRegistry, SkillTool, SkillsPlugin               （4 类）
├── plan/         PlanMode, ExitPlanModeTool                                  （2 类）
└── prompt/       PromptFragment, PromptPlugin, PromptRegistry, PromptsView,
                  AgentsMd, AgentsMdPlugin                                     （6 类）
```
测试随主类同包搬移（仓库惯例）：governance 5 / skills 2 / plan 2 / prompt 1，根包留 AuditingAnswererTest、SessionTitlesTest。

## 实现注记（三处语义性最小变更，均为搬移的必要伴随）

1. **`SkillRegistry.findProjectRoot` 包私有 → public**：原同根包可达，拆包后 AgentsMd（prompt 域）跨包调用——项目根定位是技能发现与 AGENTS.md 注入的跨域共用原语。
2. **yml 插件类名同步**（4 个 yml/文档）：`agent-demo.yml`、`web-assembly-test.yml`、`cli-assembly-test.yml`、`插件配置参考.md` 里的插件类是字符串反射加载（`ClassNotFoundException` 由 Boot 点名暴露）——类名必须随包迁移。`.scratch/m12` 验收存档为历史记录不改。
3. **package-info 补齐**：根包描述更新为"契约与门面 + 域子包导航"，四个新子包按 internal/presenter 惯例补包描述。

## 验收指引（用户手跑）

```bash
# 1) agent 模块全量（14 测试类，含搬移后的新包位置）
mvn -pl duo-harness-agent -am test

# 2) 下游两模块装配链（yml 类名迁移验证）
mvn -pl duo-harness-web,duo-harness-cli,duo-harness-example -am test -Dtest='WebFaceTest,CliPluginTest,AgentReplMainTest' -Dsurefire.failIfNoSpecifiedTests=false

# 3) 全量
mvn test
```

预期：全绿。行为与 0.8.0 完全一致（spec 用户故事 14）——对外唯一可见变化是插件 yml 中 prompt/skills 域插件类的全限定名带新子包路径。

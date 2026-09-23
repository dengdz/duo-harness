# 09: /model 白名单切换与 resume 意图

## What to build

yml 增 `llm.models` 白名单清单（缺席/空 = 不可切，/model 提示配置方法）；`/model` 无参列出清单 + 当前模型、带参仅准切清单内（清单外直接拒切——模型名决定成本面，防拼错烧钱）；切换落会话事件（保存意图）、下一 turn 生效（重建 adapter 完成执行绑定）；首期限同 provider（baseUrl 不变，跨 provider 路由留 1.0 后菜单）；resume 时投影暴露意图模型，≠ 当前配置模型则横幅提示（含 /model 建议）、不自动切——防成本意外（休假期换便宜模型后被静默换回）。

决策依据：ADR-0026 决策六；探测 docs/research/ZCode/Agent循环与会话/投影、恢复与分页.md（保存意图 vs 执行绑定分离）；backlog「/model 运行时切换」销账对象。

## Blocked by

08（provider 字段与装配选型先行）

## Status
in-progress

## Checklist
- [x] `llm.models` 解析 + `/model` 命令（无参列出 / 带参切换 / 清单外拒切）
- [x] 切换会话事件 + 下一 turn 生效（adapter 重建）
- [x] resume 意图投影 + 横幅提示不自动切
- [x] 测试（先例 ChatAgent seam / CliPluginTest / BootTest）
- [ ] 工单级验收件：切模型下 turn 生效 + resume 提示演示，用户手动确认
- [x] CHANGELOG 记账（0.19.0 段）

## Comments
- 2026-09-23：实现与三轴审查完成（报告见下；四轴制的 Java 规范轴自本单首跑），全量 BUILD SUCCESS（872 用例 0 失败，2 既有 skip）。**待用户手动验收后转 done。**
- **前缀粒度同源记档**：/model 切换的执行绑定经 `SwappableLlmAdapter.swap(PresenterAssembly.llmAdapter(next))`——装配知识单点（llmAdapter 工厂），provider/后续装饰变更自动跟随；`/new` 换绑重建的 agent 捕获同一 Swappable 实例，swap 跨换绑存活。
- **已知边界记档**：① /new 建新会话不落 model/intent——该会话 resume 时无横幅，即便实际运行在非默认模型上（spec 未要求，记账待议）；② Web 面不含 /model 命令（CLI 面），WebPlugin 的 llm 链未包 Swappable——后续 Web 复用 /model 时包一层即可，不被阻断；③ /model busySafe=true，swap 与 activeConfig 前进两步非原子——观察窗仅限 CLI 主线程自身（行级轴已核无害，注释固化前提）。
- 模块/文档同 diff：插件配置参考「llm models 白名单」节、会话事件类型表 model/intent 行、CHANGELOG 0.19.0 段记账。

## 审查报告（第 1 轮·四轴）：工单 09 全 diff（基点 6405ee5 工作树，9 改 + 2 新增）

**覆盖**：11 文件 = 已审 11 + 跳过 0（覆盖率 100%）；行级轴名单 4 文件，测试/文档由 Standards/Spec 轴覆盖

### 阻断
- 无（Standards 轴红线 3/6 均核对通过——CHANGELOG/文档/事件表已同 diff；Spec 轴确认主链路与工单/ADR 全对齐）

### 建议
- Spec 轴：swap 与 activeConfig 前进在 append 之前——append 抛错时绑定已换而意图未落盘（已修：调序为先 append 意图事件再 swap 换链，持久化优先）
- 行级轴：target == 当前模型仍 swap+落盘冗余事件（已修：短路返回「已是当前模型」）
- 行级轴 low ×3：withModel 不 strip（已修 strip 归一）；config model 主字段不 strip 与白名单 strip 不对称致横幅误报（已修 load 处 strip）；resume 横幅建议未验白名单（已修：白名单外意图降级提示「需先在 config.yml 声明」）
- Standards 轴：LlmConfig 11 组件（兼容构造缓解，再增应上 builder——记档）；CliPlugin 静态/实例状态混写的 M21 遗留字段（visionEnabled 等 4 个，记档随 M24 后续收敛）；/model handler 33 行（记档——与 /permission handler 同粒度）
- 行级轴 low：delegate() 观测方法无调用方——已修：删除
- AnthropicMessagesAdapterTest FQN List.of（行级 Standards 前同款风格点）——已修

### 测试覆盖
- 新增 SwappableLlmAdapterTest 3 用例（初始委托/swap 换链/null 拒绝/stream 透传）；LlmConfigTest +2（models 白名单解析三面、withModel 全字段保留）；SessionTest +1（modelIntent 投影 latest-wins 与重放）
- 缺口：/model 命令 handler 的 CliPlugin 级用例（命令夹具重）——由工单级验收件手动覆盖

## Standards 轴原样分列

- 硬违规：无（初报红线 3/6 两项系子代理读取了修复前的陈旧工作树——主审复核 git diff 确认 CHANGELOG/插件配置参考/事件表均已在本 diff 内，记误报；以 `git status --short` 实证为准）
- 基线：LlmConfig 11 组件（记档）、CliPlugin static/instance 混写（M21 遗留记档）、/model handler 长度（记档）、SwappableLlmAdapter 无坏味道、AnthropicMessagesAdapterTest FQN（已修）

## Spec 轴原样分列

- (a)：CLI 级测试缺席（记档——命令夹具重，验收件覆盖）、空清单分支不显示当前模型（已修：提示语并入当前模型与配置方法）、/new 无意图记账（记档待议）、Web 面未包 Swappable（工单字面内不缺，记耦合点）、验收件待手动
- (b) Scope creep：无（/permission 注释误删已被主审自查恢复）
- (c) 实现正确性：意图/绑定分离（顺序已按建议调正）、清单外拒切、resume 提示不自动切、/new 无意图无提示、白名单双闸（启动 parse + 运行时 /model）——全部对照通过

## 行级规则轴原样分列

- 存留 7 条均 low（并发窗口无害并已注释固化前提、target 等于当前已短路、strip 冗余防御性、withModel strip 已修、config model strip 已修、空数组措辞已并入配置方法提示、delegate() 已删）
- 已过滤误报：volatile 读写面匹配、Session 投影无 CME、context.session() 空防御与既有约定一致、modelAllowed 大小写敏感符合模型名惯例

## 审查报告（第 2 轮）：修复复核

第 1 轮修复均为小改（调序、短路、strip、删观测方法、FQN），由新增与既有测试锁定（872 用例 BUILD SUCCESS）——按 SKILL 第 4 步未触发四轴复跑。

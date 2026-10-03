# 01: 运行期行级控制——内核口一（按 id 装载/拔除）

## What to build

yml 树激活后，上层编排能按行 id 在运行期拔除一个插件实例（其服务、工具、监听随作用域摘除，依赖它的插件自动回落 PENDING 等待），也能按 id 重新装载（服务回归、依赖方自动重载）——复用六态状态机与依赖指纹语义，不加新状态。Boot 装载产物按 id 登记可寻（现状句柄被装载器私有持有）；重复 id 装载点名报错（对齐服务同名互斥口径）。这是插件中心编排（05）与一切运行期管理能力的容器地基。

验收标准：S1 Boot 缝测试锁住"拔除→依赖方回落→重装→自动重载"全链路；root 上下文之外的插件上下文用例覆盖（声明闸门纪律）。

## Status

in-progress（实现与回归锁已完工待提交；提交经用户确认后随 09 单端到端验收转 done）

## Checklist

- [ ] 内核受控 API：按 id 列举（id + 六态）、按 id 拔除、按 id 装载（编程挂载语义复用既有 ctx.plugin 契约）
- [ ] Boot 装载产物登记：yml 行 id → 运行期实例可寻
- [ ] 重复 id 装载点名报错（消息含先注册方 id）
- [ ] S1 测试：提供方拔除 → 依赖方 UNLOADING→PENDING；重装 → 自动重载（先例 BootYmlTest 形态；插件异常断言沿 cause 链——M27 经验）
- [ ] 并发口径记档：与依赖指纹 recheck 的交互单线程语义测试为主，深度竞态沿用已知限制 #2 口径
- [x] CHANGELOG 记账（内核新公开 API 属用户可见变更，同 diff）

## Comments

- **实现形态（2026-10-03）**：`PluginRows` 接口 + `RowSnapshot`（api 包）→ `PluginRowsImpl`（internal，根作用域门面）→ `RowRegistry`（行 id → 句柄，LinkedHashMap 保装载序 + synchronizedMap，仅根持有）；`ContextImpl` 增 `isRoot()` / `rows()` / `registerRow()`；BootLoader 装载即登记 + boot 成功后自动发布 `pluginRows` 服务。六态状态机零改动（只开门不加机制）。
- **apply 失败的登记语义（超出工单字面的落钉）**：装载两路径分叉——绑定失败同步抛 PluginConfigException、无登记残留；apply 失败不抛（内核既有契约：错误统一经 handle），行登记为 FAILED、可拔除后同 id 重试。测试两路径各锁（bindingFailure... / applyFailure...）。
- **并发口径**：行管理整段持锁（查重-登记原子性优先于并发吞吐）——操作者驱动的低频动作，串行化可接受；与依赖指纹 recheck 的深度竞态沿用已知限制 #2 口径，未新增多线程测试（工单原文口径）。
- **验证**：PluginRowsTest 9 用例全绿（S1 boot 缝；重试件用无服务发布的 ConsumerPlugin——GreeterPlugin 会撞树上同名服务互斥）；core 模块全量 104 例 0 失败 0 错误。
- **待办**：提交待用户确认（红线 1）。

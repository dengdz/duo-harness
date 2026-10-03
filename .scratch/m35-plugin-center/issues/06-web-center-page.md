# 06: Web 插件中心页——四区页面与操作面

## What to build

浏览器侧栏新增「插件中心」tab（无构建静态单页延伸，不引入构建链——M8 限制 #3 口径不破），四区走通全部操作，页面 API 全走 04 贡献口端点（05 服务供数）：

1. **已装清单**：六态 + 服务消费/提供面展示
2. **待装**：目录扫描清单 + 装前点名校卡（确认安装 / 取消零副作用）
3. **操作**：启停/卸载/换审批策略——破坏性动作二次确认；换策略 = 停现提供方行 + 启目标行；无状态插件配置编辑走"停→改→启"向导；不可拔插件展示"重启生效"标注
4. **状态行**：`plugin/status` 叙述

验收标准：S3 HTTP 缝端点用例全过；浏览器面四区操作实测走通（S4 批在 09 单统一执行，本单以真实页面首载验证为准——前端 IIFE 边界核对纪律）。

## Blocked by

04, 05

## Status

in-progress（实现与浏览器实测已完工待提交；提交后随 09 单端到端验收转 done）

## Checklist

- [x] 贡献口 API 端点：`PluginCenterWebBridge` 经 webRoutes 挂 `/plugins/center/**`——rows/scan/inspect/install/mount-classpath/disable/enable/uninstall/reconfigure 九端点；业务点名 400 + 消息 JSON 与路由 500 兜底分离（routeHandler 统一包裹）
- [x] 四区页面：已装与装配行（六态 + 操作按钮）/ 待装扫描（点名候选预填）/ 挂类路径插件表单 / 状态汇总行；抽屉形态复用子任务抽屉骨架（无构建单页延伸）
- [x] 装前点名校卡：安装按钮 → 候选唯一自动预填 → 行 id/config prompt 确认
- [x] 破坏性二次确认：卸载、挂类路径替换（confirm）
- [x] "停→改→启"向导：`reconfigure(id, config)` 服务端一步（拔除→改行→重建）；不可拔行"需重启生效"标注（无操作按钮）
- [x] 前端 IIFE 边界核对：新代码为文件尾独立顶层 IIFE，只依赖 $/showToast/errText 顶层符号（M29 纪律）；`node --check` + 真实页面首载双验
- [x] CHANGELOG 记账（用户可见：插件中心页，同 diff）

## Comments

- **桥接方向落钉（架构先例）**：桥 `PluginCenterWebBridge` 在 plugin-center 模块（pom 增 web 依赖）——功能域消费呈现域贡献口：插件中心 OWN 自己的 Web 集成面，这正是贡献口建出来的用途（旗舰示范）；CLI-only 部署 webRoutes 缺席零感知（optionalInject）。
- **浏览器实测实录（红线 5，装前/装后双截图）**：真实 fat-jar（example shade 补登 plugin-center 依赖时发现并修复——装配引用了行而 example 未依赖该模块）+ 临时 DUO_HOME + stats 样板包入目录。六行渲染、plugin-center 行"需重启生效"标注生效、安装后 7 行（tool-stats ACTIVE + 操作按钮）、待装区清空、`tool_stats` 工具实际入状态面（/api/status 实证）。
- **两处实测修正**：①Jackson 对 Path 的默认序列化是 file: URI 形态——scan 响应投影为纯字符串 + 桥端 pathFrom 宽容解析；②空 config JSON 须归一为 null（configType=null 插件"声明即须不提供"——空 map 也算提供了配置）。
- **原生 prompt 对话框在 IAB 自动化下未能捕获**（页面 prompt 流的自动化驱动留 09 人工验收）；安装动作经端点驱动验证 + 页面渲染态截图，功能等价（同一桥端点）。
- **验证**：PluginCenterWebBridgeTest 4 用例（自动挂接/装停端到端/业务点名 400/鉴权 403 覆盖）+ PluginCenterTest 6 用例回归 + 全模块链 mvn exit 0。
- **视觉取舍（2026-10-03 用户裁定）**：本单交付为"功能达标 + 复用既有抽屉骨架"的最快可用形态，视觉刻意从简；页面视觉债务（横幅形态/三列布局/抽屉精修）经用户选 B 裁定并入 M36 主题 token 期——已落 backlog「Web 呈现视觉债务」条目。

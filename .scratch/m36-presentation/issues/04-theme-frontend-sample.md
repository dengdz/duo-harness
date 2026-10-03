# 04: 前端主题机制 + 示例主题包样板

## What to build

用户在页面上换肤：状态面板新增主题选择器（插件中心入口同区），切换即时生效；所选主题经 localStorage 持久化（跨标签一致——鉴权令牌先例，非 tabId 的 sessionStorage 隔离语义）；启动拉聚合端点把所选主题值集套到根元素；主题插件停/卸 → 服务随作用域消失 → 自动回落内置暗色。示例主题包样板（内容如亮色值集，工单细节可调——顺带作「亮色留第三方」的官方示范）走通全流程：放进插件目录 → 页面装前点名 → 装 → 主题列表出现 → 切换生效 → 卸载回落，全程不重启。

验收标准：S4 浏览器实测——切换/刷新保持/卸载回落三态截图；示例包经目录扫描真装（非 classpath 捷径）；token 名零变更下页面无漏色。

## Blocked by

02（内置暗色是回落锚与参照系）, 03（呈现贡献口 + 聚合端点）

## Status

in-progress（实现与浏览器三态走查通过、待提交；插件包真装腿随 08 收口验收件走用户手工腿）

## Checklist

- [x] 状态面板主题选择器 + 切换即时生效
- [x] localStorage 持久化 + 停/卸自动回落内置暗色（5s 节拍跟随状态轮询）
- [x] 值集套根元素（消费聚合端点；先清旧集防混主题）
- [x] 示例主题包样板（新模块 duo-harness-theme-light，常规插件包交货形态）
- [x] S4 浏览器实测（三态：切亮色/刷新保持/卸载回落，截图归档 style-proof/）
- [x] CHANGELOG 记账（用户可见：页面可换肤，同 diff）

## Comments

- **前端机制形态（2026-10-03）**：顶层 §5 IIFE（零 render/sse 依赖，M29 边界纪律）——fetch 全局包装自动携 token（/api/presentation 过栅栏）；选项列表按签名变化重建（防 5s 节拍重建打断下拉）；套值先清旧集（appliedTokenNames 记账，防残留混主题）；存储异常降级内存态（duoToken 先例）。
- **样板模块**：`duo-harness-theme-light`——M36 前内置亮色值集 + 新 token 亮色对应值（几何/字体随缺省不重复声明，值全过白名单）；依赖 core + web（PresentationRegistry 接口在 web 域——plugin-center 依赖 web 同款先例）；classpath 行与插件包双形态（stats 先例）。example pom 补接入行（shade 全量打进 fat jar——首建漏接入行 ClassNotFound 实测逮出）。
- **S4 三态实测**（隔离实例 + IAB 真实首载）：①切亮色 body 精确命中值集 rgb(238,240,243)；②刷新保持（localStorage 恢复 + 选择器回填 + 值集重套）；③卸载（移除行重启模拟）后 5s 内回落暗色 rgb(11,15,22) + localStorage 清空 + 根元素内联 token 清零 + 选择器复位。截图归档 style-proof/04、05。
- **Java 测试**：LightThemePluginTest 4 用例（optionalInject 声明闸门/注册内容覆盖面/移除再装/同 id 冲突点名——claimInto 静态入口直供 registry 免 Context 桩）；全量回归 15 模块 1159 例绿。
- **插件包真装腿**（目录扫描 → 点名 → 装 → 卸载）：dialog 自动化复杂度高，随 08 收口验收件走用户手工腿（端到端叙事主腿）。

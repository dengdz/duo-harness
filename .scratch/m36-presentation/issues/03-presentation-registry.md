# 03: 呈现贡献口 presentationRegistry + 内核聚合端点

## What to build

插件可经呈现贡献口注册两类声明：主题（主题名 → token 值集）与展示卡（工具名 → 卡片声明）。WebPlugin 发布 camelCase 服务 `presentationRegistry`（SERVICE_NAME 与视图接口方法名逐字一致）；申请制 + 同名冲突点名拒绝（对齐 `webRoutes` claim 哲学）；注册器挂提供方作用域、拔除即注销。内核聚合端点（/api 内核面）下发主题清单与值集、卡片声明聚合表——插件数据一律经内核序列化下发，无直达前端注入通道；token 值经颜色/长度/字号字面量白名单校验（函数值如 url 拒收——堵 ADR-0038「改值不改构」的注入缝）；鉴权栅栏全覆盖 + 异常 500 兜底铁律照旧。

**头号风险实现首日先验**：依赖 `presentationRegistry` 的行与 WebPlugin 行的装载序 + Boot 启动审计 PENDING 口径（pluginRows 先例：M35 工单 05），留回归锁。

验收标准：S1 插件装配缝（声明闸门 + 视图接口逐字一致）+ S2 HTTP 缝（聚合端点可达、鉴权负路径、冲突 400 点名、500 兜底）全过；时序验证留回归。

## Blocked by

None (can start immediately)

## Status

in-progress（实现与回归锁已完工待提交；提交后随 08 单端到端验收转 done）

## Checklist

- [x] `presentationRegistry` 服务发布：主题 claim + 卡片声明注册、冲突点名拒绝、作用域摘除即注销
- [x] 内核聚合端点：主题清单/值集/卡片声明表下发（GET /api/presentation）
- [x] token 值格式白名单校验（url() 函数与字符集外字符拒收）
- [x] Boot 审计时序验证（首日先验）+ 回归锁
- [x] S1/S2 测试：声明闸门 + HTTP 缝全路径
- [x] CHANGELOG 记账（用户可见：插件可贡献主题与展示卡声明，同 diff）

## Comments

- **实现形态（2026-10-03）**：`PresentationRegistry` 接口（嵌套 ThemeDeclaration/CardDeclaration record + PresentationView 消费视图）+ `PresentationContributions` 实现（synchronized 双表、注册时点名校验、快照防御性拷贝、移除器幂等可再申请）；WebFace 构造持有 + WebPlugin 发布；WebEndpoints 挂 `GET /api/presentation`（经既有 route 统一栅栏 + 500 兜底）。
- **头号风险实测排除**：Boot 收尾审计判 PENDING 失败（BootLoader:341 在案）——但 PluginInstance 可选依赖语义书明「缺失不阻塞 PENDING、**在场即参与指纹**（升级↔降级重载与硬依赖同一套机制）」：主题行 optionalInject + hasService 守卫（M35 mountIfWebPresent 先例）先于 web 行装载 → 哑激活 → web 行发布服务 → 指纹变化自动重载完成注册。回归锁 = `themeRowBeforeWebSelfHealsViaDependencyFingerprint`（夹具 presentation-theme-first.yml 主题行置首）。
- **实测逮出两处**：Void configType 行须显式 `config: {}`（M35-09 同款坑重现）；快照防御性拷贝（外层 List.copyOf 不拷内层 Map——`snapshotsAreCopiesNotLiveViews` 逮出，claim 时 Map.copyOf 收口）。
- **S2 口径说明**：冲突点名发生在注册时（plugin apply 期 PluginException → 装配审计点名），非 HTTP 400——单测 PresentationContributionsTest 锁冲突/白名单形态；HTTP 缝锁栅栏负路径（无/错 token 403、auth: none 一致 200）+ 快照 JSON 形态。
- **验证**：三目标套件绿（WebPluginAssembly 9 含自愈用例 / Endpoint 3 / Contributions 6，新 surefire 实证）；全量回归随提交前核对执行。

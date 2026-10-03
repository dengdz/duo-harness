# 03: 呈现贡献口 presentationRegistry + 内核聚合端点

## What to build

插件可经呈现贡献口注册两类声明：主题（主题名 → token 值集）与展示卡（工具名 → 卡片声明）。WebPlugin 发布 camelCase 服务 `presentationRegistry`（SERVICE_NAME 与视图接口方法名逐字一致）；申请制 + 同名冲突点名拒绝（对齐 webRoutes claim 哲学）；注册器挂提供方作用域、拔除即注销。内核聚合端点（/api 内核面）下发主题清单与值集、卡片声明聚合表——插件数据一律经内核序列化下发，无直达前端注入通道；token 值经颜色/长度/字号字面量白名单校验（函数值如 url 拒收——堵 ADR-0038「改值不改构」的注入缝）；鉴权栅栏全覆盖 + 异常 500 兜底铁律照旧。

**头号风险实现首日先验**：依赖 `presentationRegistry` 的行与 WebPlugin 行的装载序 + Boot 启动审计 PENDING 口径（pluginRows 先例：M35 工单 05），留回归锁。

验收标准：S1 插件装配缝（声明闸门 + 视图接口逐字一致）+ S2 HTTP 缝（聚合端点可达、鉴权负路径、冲突 400 点名、500 兜底）全过；时序验证留回归。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] `presentationRegistry` 服务发布：主题 claim + 卡片声明注册、冲突点名拒绝、作用域摘除即注销
- [ ] 内核聚合端点：主题清单/值集/卡片声明表下发
- [ ] token 值格式白名单校验（函数值拒收）
- [ ] Boot 审计时序验证（首日先验）+ 回归锁
- [ ] S1/S2 测试：声明闸门 + HTTP 缝全路径
- [ ] CHANGELOG 记账（用户可见：插件可贡献主题与展示卡声明，同 diff）

## Comments

- spec 锚点：ADR-0038 决策二/四；无 Web 部署下消费方 optionalInject 优雅缺席——声明必须显式（M34 三盲教训）。

# 01: 插件可选依赖 + CLI 纯对话装配

**What to build:** 部署者在 boot yml 里不装 fs 插件行，CLI 照常启动、能聊天——`/permission` 这类依赖 workspace 的命令降级为"未挂载"提示而非整树起不来（backlog 原始用例）。插件开发者获得 `optionalInject()` 声明："就绪则用、缺失不拦"，服务出现/消失自动重载、升级↔降级双向对称。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

语义权威：ADR-0019 决策 7/8/9；spec 见 `.scratch/m18-extension-mechanism/spec.md`（可选依赖小节）。

- [ ] `Plugin` 新 default 方法 `optionalInject(): Set<String>`（空集缺省，与 inject 对称）；`as()` 读取许可放宽为 `inject() ∪ optionalInject()`——未声明名字读取仍点名报错，实例仍只经声明名字取
- [ ] 声明可选依赖的服务缺失时插件照常 ACTIVE（不 PENDING）；`apply` 内以 `hasService()` 判存并按在场性接线（降级桩形态）
- [ ] epoch 语义：依赖指纹缺失项跳过（不置 null）、在场项照常参与；可选服务 provide/unprovide 经既有 recheck 自动重载，升级↔降级双向对称
- [ ] 不新增读取 API：`hasService()` 判存 + `as()` 惰性视图；DSH 式 `ctx.get(name)` 使用点探测不引入
- [ ] yml 路径 BootLoader 审计对仅可选缺失的插件不报"PENDING 等待"（缺失清单逻辑抽取，供 02 复用）
- [ ] 端到端验收：boot yml 去掉 fs 插件行 → CLI 照常聊天，`/permission` 提示"未挂载"（点名 workspace 服务）；装配级自动断言 + duo-acceptance 真机验收件
- [ ] 测试走 Boot 双接缝（LifecycleTest/BootTest 叙事：接缝 A = yml 全链路 / 接缝 B = 编程 API），只断言外部行为（插件状态、视图可用性、审计文案），不测内部实现

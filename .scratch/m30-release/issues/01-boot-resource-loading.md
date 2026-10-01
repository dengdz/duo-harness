# 01: Boot API 资源装载通道 + DuoMain/Headless 缺省分支切换

## What to build
Boot 装配装载支持资源流形态，消除「`java -jar` 即跑」的必炸点（缺省 yml 经 `Path.of(getResource().toURI())` 文件化读取，jar 内资源 URI 为 `jar:` 形态必炸——HeadlessArgs 与 DuoMain 同型两处）：

1. **Boot API 新增资源流装载重载**：不动既有 `from(Path)` 两签名，底层委托同一装载器，语义与文件路径装载完全一致（行序预检、Boot 失败点名、插件树构建）。
2. **行序预检适配资源流**：`validatePresenterRowOrder` 的输入适配（InputStream 单次读取——预检与装载的读取顺序在此钉死并测试锁定：预检读全量字节做行序校验，通过后同源再开流/字节复用，实现择一）。
3. **两处缺省分支切换资源装载**：常驻入口（DuoMain 缺省 agent-demo.yml 资源）与 headless 入口（HeadlessArgs 缺省分支，HeadlessBoot 链路核实）。
4. **边界**：DemoMain / ContractGuardDemoMain / ApprovalDemoMain 三处演示入口传显式路径、非发布入口，本票不动；内核无装饰/包装实现，透传排查面 = 新旧重载委托一致性（duo-workflow 经验档「接口新增通道透传排查」口径）。

验收标准：资源装载与文件装载同语义的单测（成功形态 + 行序违例 fail-fast）；全量回归绿；真实 jar 形态的端到端验证归 02 票。

## Blocked by
无。研究底座已备（spec Implementation Decisions；ADR-0032 裁定 Boot API 资源化、拒绝临时文件桥接）。

## Status
ready-for-agent

## Checklist
- [ ] Boot API 新增资源流装载重载（不动既有签名，委托同一装载器）
- [ ] validatePresenterRowOrder 适配资源流输入，预检/装载读取顺序钉死并入测
- [ ] DuoMain 常驻缺省分支切换资源装载
- [ ] HeadlessArgs 缺省分支切换资源装载，HeadlessBoot 链路核实
- [ ] 新旧重载委托一致性排查（BootLoader 单装载器口径）
- [ ] 单测：资源装载 = 文件装载同语义（成功形态 + 行序违例 fail-fast）
- [ ] 全量 ./mvnw verify 绿

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
done（2026-10-01 用户验收通过——A 方案：缺省启动冒烟无回归；jar 形态端到端验证归 02 票验收件）

## Checklist
- [x] Boot API 新增资源流装载重载（不动既有签名，委托同一装载器）
- [x] validatePresenterRowOrder 适配资源流输入，预检/装载读取顺序钉死并入测
- [x] DuoMain 常驻缺省分支切换资源装载
- [x] HeadlessArgs 缺省分支切换资源装载，HeadlessBoot 链路核实
- [x] 新旧重载委托一致性排查（BootLoader 单装载器口径）
- [x] 单测：资源装载 = 文件装载同语义（成功形态 + 行序违例 fail-fast）
- [x] 全量 ./mvnw verify 绿

## Comments

### 实现记录（2026-10-01）

- Boot 契约层新增 `fromResource(String)` / `fromResource(String, Consumer)`；BootLoader 增资源读取 + 文本装载私有入口，`from(Path)` 与 `fromResource` 收敛同一装载核（单装载器口径：grep 证实 BootLoader 直接调用方仅 Boot 契约层，无装饰/包装实现）
- 顺序钉死形态：**预检先行、通过后各自开流装载**（classpath 资源可重复打开，无共享流断流问题）；资源通道 fail-fast 用例 + 同语义对拍用例锁定
- DuoMain 缺省分支：预检资源变体（缺失抛 IOException → 既有预检 catch 网接住，与文件分支同走「消息 + exit 2」）+ `Boot.fromResource` 装载；HeadlessArgs 增 `useDefaultYml`/`defaultYmlText`（流读取），`effectiveYml`/`resourceYml`（文件化病灶）删除；HeadlessBoot 增 `runDefault`，`filteredCopy` 增文本形态重载（临时副本为既有预过滤机制、显式路径分支同构）
- 透传排查：主源码 `Boot.from` 消费点 5 处——DuoMain（双分支已切）、HeadlessBoot（过滤副本路径不变）、三处演示入口（显式路径，工单边界不动）；**AgentReplMain 为工单正文未点名的第 5 处文件化读取残留**（兼容壳、非发布链路），已记 backlog
- 测试：新增 12 用例（BootResourceTest 4 / 行序资源变体 2 / HeadlessDefaultYmlTest 4，行序契约类合计 4）；全量 verify 两轮全绿（实现后 + 审查修复后终态）

### 审查轮（2026-10-01·四轴）

**覆盖**：12 个文件（6 修改 + 6 新增）= 已审 12 + 跳过 0；行级轴主代码 5 文件 + 4 资源 100% 覆盖，测试类由 Standards / Spec / Java 规范轴覆盖

**阻断**：无。（行级轴 F1「测试资源内容互换」判 critical，经主审亲验证伪——直接读盘证实 ok.yml/swapped.yml 内容与文件名语义一致、同测试类全量 verify 4/4 绿在案；代理把两文件内容看反）

**建议（已修复 9 项）**：
- CHANGELOG 0.26.0（未发布）补记账（Standards P1，红线 6；含 headless 临时副本机制口径）
- BootLoader 文本入口 public 零外部消费方 → private（Standards P2）
- DuoMain 资源缺失 IllegalStateException 逃出预检 catch 网 → 改 IOException，exit 2 契约两分支对齐、签名如实（行级 medium + low / Standards P2）
- READ_CONFIG 两处错误消息补 `classpath:` 标签，三阶段一致（行级 low / Spec 发现 1）
- Boot javadoc「经本类类加载器」→「经实现类（BootLoader）的类加载器」（行级 low）
- DuoMain byte[] 中转重载单调用方 → 内联（Standards P3）
- 「收口」用词漂移 → 「共用同一装载器、语义对齐」（Standards P3，术语表约束）
- BootResourceTest 死断言移除 + 同语义用例改「同一资源文本双通道对拍」（Standards P3）
- runDefault javadoc 补 @return、filteredCopy(String) throws 收窄 IOException、注释「catch 网络」笔误（Java 规范轴 3 条建议）

**记档（不修，附理由）**：
- headless 缺省失败 source 指向临时副本路径（行级 F5 / Spec #3）：预过滤临时副本为既有机制（显式路径分支同构落盘），非本 diff 引入；已在 CHANGELOG 明示口径，Boot 文本入口后续演进再统一
- 三处 classpath 读文本形态相近（Standards P3）：异常语义各异（BootException / IOException / IllegalStateException 各服务所在通道契约），合并属跨模块设计变更，不入本票
- 预检先行顺序的 run() 级组合测试（Spec #2）：通道级锁定已备（违例 fail-fast + 同语义对拍），顺序由 run() 单向结构承载，端到端归 02 票 java -jar 实跑
- BootResourceTest 资源流未判 null（Java 规范轴 P3）：测试自备资源，NPE 即测试自身失败信号
- ADR-0032 拒绝项「临时文件桥接」与 headless 预过滤落盘的形似（Spec #3）：可辩护范围内实现（资源读取已走正门通道），口径已记 CHANGELOG

**测试覆盖**：新增分支/边界均有用例（资源缺失、坏结构、行序违例、缺省装配预检、过滤一致性）；同语义承诺以「同一资源文本双通道对拍」直接锁定；全量 verify 终态 BUILD SUCCESS。

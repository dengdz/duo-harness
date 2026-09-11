# M1 验收对照表

> duo-acceptance 里程碑级验收件。含**预期日志原文**（实测快照）——跑完命令逐行对照，不用凭描述猜。全部通过后 7 张工单的 done 才完整闭环。

## 运行命令

测试路径（79 用例）：

```bash
mvn clean test
```

演示路径（Demo 全链路）：

```bash
mvn -pl duo-harness-example -am package exec:java
```

IDEA 等价路径（输出与上述一致，在 Run 工具窗核对）：

- **测试**：测试类（或包/模块）右键 → Run Tests
- **Demo**：打开 `DemoMain` → main 方法旁绿色箭头 → Run
- **玩法循环**：改 `demo.yml` 后直接 rerun（自动重编译资源）

## 预期日志 A：测试路径（mvn clean test）

输出中应依次出现 7 条套件叙述（顺序随执行而定，内容与数字逐条核对）：

```
=== 套件：MinimalPluginLoopTest —— 插件闭环：apply 运行、config 严格绑定（缺字段/类型错点名）、effect 逆序回滚、销毁后拒绝注册（12 用例） ===
=== 套件：EventsTest —— 事件五模式：emit 异常隔离、waterfall 否决/参数改写/返回包装、serial·bail 顺序投票、parallel 并发聚合、监听器随作用域摘除（18 用例） ===
=== 套件：ServicesTest —— 服务注入：provide/Service 基类发布、as 视图寻址（方法名即服务名+类型校验）、inject 声明许可、依赖缺失挂起、注销级联停依赖方（14 用例） ===
=== 套件：LifecycleTest —— 六态与 epoch：状态迁移事件广播、依赖消失回 PENDING、服务回归/换实现自动重启、失败隔离（FAILED 兄弟无感）、DISPOSED 终态不复活（10 用例） ===
=== 套件：BootTest —— 配置驱动 boot：行加载（行序无关）、审计点名（缺失服务/坏配置定位/重复 id/未知类）、disabled 行跳过、失败整树回滚（14 用例） ===
=== 套件：ToolsServiceTest —— 工具域三段管线：pre 准入否决（否决后本体不执行）、execute around 包装、post 结果改写与转错误、工具异常收敛 error 结果、插件停止自动注销工具（10 用例） ===
=== 套件：DemoMainTest —— Demo 冒烟：一条命令跑通，输出叙述覆盖 boot/服务注入/三段管线/级联停止/整树回滚全部机制（1 用例） ===
```

末尾汇总（各套件数字与上述一致，合计 79，0 失败 0 错误）：

```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0  （各套件逐条）
...
```

测试固有的 warn 日志（如"事件 tick 的监听器…抛错（已隔离）"）是 EventsTest 的隔离用例故意触发，属预期现象，不是缺陷。

## 预期日志 B：演示路径（Demo，实测快照）

> 下文为输出原文；`# ←` 注释行是验收导航（实际输出无此行）。段前 `§` 标注对应工单。

§ 工单 05 boot 开始（读配置、行序无关的装载）：

```
=== duo-harness M1 Demo：插件化全链路 ===
[boot] 读取 demo.yml
  [状态] ToolsPlugin: PENDING -> LOADING
  [状态] ToolsPlugin: LOADING -> ACTIVE
  [状态] ToolGuardPlugin: PENDING -> LOADING
  [状态] ToolGuardPlugin: LOADING -> ACTIVE
  [状态] EchoToolPlugin: PENDING -> LOADING
  [状态] EchoToolPlugin: LOADING -> ACTIVE
  [状态] GreetingPlugin: PENDING -> LOADING
```

§ 工单 03 服务注入（行序无关 + 视图寻址——消费者行在 yml 里排在提供者前，却等提供者 ACTIVE 后才启动）：

```
  [状态] GreetingClientPlugin: PENDING -> LOADING
  [demo] 消费者经视图获得: 你好, 世界!
  [状态] GreetingClientPlugin: LOADING -> ACTIVE
  [状态] GreetingPlugin: LOADING -> ACTIVE
[boot] 插件树激活完成（demo-disabled 行在场而实例未装载）   # ← 工单 05：全程无 DemoDisabledPlugin 任何状态行
```

§ 工单 06 工具三段管线（正常 → pre 否决 → post 治理）：

```
[工具] 正常执行:
  -> [正常] echo:世界 [已治理]
[工具] 准入否决（参数含敏感词）:
  -> [错误] 工具 "echo" 执行被拒绝: 参数含敏感词（治理插件否决）
[工具] post-execute 结果治理:
  -> [正常] echo:第二次调用 [已治理]
```

§ 工单 04 六态与 epoch（拔提供者 → 消费者 UNLOADING 回 PENDING，等待而非销毁）：

```
[级联] 运行时挂临时服务提供者与消费者，再拔掉提供者:
  [状态] DemoMain$TempProviderPlugin: PENDING -> LOADING
  [状态] DemoMain$TempProviderPlugin: LOADING -> ACTIVE
  [状态] DemoMain$TempConsumerPlugin: PENDING -> LOADING
  [demo] 临时消费者激活，读到服务: true
  [状态] DemoMain$TempConsumerPlugin: LOADING -> ACTIVE
  [状态] DemoMain$TempConsumerPlugin: ACTIVE -> UNLOADING
  [状态] DemoMain$TempConsumerPlugin: UNLOADING -> PENDING     # ← 回等待：服务回归会自动重启
  [状态] DemoMain$TempProviderPlugin: ACTIVE -> UNLOADING
  [状态] DemoMain$TempProviderPlugin: UNLOADING -> DISPOSED
[级联] 提供者已停——消费者经 UNLOADING 回到 PENDING（见上方状态叙述）
```

§ 工单 01 effect 逆序回滚（收尾整树逐一销毁——每个插件两行迁移，最终全部 DISPOSED）：

```
  [状态] DemoMain$TempConsumerPlugin: PENDING -> UNLOADING
  [状态] DemoMain$TempConsumerPlugin: UNLOADING -> DISPOSED
  [状态] GreetingClientPlugin: ACTIVE -> UNLOADING
  [状态] GreetingClientPlugin: UNLOADING -> PENDING
  [状态] GreetingPlugin: ACTIVE -> UNLOADING
  [状态] GreetingPlugin: UNLOADING -> DISPOSED
  [状态] GreetingClientPlugin: PENDING -> UNLOADING
  [状态] GreetingClientPlugin: UNLOADING -> DISPOSED
  [状态] EchoToolPlugin: ACTIVE -> UNLOADING
  [状态] EchoToolPlugin: UNLOADING -> DISPOSED
  [状态] ToolGuardPlugin: ACTIVE -> UNLOADING
  [状态] ToolGuardPlugin: UNLOADING -> DISPOSED
  [状态] ToolsPlugin: ACTIVE -> UNLOADING
  [状态] ToolsPlugin: UNLOADING -> DISPOSED
=== Demo 结束（整树已回滚） ===
```

（GreetingClientPlugin 在收尾段先 UNLOADING→PENDING 再 PENDING→DISPOSED：因提供者先被销毁它回落等待，随后自身被销毁——两级级联的正确表现。）

## 手动玩法（工单 05 审计点名的实貌）

| 玩法 | 操作 | 预期输出（标志行） |
|---|---|---|
| ① disabled 翻转 | demo.yml 把 `demo-disabled` 的 `disabled: true` 改 `false` | 状态叙述新增 `DemoDisabledPlugin: PENDING -> LOADING` 与 `LOADING -> ACTIVE` |
| ② 删提供者行 | demo.yml 删掉 `greeting` 整行 | 启动失败：`[greeting-client] 永久等待中（state=PENDING），缺失服务: [greeting]`，整树回滚 |
| ③ 重复 id | 两行同 id | 解析失败：`配置行 id 重复: <该 id>` |

玩法后还原：`git checkout -- duo-harness-example/src/main/resources/demo.yml`

## 结果记录

- ✅ **演示路径通过**（IDEA 运行 DemoMain，JDK 23）：输出与预期日志 B 逐行完全一致，退出代码 0（2026-08-25）
- ✅ **测试路径 core 部分通过**（IDEA Run Tests，JDK 23）：5 条套件叙述与数字一致；EventsTest 的 warn 异常栈为隔离用例预期现象；退出代码 0（2026-08-25）
- ✅ **测试路径 tools 部分通过**（IDEA Run Tests，JDK 23）：ToolsServiceTest 叙述一致，10 用例，退出代码 0（2026-08-25）
- ✅ **测试路径 example 部分通过**（IDEA Run Tests，JDK 23）：DemoMainTest 叙述一致，1 用例，退出代码 0（2026-08-25）——**测试路径 79/79 全绿**
- ✅ **手动玩法 ①②③ 全部通过**（IDEA 改 demo.yml + rerun，JDK 23）：disabled 翻转后激活叙述出现；删提供者行后审计点名缺失服务 + 整树回滚叙述 + 退出 1；重复 id 解析期拒绝 + 退出 1（2026-08-25）
- 备注：玩法①时"行在场而实例未装载"的静态文案语义过时（Demo 文案不随运行时配置动态变化），已知小局限，不构成缺陷

## 结论

**M1 验收完整通过**：演示路径逐行一致、测试路径 79/79、三个手动玩法全部符合预期——七张工单的 done 闭环。

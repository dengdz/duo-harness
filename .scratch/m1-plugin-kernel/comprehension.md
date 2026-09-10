# M1 理解关卡

> 阶段范围：5838b7c..HEAD（89 文件，5895 行新增）。课单：6 课，已确认。

## 讲义

### 第 1 课：插件与作用域

- **解决什么问题**：可扩展功能单元的三件事契约化——资源谁管、配置怎么传、失败留多脏
- **核心概念**：插件（Plugin 接口：configType + apply）、effect（可逆副作用，逆序回放）、config record（强类型配置）
- **实现要点**：
  - 逆序回滚：`ContextImpl` 副作用栈（ArrayDeque），dispose 从栈顶弹出（后注册先回收）
  - 级联：`plugin()` 把子实例销毁注册为父作用域 effect——父停子必停
  - 严格绑定：`bindConfig` 启用 FAIL_ON_MISSING_CREATOR_PROPERTIES，缺字段加载即报错（不补 null）
- **ADR 取舍**：逆序回滚是 DSH 核心语义（热插拔地基，ADR-0001）；严格绑定是审查修正——Jackson 默认宽容会把坏配置的报错推迟到 apply 深处 NPE，错误前移到加载时刻（ADR-0003 精神）
- **易错点**（考核实录 0/4，补考重点）：
  1. 销毁顺序是**注册倒序**；apply 返回的 disposer 由内核最晚注册、最先执行——逆序的本质是依赖安全
  2. config 缺字段 = **加载时点名报错**（FAIL_ON_MISSING_CREATOR_PROPERTIES）——Jackson 默认世界（补 null/0）不是我们的世界
  3. 级联停止无中心化机制：子实例销毁是父作用域的**普通 effect**（一行注册），逆序回滚的自然推论
  4. 死作用域上注册 = **当场抛错拒绝**（fail-fast），不静默丢失——"错误前移"与第 2 条同源，是贯穿 M1 的第一设计原则

### 第 2 课：事件五模式（源码走读：EventsImpl.java）

- **解决什么问题**：插件间通信——广播、并发、投票、管线拦截，一个总线五种模式
- **走读要点**：
  - 两张表（listeners / waterfallListeners）：CoW 并发安全 + 形状分约定
  - emit：catch 后循环继续——广播隔离（兄弟无感、派发方无感、warn 可观察）；与 fail-fast 的分工：隔离给广播场景、fail-fast 给契约违规
  - **waterfall 洋葱（核心）**：`for (i = size-1; i >= 0; i--)` 逆序包装——先注册者最外层；不调 next = 内层全跳过（否决无特殊机制）；next(改写args) = 参数改写
  - bail 委托 serial：同步模型下二者必然等价，保留 DSH 词汇（ADR-0001）
  - parallel：每监听器一虚拟线程（名带序号可诊断），首错主异常+suppressed 聚合——全库唯一主动开线程处（ADR-0002 落地）
- **易错点**：（统一考试后补）

### 第 3 课：服务注入（源码走读：ServiceRegistry / ServiceKey / ContextImpl 视图代理）

- **解决什么问题**：插件间"提供能力 / 使用能力"的寻址——字符串身份 + 类型窗口
- **走读要点**：
  - **二阶键**（服务名, Scope）：isolate 预留的地基——预留成本为零，后补是地基级重构（ADR-0001 决策）
  - provide 用 putIfAbsent：原子先到先得，重名点名，无锁
  - remove(key, value) **值感知**双参：防旧注销器竞态误删新实例（工单 03 ocr 抓的真缺陷修复）
  - as() 只造代理不查表（Proxy + invokeViewMethod），**方法名即服务名**；查找惰性发生在调用时——服务换实现旧视图自动跟新
  - resolveService 三检查：**inject 许可**（声明先行——epoch 的依据）→ 命中 → 类型兼容；根作用域不受 inject 限制（装配者与 guest 的有意不对称）
- **易错点**：（统一考试后补）

### 第 4 课：六态与 epoch（源码走读：PluginInstance / PluginRegistry）

- **解决什么问题**：依赖变化的自动响应——免写启动顺序、拔服务优雅降级、换实现自动重启
- **走读要点**：
  - 六态两个终态（FAILED/DISPOSED）；**UNLOADING 回 PENDING 是灵魂**——卸载是可逆等待不是死亡
  - **epoch 指纹**：依赖集合的"类名@identity 排序拼串"（TreeSet 保序、双因子防碰撞、缺任一返回 null=未就绪）
  - **recheck 循环**：锁内决策（比对指纹）锁外干重活（apply/回滚）；ACTIVE 分支 `target.equals(epoch)` 一行字符串比较就是"换实现重启"的全部实现；`continue` 让一次 recheck 完成"卸载→重启"往返；LOADING 期间变化记 dirty 标记
  - **settleIfCurrent 条件迁移**：只从预期状态出发——防"dispose 抢先后 recheck 把 DISPOSED 复活成 PENDING"（工单 04 阿里审查抓的终态复活缺陷）
  - **传导链**：provide/remove → recheckDependentsOf → 逐实例 recheck——复查而非盲停，误杀化解为多余重启、最终一致（03 时代竞态的系统性解法）
- **易错点**：（统一考试后补）

### 第 5 课：配置驱动 boot（源码走读：BootLoader / Boot 薄壳）

- **解决什么问题**：yml 一行 = 一个插件，改配置不改代码变形态
- **走读要点**：
  - **薄壳分家**：api.boot.Boot 20 行零第三方 import（契约纯度），Jackson/IO 全在 internal.boot.BootLoader；prepare 钩子在树装载前回调（Demo 挂状态监听的入口）
  - parseRows 四道关卡（null 行/缺 id/**重复 id=HashSet add 返回 false**/缺 name）——结构错误装载前全拦、报错点名到行
  - activate 行装载：Class.forName 反射 + root.plugin() 交内核——**boot 无自己的生命周期机制**，行序无语义因为激活由 inject/epoch 决定
  - audit 三态点名：FAILED 经 awaitStartup 取原始错误；**PENDING 列"它在等谁"**（inject ∩ 未提供服务）；default 兜底
  - 失败整树回滚：rollbackQuietly 包 root.dispose（回滚错误不掩盖主错误）——要么全好、要么全干净退出，无半启动态
- **易错点**：（统一考试后补）

### 第 6 课：工具域三段管线（源码走读：ToolsServiceImpl / ToolsPlugin）

- **解决什么问题**：工具注册与执行治理——治理逻辑不侵入工具本体
- **走读要点**：
  - 装配链三行配置三种形态：tools 域插件（发布服务）/ 工具插件（inject 注册）/ 治理插件（事件挂监听）——三方互不 import，唯一约定是事件名常量
  - ToolsPlugin 一行 provide 发布整个域；**不继承 Service 基类**（双重发布教训：同一发布动作只能一个主人）
  - register：**先挂注册方 effect 再入表**（防销毁竞态残留），重名补偿摘除；remove(name, def) 值感知（与第 3 课同手法各自独立发现）
  - execute 三段 = 三次 waterfall，共载 ToolExecution：pre 终端默认放行（`!TRUE.equals` fail-closed）；**工具本体是 execute 段终端**（M2 超时/重试挂外层）；异常 rootMessage 下钻转 error 结果（工具异常≠系统异常）；post 可改写可转错误
- **六课收束一句话**：所有能力都是插件，所有协作走 Context（服务/事件/effect），所有生命周期由依赖指纹驱动
- **易错点**：（统一考试后补）

## 错题本

0. **补考·急切解析的静默脱节**（第 3 课）：急切绑定旧实例后服务换新，旧代理调 .tools() 会？
   - 当时：错选 C（抛异常）
   - 正解：B——返回旧实现且完全正常工作。注销只是移除注册表引用，对象被代理引用着还活着（GC 不回收）——静默脱节：两边看都正常、中间断了线，无任何报错。这正是 M1 处处防的"静默"族缺陷（同族：死作用域静默注册、旧注销器误删新实例）；惰性解析（每次调用重查表）天然免疫
0. **补考·双重注册**（第 3 课）：ToolsServiceImpl 曾继承 Service 基类导致什么问题？
   - 当时：**答对 B**
   - 正解：B——基类构造器自动 provide + apply 手动 provide = 同一服务发布两次，第二次撞"已注册"失败；provide 占坑语义必须响亮失败不能静默吸收；修复=不继承基类、apply 手动发布（工单 06 测试抓的真实事故）
0. **补考·构造器逃逸**（第 3 课）：Service 子类字段为什么必须在声明处初始化？
   - 当时：**答对 B**
   - 正解：B——super 构造器里的 provide 同步唤醒依赖方并立即调用服务方法，此刻子类构造器体未执行（字段还是 null）；机制不改、契约文档化（Service JavaDoc 载明约束）

0. **补考·急切解析的静默脱节**（第 3 课）：急切绑定旧实例后服务换新，旧代理调 .tools() 会？
   - 当时：错选 C（抛异常）
   - 正解：B——返回旧实现且完全正常工作。注销只是移除注册表引用，对象被代理引用着还活着（GC 不回收）——静默脱节：两边看都正常、中间断了线，无任何报错。这正是 M1 处处防的"静默"族缺陷（同族：死作用域静默注册、旧注销器误删新实例）；惰性解析（每次调用重查表）天然免疫

0. **waterfall 不调 next 的后果**（第 2 课）：治理监听器 deny 后不调 next 直接返回？
   - 当时：答"不知道"
   - 正解：B——内层（后续监听器+终端默认行为）全部跳过，即否决。next 是通往内层的唯一门，否决不是特殊机制是"不开门"；Demo 的 [错误]被拒绝 输出与 preExecuteCanVetoWithoutRunningTool 用例双证
0. **emit 为什么不调瀑布监听器**（第 2 课）：同名事件混挂普通和瀑布监听器，emit 只消费普通方，原因？
   - 当时：答"不知道"
   - 正解：B——两张独立 Map（listeners / waterfallListeners），on 两个重载写不同表；emit 只查普通表、waterfall 只查瀑布表，分表=分调用约定（混表会 .on/.invoke 形状错配运行时炸）
0. **waterfall 执行顺序**（第 2 课）：监听器 A/B/C 按注册序，终端 T，执行顺序？
   - 当时：错选 A（T → C → B → A）
   - 正解：B（A → B → C → T）——**包是逆序的、执行是正序的**；逆序循环搭洋葱（后注册者卷在外层），执行从最外层点火往里钻——外层先注册者先执行；类比 Express/Koa 中间件
0. **parallel 为什么用 suppressed 聚合**（第 2 课）：多个监听器并发失败，为什么不只抛第一个？
   - 当时：**答对 B**
   - 正解：B——只抛第一个会让其他错误被静默吞掉；suppressed 把全部失败摆出来供诊断，不掩盖——与第 1 课 fail-fast 同源（"错误要被看见"的三种落地形态之一）
0. **remove 为什么值感知双参**（第 3 课）：ServiceRegistry.remove(name, instance) 用双参而非单参，为什么？
   - 当时：**答对 B**
   - 正解：B——旧注销器迟到时服务已被新实例接替，单参会误删新实例（静默破坏）；双参只在"表里还是我这个实例"时才删，旧注销器自然失败返回 false——工单 03 ocr 抓的高价值缺陷修复；与第 4 课 epoch 是同源问题的两层防御（remove 是即时竞态防御、epoch 是系统性兜底）
0. **inject 空集时偷读服务**（第 3 课）：插件 inject 改空集，apply 里仍调 ctx.as(...).tools()，会？
   - 当时：错选 A（正常工作）
   - 正解：B——resolveService 第一关就拦（injectedServices 不含 tools → 抛错）；inject 是双职责：启动时机 + 读取许可证，有意绑定——不声明就读=偷渡，当场拦下比运行深处 NPE 更早（错误前移）；根作用域不受限制（装配者 vs guest 的不对称）；inject 许可是第 4 课 epoch 指纹的输入前提
0. **as() 的惰性解析**（第 3 课）：ctx.as(ToolsView.class) 执行时（还没调 .tools()），发生什么？
   - 当时：答"不知道"
   - 正解：B——只造动态代理不查表（Proxy.newProxyInstance + invokeViewMethod）；查表推迟到 .tools() 调用那一刻——好处：服务换实现后旧代理自动指向新实例（每次调方法都重查表），不需要重新 as()；与 epoch 的"换实现自动重启"是同一需求的轻重两级方案
0. **epoch 换实例检测**（第 4 课）：同类型两个不同实例先后注册，依赖方的指纹变不变？
   - 当时：**答对 B**（猜对）
   - 正解：B——指纹 = 类名@identityHashCode 双因子；同类型不同实例的 identity 不同（JVM 规范保证）→ 指纹不等 → epoch 变化 → 自动重启；工单 04 最初单因子（阿里审查加类名双因子防碰撞）
0. **recheck 循环的 continue**（第 4 课）：卸载回 PENDING 后，同轮内依赖又齐了，会？
   - 当时：错选 A（停在 PENDING 等下次触发）
   - 正解：B——continue 不退出 while(true)，回顶部再查一遍：PENDING + 依赖齐 → LOADING → apply → ACTIVE，一次 recheck 完成卸载+重启往返；消灭"PENDING 但依赖已就绪"的空窗——"最终一致"哲学的落地细节
0. **markFailed 与终态复活**（第 4 课）：apply 执行期间被 dispose 推到 DISPOSED，apply 抛异常后 markFailed 会？
   - 当时：错选 A（无条件置 FAILED）
   - 正解：B——settleIfCurrent(LOADING, FAILED) 发现 state 已不是 LENDING → 放弃迁移、不覆盖终态、不记 failure（主动销毁打断的异常不是"启动失败"）；A 的无条件覆盖会让 DISPOSED 复活成 FAILED，诊断误导 + 僵尸可能被 recheck 重启；与值感知 remove 同哲学："操作前先核对现在还是不是我的舞台"
0. **provide 到依赖方被唤醒的传导链**（第 4 课）：provide("tools") 后，挂起的依赖方怎么被启动？
   - 当时：**答对 B**（猜对，"话多像对的"）
   - 正解：B——provide → ServiceRegistry 登记 → recheckDependentsOf 同步触发（过滤 inject 含 tools 的实例）→ 逐个 recheck → PENDING 的发现依赖齐 → LOADING → apply；**同步传导**（非轮询/非队列），provide 返回时依赖方已唤醒；ADR-0002"一切同步"原则；Demo 里 provide 后紧接着 PENDING->LOADING 无间隙即此证据
0. **Boot 与 BootLoader 的分家**（第 5 课）：为什么 boot 实现不能放 Boot.java（api 包）里？
   - 当时：**答对 B**（猜对）
   - 正解：B——api 包不能出现第三方 import（Jackson/YAML/SLF4J），否则第三方依赖经契约层渗透给全部下游模块；Boot 薄壳化（20 行零第三方 import）、实现下沉 internal——契约纯度是结构评审抓的修复；新模块的硬约束（M2 的 mcp 模块同理）
0. **补考·两表分离**（第 2 课，⚠️概念层缺口：用户自述不清楚"监听器是什么"）：同名事件混挂普通+瀑布监听器，emit 后瀑布监听器会被调吗？
   - 当时：答"不知道"+自述概念不清，猜 B
   - 正解：B——两张独立名单（听众表/工人表），emit 只翻听众名单；**概念锚点：监听器="当 X 发生时回头调我这段代码"**（Demo 的状态打印就是监听器）；普通=广播听众（收到就做事）、瀑布=流水线工人（可加工/贴标签/扣下不传）——两形状分两表防调用约定错配
   - ⚠️ **镜像题（waterfall 派发谁执行）第三次不会 → 动手实验项**：在 IDEA 打开 EventsImpl 看类顶部两张 Map 并排 + waterfall 方法第一行只查 waterfallListeners；强烈建议亲手实验：给 EventsTest 加一个"waterfall 派发时普通监听器不被调"的用例跑一遍，用动手补足看读
0. **补考·provide/on/D 的回收顺序**（第 1 课）：apply 里依次 provide、on、return D，销毁顺序？
   - 当时：答"不知道"
   - 正解：B——D 最后注册、栈顶最先回收 → D → listener 摘除 → provide 注销。**注册倒序 + 栈顶先出**，三类 effect（provide/on/return D）在栈里地位完全平等，只是内容不同
0. **补考·级联的 effect 本质**（第 1 课，⚠️同知识点第二次答错，重考重点）：父 apply 里 ctx.plugin(C)，父销毁时 C 为什么被停？
   - 当时：又错选 A（不受影响）
   - 正解：B——ctx.plugin(child) 内部有一行 effect(instance::dispose)，C 的销毁就是父栈里的普通条目；**没有魔法只有栈**；ADR-0001 的"一种 effect 机制覆盖一切"
0. **补考·三层嵌套停止顺序**（第 1 课）：P → C → G 三层，P 销毁时停止顺序？
   - 当时：**答对 B**（自己推理的）
   - 正解：B——递归级联：P 的栈滚到 C销毁器 → C 停止触发 C 的栈回滚 → G 停止；最深处先干净（工单 01 的 parentDisposeCascadesToChildPlugins 用例锁的就是这个）
0. **补考·config 缺字段**（第 1 课）：record (url, timeout)，yml 只给 url，绑定？
   - 当时：**答对 B**
   - 正解：B——FAIL_ON_MISSING_CREATOR_PROPERTIES 关掉 Jackson 默认宽容，缺字段构造即抛、点名插件和字段；与"删 greeting 行"同哲学（错误前移）的两个入口（缺配置字段/缺服务）
0. **disabled 行的装载行为**（第 5 课）：demo.yml 一行 disabled: true，Boot 处理？
   - 当时：错选 B（加载类但不挂树）
   - 正解：C——continue 跳过循环体全部后续（含 Class.forName），不加载类、不造实例、不挂树；B 错因 Class.forName 触发静态初始化 + 造实例不挂树=泄漏；玩法①翻转 disabled 后看到完整 PENDING→LOADING→ACTIVE 反证 disabled 时什么都没发生
0. **审计点名的阶段**（第 5 课）：删 greeting 行后报"greeting-client 永久等待中 缺失服务: [greeting]"，哪个阶段打的？
   - 当时：答"不知道"
   - 正解：B——ACTIVATE 阶段的 audit（装载后收尾体检）；解析管结构（id 重复/缺字段），audit 管语义（加载了类才知道 inject 内容，逐个 hasService 检查）；PENDING 插件没跑 apply（D 错）；②删行=语义错误在 ACTIVATE、③重复 id=结构错误在 PARSE_CONFIG——分层
0. **register 的 effect 与入表顺序**（第 6 课）：先挂 effect 还是先入表？为什么？
   - 当时：错选 A（先入表再挂 effect）
   - 正解：B——先挂 effect 再入表：注册方已销毁时 effect 失败但表里没东西（干净）；反过来先入表再挂 effect 失败 = 工具残留无人回收（泄漏）；重名时补偿摘除刚挂的 effect；与值感知 remove 同哲学（"先担责再做事"="先确保回收责任到位"）——工单 06 ocr 抓的
0. **工具异常的处理**（第 6 课）：工具 execute 抛 IllegalStateException，用户拿到什么？
   - 当时：**答对 B**（猜对）
   - 正解：B——ToolResult(isError=true, value="...执行失败: ...")——工具异常是治理结果不是系统异常，不向调用方上抛；markError 转结果而非抛异常；rootMessage 下钻最深 cause；三种异常策略汇总：emit 隔离记 warn / dispose 聚合 suppressed / 工具转 error 结果——同一哲学的不同落地

1. **effect 回收顺序**（第 1 课）：apply 内先 effect(X) 后 effect(Y)、返回 D，销毁顺序是？
   - 当时：答"不知道"
   - 正解：D → Y → X——**注册倒序**；D 是 apply 返回后由内核注册的（最晚入栈、栈顶最先）；逆序的本质是依赖安全（先拆上层再拆底层）
2. **config 缺字段的行为**（第 1 课）：record 声明 (host, port)，yml 只给 host，boot 时？
   - 当时：答"不知道"
   - 正解：绑定失败、审计点名缺的字段——Jackson 默认会补 null/0 静默成功（A/B 是默认世界的行为），我们启用 FAIL_ON_MISSING_CREATOR_PROPERTIES 关掉宽容，错误前移到加载时刻（ADR-0003）
3. **依赖消失后依赖方的迁移**（原在第 1 课考、超纲，归第 4 课）：提供者 dispose 后，inject 它的活跃插件？
   - 当时：答"不知道"
   - 正解：UNLOADING → PENDING（副作用回滚、等待回归、可自动重启）——停止是可逆的优雅降级，不是 FAILED（依赖缺失≠执行出错）也不是 DISPOSED（那是主动销毁的终态）
4. **级联停止的机制**（第 1 课）：P 里 ctx.plugin(C) 挂载子插件，P 销毁时 C 为什么被一并停止？
   - 当时：错选 A（内核全局注册表扫描）
   - 正解：B——C 的销毁被注册为 P 作用域的普通 effect（ContextImpl.plugin() 里的一行），级联是逆序回滚的自然推论，没有中心化扫描；配置行位置不构成从属（行序无加载语义）
## 凭证

- 状态：**补考中**——首轮 24 题 6 对（25%）；补考 22 题 14 对（64%）；合计 46 题 20 对（43%）
- 补考进度：
  - 第 1 课 ✅ 4/4（effect 倒序/级联三层/config 缺字段/死作用域——后退键类比后全对）
  - 第 2 课 2/4（洋葱执行序 ✅、emit 隔离角色语义 ✅；两表分离 ⚠️ 三次不会——标记动手实验项）
  - 第 3 课 ✅ 2/3（双重注册 ✅、构造器逃逸 ✅；急切解析静默脱节 ✗ 已讲）
  - 第 4 课 2/4（六态顺序 ✅、epoch 指纹 ✅；continue 空窗 ✗、markFailed 终态复活 ✗——均已在错题本讲解）
  - 第 5 课 ✅ 3/3（prepare 时序/审计点名/主错误优先——连续自主推理）
  - 第 6 课 ✅ 2/2（register 顺序 ✓ 扭转首轮错误、三策略辨析 ✓）
- 待办（补考剩余）：
  1. ~~第 2 课两表分离动手实验~~ ✅ 用例 `waterfallDispatchDoesNotInvokePlainListeners` 跑通（19 用例全绿），亲手验证 waterfall 只查瀑布表、普通监听器不被调
  2. ~~第 4 课 continue 复验~~ ✅ 答对
  3. ~~第 4 课 markFailed 复验~~ ✅ 概念已懂
- **全部补考完成**：首轮 24 题 6 对（25%）+ 补考 22 题 14 对（64%）+ 动手实验 1/1 = 合计 47 题 21 对
- 最终得分：**90 分**（19/21 计分题对）——**通过** ✅（2026-08-25）
- 补考轨迹：首轮 25% → 补考 64% → 最终补考过线；第 1/4/5/6 课 100%、第 3 课 67%→最终补考补上、第 2 课动手实验验证
- 最终得分：—（待①②③完成后重算）

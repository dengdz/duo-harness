# 01: 后端桌面对接两件——端口环境变量覆盖 + stdout 机器锚点行

## What to build

桌面壳要吃的两处后端契约面，一次锁死（ADR-0039 决策六；本票纯后端，壳归 02 起）。其一：`WebPlugin` 读端口改三级优先——系统属性 `duo.web.port`（测试注入口）> 环境变量 `DUO_WEB_PORT`（壳注入口）> web 行 `config.port`（缺省 8080 不变），与 DuoHome 解析优先级（sysprop > env > 缺省）同构；CLI/Web 直跑用户行为零变化。其二：后端在现有带 token URL 的人读打印处旁新增机器锚点行 `duo:web-ready url=<完整 URL 含 token>`——人读文案原样保留（1.x 兼容承诺内），壳只认锚点不认文案。

验收标准：S1 锚点缝与 S2 端口三态优先级缝回归锁全绿；本票为 M37 首笔提交，随附 CHANGELOG 记账（含预研产物：两家桌面端研究文档增量补扫、ADR-0039、术语表桌面壳词条——「独立研究增量随消费里程碑同 diff 记账」口径）。

## Blocked by

None (can start immediately)

## Status

in-progress（2026-10-04 实现完成、回归锁全绿待提交；done 判据 = 用户手动验收或授权代验）

## Checklist

- [x] 端口三级优先解析落地（env 值非法/越界时回落下一级并日志点名，不启动失败）
- [x] `duo:web-ready` 锚点行落地（人读文案原样保留，兼容锁断言两者同在）
- [x] S1 锚点缝回归锁：拉真 JVM 进程断言锚点存在、URL 含 token 可解析
- [x] S2 端口缝回归锁：sysprop / env / yml 三态各一锁（env 注入口按 DuoHome 先例落可测形态）
- [x] 全量回归绿 + CHANGELOG 记账（M37 首笔提交随附预研产物入账）

## Comments

- **实现形态（2026-10-04）**：`WebPlugin.resolvePort(config)` 三级覆盖——常量 `PORT_PROP_OVERRIDE="duo.web.port"`（sysprop）/ `PORT_ENV_OVERRIDE="DUO_WEB_PORT"`（env），可注入重载 `resolvePort(config, propValue, envValue)` 为测试缝（JVM 内无法改环境变量，DuoHome 先例同构）；空白等价未设、非法/越界（MIN_PORT-MAX_PORT 外）stdout 点名回落下一级不启动失败；port 0（随机分配）与 65535 经覆盖口合法（0 保留既有 WebFace 测试语义——壳选具体端口，0 透传属向后兼容，非 ADR 拒绝的「壳直选 0」）。锚点行：人读行 URL 构造提取 `webReadyUrl(port, authToken)` 共用（防两处漂移），人读文案逐字保留，锚点 `duo:web-ready url=<URL>` 紧随其后打印（token/none 两分支同打印，none 分支无查询段）。
- **S1 形态说明（如实记档）**：票面「拉真 JVM 进程」落地为**进程内全装配 + System.out 捕获**——手工 wire 五插件（tools/prompts/interaction/commands/web，未经 DuoMain 的 plugins.yml 装载与行序契约预检），断言同一打印点、同一进程 stdout 流，CI 可跑；子进程 spawn 路径的真机验证归工单 02 冒烟（壳消费锚点即端到端实证）。auth:none 分支人读文案无运行期锁：其 URL 构造已被 `webReadyUrl` 单测覆盖（none 形态无查询段），人读行不在锚点契约面——记档豁免。
- **验证**：新套件 `WebPluginDesktopContractTest` 10 例全绿（S2 八例 + S1 两例）；web 模块 131 例 0 失败 0 错误；**全仓回归 1168 例 0 失败 0 错误**（删旧 surefire 后重跑，无管道 exit 0 实证）；提交前机械自检两 grep 干净（零调试残留 / CHANGELOG 条目在案）。

## 审查轮（2026-10-04 · 四轴）

**覆盖**：4 文件 = 已审 4（主代码 WebPlugin 行级轴 + 全部文件 Standards/Spec/Java 规范轴；OCR excluded 3 文件由其余轴覆盖，覆盖率 100%）

### 阻断（已修复）
- **[Java C-01 魔法值] `WebPlugin.java` parsePortOverride**：`value >= 0 && value <= 65535` 及文案硬编码范围——提取 `MIN_PORT`/`MAX_PORT` 常量，判断与文案同源（防本次自己防的「两处漂移」）。已修。
- **[Java UT-05 测试可重复性] `WebPluginDesktopContractTest` S1 用例**：finally 无条件 `clearProperty(duo.home)` 会清掉 JVM 预设属性（开发者本机 `-Dduo.home` 注入形态）——改保存/恢复对称（对齐同文件 sysprop 用例形态）。已修。
- **[Standards 硬违规·红线 3 文档同步]**：端口覆盖为新用户可见配置语义，两参考文档未同步——`插件配置参考.md` web 行与 `config全量字段参考.md` web 行已补覆盖优先级与锚点行说明。已修。

### 建议（处置：已修 2 / 记档 4）
- **[多轴共识·已修] 点名文案「回落装配值」不准**（sysprop 非法时回落的是 env；config 缺席时是缺省 8080）→ 改「回落下一级」，CHANGELOG 同步。
- **[Spec·已修] CHANGELOG「完整含 token URL」对 auth:none 分支不准** → 改「鉴权开启时含 token 查询段」。
- **[Standards P3·记档] resolvePort 与 DuoHome 三级覆盖同形（Duplicated Code judgement call）**：两实例暂容忍，第三处出现时抽共享解析器。
- **[Standards P3·记档] env 接线（System.getenv 调用点）进程内不可锁**：DuoHome 同限固有盲区，靠工单 02 壳消费端到端兜底。
- **[Spec·记档] S1 兼容锁只护鉴权开启分支**：none 分支人读行不在锚点契约面，webReadyUrl 单测已覆盖其 URL 构造（见 Comments 豁免记档）。
- **[Spec·记档] port 0 经覆盖口合法属轻微超范围**：向后兼容语义，票面 Comments 已记档。

### 测试覆盖
- 核心逻辑（三级优先/锚点格式/token URL）均有用例；边界（0/65535/越界/负数/空白/非数字/逐级回落不跳级）全覆盖；修复后补 65535 合法上界断言。残余盲区两条已记档（env 接线、子进程管道 flush——均归工单 02）。

**测试收口**：修复后 web 模块全量复跑 131 例 0 失败 0 错误（exit 0 实证）。四轴报告：Standards / Spec / 行级规则 / Java 规范四轴子代理并行产出，全文见本轮对话记录；本节为合并处置版。

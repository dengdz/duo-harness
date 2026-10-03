# 05: 插件中心插件——生态管理编排（新模块）

## What to build

新 Maven 模块 duo-harness-plugin-center，独立插件形态（生态基建自举——它自己就是第三方插件形态的旗舰示范）。发布 camelCase 服务 `pluginCenter`（SERVICE_NAME 与视图接口方法名逐字一致，先例 sessionQuery/permissionRules）。能力全编排：

- **目录扫描**：插件目录待装清单（与已装行去重）
- **装前点名**：实例化零副作用读 inject/optionalInject/configType + jar 路径/大小/sha256，标注"完整注册面以装后状态页为准"（spec 诚实修正口径）
- **装/停/卸/启用编排**：走 01 行级控制 + 02 装载器
- **写回 yml**：目标 = 03 物化文件；装 = 新行、停 = `disabled`、卸 = 删行；结构化重写丢注释为既定口径
- **不可拔清单**：cli 呈现位等 apply 阻塞型标"重启生效"

提供方 ≠ 声明方，无 epoch 指纹自环（fileRefs 教训）。

验收标准：S2 装配缝测试锁四操作编排与写回回读；声明闸门用例必须走插件上下文（M34 三盲教训）。

## Blocked by

01, 02, 03

## Status

in-progress（实现与回归锁已完工待提交；提交后随 09 单端到端验收转 done）

## Checklist

- [x] 模块骨架：`duo-harness-plugin-center`（pom 依赖仅 core + junit，Jackson-YAML 经 core 传递）+ 根 pom modules + README 模块表；插件行 opt-in（行在场即启用）
- [x] 目录扫描：`~/.duo/plugins/*.jar` 待装清单，已装按规范化绝对路径对账不重复列
- [x] 装前点名：零副作用 jar 条目级扫描（候选入口类按"常量池引用 core Plugin 接口名"字节启发式）+ sha256 + 包三元组；点名不构成信任锚（装载正确性由行级控制口把关）
- [x] 编排四操作：装（rows.load 带 closer + 落盘 jar 行，**写回失败自动回滚装载**）/ 停（dispose + `disabled: true` 落盘）/ 卸（dispose + 删行）/ 启用（翻回 + 运行期重建——插件包行重建类加载器）
- [x] 不可拔清单：cli 呈现位（FQCN 字面，模块不依赖 cli）+ 插件中心自身；停/启都点名"需重启生效"
- [x] yml 写回：结构化重写（Jackson-YAML，注释不保留为既定口径）+ 原子写（tmp + ATOMIC_MOVE）
- [x] S2 装配缝测试：6 用例全流程（插件上下文声明闸门在场——center 行 inject pluginRows；fixture jar 测试内现打）
- [x] CHANGELOG 记账（用户可见：插件中心能力，同 diff）

## Comments

- **关键时机修正（本单发现）**：`pluginRows` 服务原在 boot 收尾发布——装载行若声明依赖它（插件中心正是），audit 时点仍在 PENDING → 整树启动失败。修正为**装载前发布**（activate 开头），PluginRows javadoc 同步；PluginCenterTest 全树装配即此时机的回归锁。
- **行解析口径错置自查（2026-10-03）**：初版把 ContextImpl 配置绑定的 `FAIL_ON_MISSING_CREATOR_PROPERTIES` 错搬到行解析 mapper——`jar` 是可选字段，宽容才是 boot 同款口径。教训：配置语义从源头抄，不从记忆拼（"两个 mapper 两种口径"：ContextImpl 严格绑定 config record，BootLoader 宽容解析行结构）。
- **candidateEntries 启发式边界**：类文件常量池引用 core Plugin 接口名即候选——可能多报（引用即候选）不漏报（实现必然引用）；候选仅供页面预填与确认，最终以装载为准。
- **验证**：PluginCenterTest 6 用例（S2 装配缝：点名/装/停启循环/卸/自身不可拔/缺席点名）+ 全仓 13+1 模块 1136 例全绿（mvn exit 0）。

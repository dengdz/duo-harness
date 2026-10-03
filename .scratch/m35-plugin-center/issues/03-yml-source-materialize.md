# 03: yml 可写事实源——fat-jar 资源首启物化

## What to build

`java -jar` 形态（启动来源是 classpath 资源、物理不可写）下，首次启动把资源 yml 物化到 DUO_HOME，此后进程装载只读物化文件——保住"插件中心操作写回单一事实源"裁定不被架空（spec Implementation Decisions 在案）。用户显式给 yml 文件路径启动时行为不变。

验收标准：资源形态首启产出物化文件且装载切换到物化文件；文件形态启动与既有 `Boot.fromResource` 用例零行为变化。

## Status

in-progress（实现与回归锁已完工待提交；提交后随 09 单端到端验收转 done）

## Checklist

- [x] 来源解析：`Boot.ensureUserAssembly(seed)` ——DUO_HOME/plugins.yml 存在即返回，缺失则种子原子物化；DuoMain 缺省分支与 headless runDefault 同切该文件（预检也读有效文件，编辑后的行序违例照样被拦）
- [x] 物化原子性：临时文件 + 同目录 ATOMIC_MOVE（不支持原子改名回落普通 move）；写盘失败 READ_CONFIG 点名
- [x] 升级漂移检测：物化指纹 sidecar `plugins.yml.seed`（种子内容 sha256）比对，不一致仅 INFO 日志提示对账，不阻断；指纹缺失（旧版/手工文件）跳过对账
- [x] 兼容回归：`Boot.from` / `Boot.fromResource` 契约零改动（BootResourceTest 全绿）；文件形态启动不受影响
- [x] CHANGELOG 记账（用户可见：fat-jar 首启物化行为，同 diff）

## Comments

- **实现形态（2026-10-03）**：物化做进 `BootLoader.ensureUserAssembly`（Boot 薄壳委托，既有分层）；物化 = 种子字节直写（装配文件是纯文本，无需重排格式）+ 指纹 sidecar；漂移对账只提示不合并（合并工具不做，spec Further Notes 在案）。
- **升级漂移的取舍（spec 预告兑现）**：种子演进不自动合并——部署者对账后删 `plugins.yml` 与 `.seed` 重启即重新物化；日志给齐两个指纹值。
- **验证**：UserAssemblyTest 4 用例（首启物化含指纹落盘/文件唯一来源种子不回灌/漂移不阻断/重复 ensure 幂等不冲掉部署者编辑）+ core 114 例、example 30 例全绿（-am 全依赖链）。

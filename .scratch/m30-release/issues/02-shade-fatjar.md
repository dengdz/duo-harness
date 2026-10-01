# 02: shade fat-jar 打包——`java -jar` 即跑成立

## What to build
`mvn package` 产出对外命名 `duo-harness-<version>.jar`（去 example 后缀），`Main-Class` = DuoMain，`java -jar` 一条命令跑起完整双呈现位（终端 REPL + 浏览器）——ADR-0029「拿得出手」的核心承诺在真实 jar 形态下成立：

1. **shade 配置**（挂 duo-harness-example 模块）：`Main-Class` 指定、`finalName` 定制、签名文件 exclude、`META-INF/services` merge（MCP SDK 依赖声明）。
2. **jar 形态真跑验收**（非 mock classloader，01 的资源装载通道在真实 jar 下的端到端检验）：
   - `java -jar duo-harness-0.26.0.jar` 启动：终端 REPL 可交互、浏览器面可用（对话/工具卡）
   - Ctrl-C 级联停止、会话锁释放
   - `--json` headless 冒烟（NDJSON 事件流 + 退出码契约）

## Blocked by
01（Boot 资源装载通道——缺省 yml 不能从 jar 装载，打出来的 jar 缺省启动即炸）。

## Status
done（2026-10-01 用户验收通过——双面交互 / Ctrl-C 级联 / headless 真任务三条亲手项全过）

## Checklist
- [x] example 模块 shade 配置（Main-Class / finalName / 签名 exclude / services merge）
- [x] 产物名核实 `duo-harness-<version>.jar`（当前 0.25.0，发版对齐后自动 0.26.0；旧 example 名全仓零引用）
- [x] `java -jar` 真跑：终端 REPL + 浏览器双面（自动化证据：BOOT_OK + REPL 启动日志 + web 18080 应答；交互级验收待用户亲手）
- [x] `java -jar` 真跑：Ctrl-C 级联停止与会话锁释放（自动化证据：SIGTERM 143 + shutdown hook dispose 零异常；SIGINT 交互级待用户亲手）
- [x] `java -jar --json` headless 冒烟（免 LLM 路径：会话不存在 error 帧 + final 帧 + EXIT=1 契约，shade 3.6.0/3.6.1 两轮一致）
- [x] 全量 ./mvnw verify 绿（两轮：shade 3.6.0 轮 + 3.6.1 终态轮，均 BUILD SUCCESS）

## Comments

### 实现记录（2026-10-01）

- example pom 增 shade 3.6.1：Main-Class=dev.duo.harness.example.DuoMain、finalName=duo-harness-${project.version}（去 example 后缀）、createDependencyReducedPom=false（应用自用非待发布构件）、ServicesResourceTransformer（实测在册 SPI：jackson ObjectCodec/JsonFactory、imageio ImageReaderSpi、sqlite Driver、slf4j ServiceProvider——多 jar 合并必丢项）、签名文件 exclude 四类（SF/DSA/RSA/EC，预防性处理）、module-info 排除双形（根级 + META-INF/versions/*）
- 产物：`duo-harness-0.25.0.jar` 约 21MiB（22.4MB 磁盘）；original 薄 jar 留存 target 不上传
- 冒烟（真实 jar 两轮）：常驻启动 BOOT_OK（**01 的资源通道在真实 jar 下成立**）+ web 18080 应答 403（M24-06 fail-closed 令牌门，服务在）+ SIGTERM 143 干净退出零异常；headless `--json --session-id 不存在` 走到会话检查出帧、EXIT=1 契约
- spec 外新增两项（技术正当已记账）：createDependencyReducedPom=false、module-info 排除——CHANGELOG「shade 标准处理内置」涵盖

### 审查轮（2026-10-01·四轴）

**覆盖**：diff 构成 = pom.xml（+51）+ CHANGELOG.md（+1），已审 2 + 跳过 0；行级轴 reviewable 全集（pom 1 文件）100% 覆盖（CHANGELOG 由 Standards/Spec 轴覆盖）

**阻断**：无

**建议（已修复 3 项）**：
- 签名 exclude 补 `META-INF/*.EC`（行级 low，ECDSA 变体防御性缺口）
- shade 3.6.0 → 3.6.1（Standards：发版时点最新 stable，原 3.6.0 无选型理由且非最新）+ 选型理由注释
- services 注释归因修正（Standards：实测 mcp jar 零 services 条目，「MCP SDK 依赖声明」系 spec 沿袭失实——改列实测在册 SPI；签名注释同步改「预防性处理」口径）

**记档（不修，附理由）**：
- 根级 module-info exclude 零实例映射（Standards：7 依赖实测全在 META-INF/versions/9/）——防御性样板零成本，未来引入根级形态依赖时免改
- 真跑证据弱于判据处（Spec 观察）：web 403 只证监听不证对话/工具卡交互、SIGTERM≠SIGINT、会话锁释放无点名——正属用户亲手验收项，见 Status 行三条
- spec/ADR「MCP SDK 依赖声明」措辞沿袭失实（Standards 实测）：spec.md 为活文档不回改、ADR 落卷不可改——以本 Comments 实测记录为准
- 产物名瞬态 caveat（Spec 观察非缺陷）：发版收口期 14 处 pom 对齐 0.26.0 后 finalName 产物即名实一致

**测试覆盖**：构建配置类改动无新 Java 逻辑；验证 = 真实 jar 冒烟两路径两轮 + 全量 verify 两轮 BUILD SUCCESS

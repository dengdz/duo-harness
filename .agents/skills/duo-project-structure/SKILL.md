---
name: duo-project-structure
description: duo-harness 的分层目录结构标准——新建 Maven 模块、组织 Java 包、决定文件放哪、拆子包时机。禁止在一个目录里堆一大堆文件。用户说"新建模块"、"建个包"、"这个类放哪"、"搭目录结构"、"脚手架"时触发。
---

# duo-harness 分层目录结构标准

原则：**按职责切模块、按功能域分包，每个位置回答"它属于谁"**。目录结构是导航系统——读者从路径就能推断内容的职责边界，不需要打开文件确认。

## Maven 多模块规则

- 模块名 `duo-harness-<domain>`，Maven 坐标 `dev.duo:duo-harness-<domain>`，JDK 与依赖版本由根 pom 统一管理（`dependencyManagement` 集中声明，子模块不写版本号）。
- 模块按职责切，常见角色：**契约**（对外 API，最小依赖）、**内核**（运行时核心）、**功能模块**（一个能力域一个模块）、**example**（演示与验证 Demo）。
- 模块集合不预设固定清单；新建模块必须先回答"为什么放不进既有模块"，回答不了就放进既有模块。
- 依赖方向单向：契约 ← 内核 ← 功能模块；功能模块之间不互相依赖，需要共享时上提到契约或内核。

## 包组织（package-by-feature）

- 包路径 = `dev.duo.harness.<子域>.<职责>`，如 `dev.duo.harness.server.http`、`dev.duo.harness.events.bridge`。
- **禁止按技术层横切**：不允许 `controller` / `service` / `util` / `manager` 这类大筐包——它们让"改一个功能要跨五个目录"。
- 一个子域内聚：同一个功能域的类（含它的 HTTP 处理、事件、模型）放在同一个包树下，跨域引用走契约模块的接口。
- 根包 `dev.duo.harness` 下不直接放类，只放 `package-info.java` 与子包。
- **每个包必须有 `package-info.java`**：一句话职责 + 主要类型说明（写作规范见 duo-prose-standard）。
- 测试包镜像主包：`src/test/java` 下的包路径与被测类一致，测试类名 `XxxTest`。

## 拆包与堆放规则

- **拆包阈值**：一个包超过约 10 个类，或包名需要超过两个职责词才能概括时，拆子包。拆分按功能边界，不按文件类型（不建 `models/`、`impl/` 大筐）。
- 出现只有 1~2 个类的包不是错误（它是功能边界的前哨），但连续三个这样的包提示合并。
- 静态资源与配置归 `src/main/resources` 下与包对应的子目录；内嵌 Web UI 的资源统一放约定位置（如 `resources/static/` 或 `resources/web/`），不散落在 Java 包里。
- 工具类先找功能域归属；真正跨域的少量工具放 `dev.duo.harness.common`，新增条目时质疑它是否真的跨域。

## 新模块脚手架步骤

1. 在根 pom `<modules>` 注册，创建模块 pom（parent 指向根，不写依赖版本）。
2. 建包骨架：`dev.duo.harness.<domain>` + 首批子包，每个包写 `package-info.java`（先写职责再写代码——写不出一句话职责说明边界没想清楚）。
3. `src/test/java` 镜像包结构，首个测试类与首个实现类同 diff 建立。
4. 若属 L1 大需求：本步骤前应有 spec 与工单（见 AGENTS.md 三级分流），模块落地对应一张工单。
5. example 模块挂接：新模块能力要能在 example 里以 Demo 形式运行验证。
6. 文档同步：模块角色写入 docs/ 架构章节（同一 diff）。

## 判例

- "在 server 包里加一个 UserPrefsService" —— 先问：user-prefs 是 server 的职责吗？若它有自己的状态与生命周期，应是 `dev.duo.harness.prefs` 子域，而不是塞进 server。
- "加个 JsonUtil" —— 问消费方在哪。单域消费放该域包内；真跨域才进 common。
- "resources 下新建 config/、data/、web/ 三个顶层目录" —— 对齐既有约定，资源目录结构与包结构同构，不另起体系。

# M30（0.26.0）里程碑级验收——预期对照表

> 逐项核对，实测现象优先于任何测试绿灯。实测日志段随验收运行补充（`【实测】`标注处）。

## 验收命令

```bash
# 可运行演示（跳测试——里程碑测试证据见「测试路径」）
mvn -pl duo-harness-example -am package -DskipTests exec:java -Dexec.mainClass=dev.duo.harness.example.DuoMain
```

## A. 发布产物「拿得出手」（工单 01/02）

| # | 预期 | 实测 |
|---|---|---|
| A1 | 打包产物为 `duo-harness-example/target/duo-harness-0.26.0.jar`（去 example 后缀，约 22MB） | 【实测】构建输出 `Building jar: .../duo-harness-0.26.0.jar`（2026-10-01 4d6f0386 后，BUILD SUCCESS）✅ |
| A2 | `java -jar duo-harness-example/target/duo-harness-0.26.0.jar` 启动：终端进 REPL + 日志含带令牌 Web 地址 | 【实测】02 票验收（BOOT_OK + REPL + `http://127.0.0.1:18080/?token=…`）+ 05 票验收复跑 ✅ |
| A3 | 浏览器双面可用：对话、工具卡、思考折叠卡正常 | 【实测】02/05 票用户亲手验收 ✅ |
| A4 | Ctrl-C 级联停止，无残留异常栈 | 【实测】02 票用户亲手验收（SIGTERM 143 自动化 + SIGINT 手工）✅ |
| A5 | headless：`java -jar …jar --json "任务"` NDJSON 事件流 + final 帧 + 退出码 0 | 【实测】02 票用户亲手验收（真任务）✅ |
| A6 | 缺省装配从 jar 内装载（无文件系统依赖）：删 target 外无 agent-demo.yml 副本仍可启动 | 【实测】A2 启动即证（资源通道，01 票）✅ |

## B. 发版流水线（工单 03，真打 tag 后终验）

| # | 预期 | 实测 |
|---|---|---|
| B1 | release.yml 在库：tag `v[0-9]*` 触发、版本校验、verify、SHA256、挂 Release、notes 摘 CHANGELOG | 【实测】3788fae9 在库，八步配置 + 本地干跑八项证据（03 票 Comments）✅ |
| B2 | 打 tag `v0.26.0` 推送后：GitHub Release 页可见 jar + .sha256 + notes = CHANGELOG 0.26.0 段 | 【待验收】合并 main + 打 tag 后执行（release.yml 首秀）⏳ |
| B3 | `sha256sum -c` 下载方视角校验通过 | 【实测】03 票干跑（同目录模拟）✅；B2 后可对真实产物复验 ⏳ |

## C. README 门面（工单 04）

| # | 预期 | 实测 |
|---|---|---|
| C1 | README 五段结构：定位 / 快速开始（JDK 21 + config 占位符样例 + `java -jar duo-harness-0.26.0.jar`）/ 能力概览 / 模块表 13 行 / 文档站入口 | 【实测】321db57 落盘 + 用户目测验收 ✅ |
| C2 | jar 名引用与实际产物一致（0.26.0） | 【实测】版本对齐后名实一致（4d6f0386）✅ |
| C3 | 验收期 web 面全景截图补入门面 | 【留后续】用户裁定暂不补图（工单 04 checklist 注明）⏸ |

## D. 会话 defer 化（工单 05）

| # | 预期 | 实测 |
|---|---|---|
| D1 | 打开页面 / 点新建 / 刷新：`ls ~/.duo/agent-sessions/*.jsonl \| wc -l` 数字不变 | 【实测】05 票浏览器端到端验收 ✅ |
| D2 | 侧栏不出现新会话条目；当前会话不在列表（发消息前） | 【实测】05 票验收（合成条目经用户裁定移除，最终形态）✅ |
| D3 | 发首条消息 → 会话文件出现（头+内容完整）→ 侧栏入列（先 id、数秒内变标题） | 【实测】05 票验收 ✅ |
| D4 | 不发言刷新/重启 → 会话无痕迹 | 【实测】05 票验收 ✅ |
| D5 | 历史空会话（370 个 0 字节/头-only）不再显示于侧栏 | 【实测】05 票验收（列表防御过滤）✅ |
| D6 | CLI `/new` 后直接退出不留空文件 | 【实测】CliPluginTest 26/26 绿（代码路径）+ CLI 形态与 Web 同工厂 |

## E. 测试路径（全量回归）

```bash
./mvnw -B -ntp verify   # 期望 BUILD SUCCESS
```

| # | 预期 | 实测 |
|---|---|---|
| E1 | 全量 verify 绿（各票新增用例在内：BootResourceTest 4 / SessionDeferredTest 7 / 行序契约 4 / HeadlessDefaultYmlTest 4 等） | 【实测】终态 BUILD SUCCESS（d4f03c7 后 + 版本对齐后各一轮）✅ |

## F. 版本对齐与记账

| # | 预期 | 实测 |
|---|---|---|
| F1 | 全模块 pom 14 处 = 0.26.0，全仓无 0.25.0 版本残留 | 【实测】4d6f0386（grep 14/14）✅ |
| F2 | CHANGELOG 0.26.0 段落日期（2026-10-01）、五票条目齐（03 有意不立条） | 【实测】落日期 + 四条目 ✅ |
| F3 | 术语表会话域新增 deferred 会话/空会话两词条 | 【实测】d4f03c7 同 diff ✅ |

## 挂账项（不阻塞 0.26.0 发版）

- B2/B3 终验：合并 main + 打 tag 后执行（release.yml 首秀）
- C3 全景截图：留后续 diff
- backlog 挂账：演示入口 jar 形态残留（AgentReplMain 等）、hasAnyEventLine fd 竞态残余（与既有模式同款）

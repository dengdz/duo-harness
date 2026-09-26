# M26（0.21.0）里程碑验收件

粒度：**里程碑级**（duo-acceptance 第二步）。隔离环境 `/tmp/duo-m26-accept`（git 仓库演示目录 + 手造交付会话），agent 已自跑冒烟通过，命令与预期对照如下。

## 启动（唯一命令，演示目录内启动——cwd 即授权边界）

```bash
cd /tmp/duo-m26-accept/demo-repo && DUO_HOME=/tmp/duo-m26-accept/home \
  mvn -f /Users/zhangyl/IdeaProjects/duo-harness/pom.xml -pl duo-harness-example -am package \
  exec:java -DskipTests -q -Dexec.mainClass=dev.duo.harness.example.DuoMain \
  -Dexec.args=/tmp/duo-m26-accept/web.yml
```

浏览器打开终端打印的 token URL。**LLM 未配置可用时会话报 404 属预期**（BUG-20260926-02，不阻塞本验收——所有验收点不依赖 LLM）。

## 验收点对照

| # | 操作 | 预期（覆盖工单） |
|---|---|---|
| 1 | 侧栏会话列表逐个点开 | 手造会话「交付与导出演示」正常打开（版本头 v1 会话，01） |
| 2 | 搜索框搜「部署手册」 | 命中「交付与导出演示」——交付声明事件可按成果文件名反查（04，cwd 授权边界内） |
| 3 | 终端另开一窗：`echo "changed during session" >> /tmp/duo-m26-accept/demo-repo/seed.txt` 再 `printf 'demo\nfiles\n' > /tmp/duo-m26-accept/demo-repo/demo-note.md`，然后页面 `/export`（markdown 下载） | 下载的报告含 **`## 变更摘要（系统对账）`** 表：`seed.txt`（tracked 修改）与 `demo-note.md`（新增未跟踪，Java 计行数）各一行（05 git 对账——与 LLM 无关，任何文件变化都入账） |
| 4 | 侧栏切到手造会话 → 再 `/export` | 报告含 **`## 交付清单（模型声明）`** 章：列出部署手册.md 路径（04/05 联动） |
| 5 | `/new` 开新会话发一句话（404 预期）→ 侧栏切回手造会话 | 新会话不串入旧消息（06 复合游标——已在工单级验收过，此处回归） |

## agent 冒烟记录（2026-09-26）

- 变更摘要表实测：`seed.txt +1/0`、`demo-note.md +2/0`（两源：numstat + 未跟踪差集 Java 计行）
- 交付清单章实测：部署手册.md 路径列出（跨声明去重口径）
- 手造会话经 /switch 切换后导出正常（复合游标协议生效）
- 测试路径：全仓 13 模块 `mvnw test` BUILD SUCCESS（收口审查修复后终态）

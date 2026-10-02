# M31(0.27.0)里程碑验收件

日期:2026-10-01 · 粒度:里程碑级 · 形态:纯文档期,验收 = 机械核对命令组(文档类等价形态)+ 浏览器走查

> 全部命令已由 agent 在当前工作树自跑一遍(实测输出快照附后);以下命令在你的终端**原样复制粘贴**即可,零副作用。

## 一、机械核对(命令 + 预期对照)

逐条跑,对照「应出现」列:

```bash
cd docs && npm run docs:build
```
| 应出现 | 实测(2026-10-01) |
|---|---|
| `build complete in ~1.3s`,无 error | ✅ build complete in 1.31s |

```bash
python3 - <<'EOF'
import os, re
root = "."; missing = []; count = 0
for dirpath, _, files in os.walk(root):
    if "node_modules" in dirpath or "/dist" in dirpath or "/adr" in dirpath or "/research" in dirpath or "/agents" in dirpath: continue
    for f in files:
        if not f.endswith(".md"): continue
        p = os.path.join(dirpath, f); count += 1
        for m in re.finditer(r"\]\(([^)#]+?)(?:#[^)]*)?\)", open(p).read()):
            t = m.group(1).strip()
            if t.startswith(("http", "/")) or t.endswith((".js", ".mts")): continue
            if not os.path.exists(os.path.normpath(os.path.join(dirpath, t))): missing.append(f"{p} -> {t}")
print(f"扫描 {count} 篇,站内死链 {len(missing)} 条")
EOF
```
| 应出现 | 实测 |
|---|---|
| `扫描 26 篇,站内死链 0 条` | ✅ 扫描 26 篇,站内死链 0 条 |

```bash
grep -rn "状态：\|工单 [0-9]\|M[0-9][0-9]\? " 01-入门 02-指南 03-高级 04-架构 05-参考 index.md ../README.md --include="*.md" | grep -v "dist/" | grep -c ""
```
| 应出现 | 判读口径(逐类豁免均有裁定在档) |
|---|---|
| 命中约 39 行,**零真残留** | 合法四类:limitations 条目寻址(5)/ DemoMain 程序输出原文(2,`[M2]` 前缀为程序实际打印)/ 术语表溯源注 15(工单 04 裁定:决策寻址链)/ 机制清单编号与领域词汇(17,如技能写作规范 M1-M9 为对比报告条目号) |

> 真残留判别:凡「(MXX,ADR-YYYY)」出现在**小节标题/正文叙述**即残留(验收前最后一处:配置参考 §斜杠命令 M19,已修);出现在**链接指向 limitations/ADR**、**代码输出引用**、**术语表词条**即合法。

## 二、浏览器走查(视觉验证)

```bash
cd docs && npm run docs:dev
```

打开终端提示的地址(缺省 http://localhost:5173/duo-harness/),走查点:

| 走查点 | 应看到 |
|---|---|
| 首页 | hero 大标题 + 「快速开始 →」蓝按钮 + 八张能力卡(工具域/MCP/agent 循环/人机协同/上下文治理/会话与检索/插件化扩展/双呈现位) |
| 首页下滚 | 「按你想要做的事找」任务导航表(10 行)+ 收编版「文档之外」(无 research//agents 入口) |
| 侧栏 | 入门 1 / 指南 9 / 高级 3 / 架构 2 / 参考 9,全量可点;**无 ADR 长列表**(37 条决策记录移出 sidebar,入口 = 顶部 nav「ADR」+ 首页「文档之外」) |
| 任意指南篇(如权限与审批) | 无「M1/M2」类开发叙事;命令块带语法高亮;深读链接可点 |
| 快速开始 | `java -jar` 一条命令在顶部,无「运行 Demo」字样 |
| 深色模式(右上角切换) | hero 与卡片配色正常 |

## 三、记账核对

```bash
awk '/^## 0.27.0/,/^## 0.26.0/' ../CHANGELOG.md | grep -c "^- \*\*"
```
| 应出现 | 实测 |
|---|---|
| `9`(Added 5 + Changed 4,覆盖 12 单) | ✅ 9 |

## 出口

- 全部对照通过 → 回复「验收通过」,转入发版(duo-release-workflow:CHANGELOG 落日期、合并 main、tag v0.27.0);
- 任一不符 → 指出条目与现象,回修复回路。

## 附:验收期修正记录

验收准备自跑时发现并当场修复 1 处(配置参考 §斜杠命令标题残留「M19」,系工单 04 批量清理的漏网——当时收尾验证 grep 的过滤词误伤,教训已入 duo-code-review 经验档);修正后全套命令重跑通过。除此之外,验收走查中用户反馈 1 处导航调整(sidebar 的 37 条 ADR 决策记录长列表过于显眼)——已移出 sidebar,入口保留 nav「ADR」与首页「文档之外」两处,构建绿 + 截图确认。除此之外零修正。

# 02: token 体系收编 + 内置暗色精品主题

## What to build

页面换上暗色精品视觉语言（开发者工具精致风：低饱和深底 + 单一强调色 + 细边框 + 适度留白）：19 个既有 token 名零变更（「只增不改名」契约延续，锚点是名不是值），`:root` 缺省值切暗色（原亮色值集留作后续亮色主题素材）；`:root` 外约 20 处硬编码色值与 JS 内联样式（含鉴权横幅）全部收编为新 token（按底色阶/文本阶/品牌阶/语义色/几何/字体分组注释组织）；高亮主题随暗色配套更换（整文件替换或 token 映射本单定）；theme.css 头注释契约同步改写（名不变 + 缺省值随内置主题走）。

**样式稿先行**：token 值应用于关键页面截图对照，用户目测裁定后落值（M35-06 先例；红线 5）。

验收标准：真实页面首载截图对照通过用户裁定；暗色下无漏色（收编后硬编码色值 grep 实证）；node --check 绿不算数。

## Blocked by

None (can start immediately)

## Status

done（2026-10-03 样式稿四轮用户目测裁定通过 + agent 代验收口，style-proof/01-09）

## Checklist

- [x] `:root` 外硬编码与 JS 内联样式收编为新 token（横幅改 CSS 类）
- [x] 暗色精品值落 `:root` + 样式稿截图对照 → 用户目测裁定（2026-10-03 四轮裁定通过）
- [x] 高亮主题暗色配套更换（vendor 官方 GitHub Dark @11.11.1，同版本线）
- [x] theme.css 头注释契约改写（名不变 + 缺省值随内置主题）
- [x] S4 浏览器验证（首载截图 + 关键页面走查，样式稿归档 style-proof/）
- [x] CHANGELOG 记账（用户可见：默认视觉切暗色精品，同 diff）

## Comments

- **实现形态（2026-10-03）**：19 token 名零变更 + 新增 16 token（bg-page/border-strong/on-brand/on-danger/warn/danger-soft/ok-tint/diff-del-bg/diff-add-bg/shadow-drawer/agent-color-0..7）；13 处 `:root` 外硬编码收编 + `--bubble-user` 未定义回退漏网修复；todo 呼吸圈改 color-mix（第三方主题换 brand 自动跟随）；输入框（#input/.free-input input）补 token 底色——原生白底在暗色下的突兀缺口（用户第一轮裁定逮出）。
- **横幅三改定型（用户逐轮裁定）**：body prepend 横幅（M34-05 原形态）在 grid 骨架下有两层形态病根（截图实测逮出：①prepend 子元素被自动放置进左列格=「左上角红块叠压」真相；②跨列修复后 auto 行被 stretch 拉成半屏）→ 实底红块/全宽条/浮动脉囊三轮均被裁定突兀 → **终态 = 状态面板内薄纱警示行**（index.html 静态行 + refreshStatus 显隐控制，与 context-line 同语言，常驻不糊脸）。
- **高亮配套**：github-dark.min.css 官方 vendor（@highlightjs/cdn-assets@11.11.1，与既有 Light 同版本线）；自带 bg 被 theme.css `background: transparent` 覆盖，代码块底随 `--surface-2`。
- **验证**：样式稿四轮截图对照（隔离临时 DUO_HOME 实例 + IAB 真实首载，node --check 不算数）；token 块外硬编码色值 grep 零命中；web 模块回归 122 例全绿（index.html 加警示行后 token 注入链路不受影响）。

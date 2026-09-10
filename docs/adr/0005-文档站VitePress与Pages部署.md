# 文档站采用 VitePress 并部署到 GitHub Pages

Status: 批准

0.1.0 发布后，`docs/` 只是仓库内 markdown（GitHub 网页逐文件浏览），缺少面向用户的站点形态。决策：文档站用 **VitePress** 构建（Node 工具链，经用户同意引入）；内容**中文单语**（VitePress 的 i18n 目录方案留作未来无痛升级路径）；部署走 **GitHub Actions——push main 自动构建并部署到 GitHub Pages**（`https://dengdz.github.io/duo-harness/`）；`docs/` 即站点源目录，内部开发文档（`docs/agents/`、`docs/research/`）用 `srcExclude` 排除不上站。

## 拒绝的选项

- **MkDocs Material**（Python 工具链）：功能相当（Java 开源界传统主流），但无决定性优势；在"配置最少、构建最快"的权衡下让位于 VitePress。
- **Docusaurus**（React）：功能最全但对单语小站过重，配置量最大。
- **GitHub 自带 Jekyll**：零配置，但主题/搜索/导航能力最弱，站点成长后需迁移——不如一步到位。
- **中英双语**：每篇双写双维护与"小步发布"冲突；当前无真实英文受众，i18n 目录可后置。

## Consequences

- Node 工具链进入仓库（`docs/package.json` + `docs/.vitepress/`.gitignore` 增加 `node_modules` 与 VitePress 构建缓存；本地预览需 `npm run docs:dev`（无 Node 时依赖 CI 部署后在线查看）。
- 站点跟随 main 自动更新：文档改动推送 main 即上线（几分钟生效）；错误同样快速可修。
- 指向被排除内部文档（research/agents）的链接在站点上是死链——构建配置以 `ignoreDeadLinks` 显式容忍，这是有意的取舍。
- 02 指南 / 05 参考内容缺口不阻塞站点上线，另立任务填充。

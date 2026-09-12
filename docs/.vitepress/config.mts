import { defineConfig } from 'vitepress'

// duo-harness 文档站配置：srcDir 即本目录（docs/），中文单语。
// 内部开发文档（agents/research）不上站：srcExclude 排除，
// 指向它们的 markdown 链接在站点上是死链——ignoreDeadLinks 有意容忍（ADR-0005）。
export default defineConfig({
  lang: 'zh-CN',
  title: 'duo-harness',
  description: 'Java 插件化 AI agent harness：内核为自研轻量插件容器，能力以插件组装',
  // GitHub Pages 项目站部署在 /duo-harness/ 子路径下——资源与链接都要带此前缀
  base: '/duo-harness/',
  ignoreDeadLinks: true,
  srcExclude: ['**/agents/**', '**/research/**'],
  themeConfig: {
    siteTitle: 'duo-harness',
    nav: [
      { text: '入门', link: '/01-入门/运行Demo' },
      { text: '架构', link: '/04-架构/模块划分' },
      { text: '已知限制', link: '/limitations' },
      { text: 'ADR', link: '/adr/0001-自研插件容器内核' },
      { text: 'GitHub', link: 'https://github.com/dengdz/duo-harness' }
    ],
    sidebar: [
      {
        text: '入门',
        items: [{ text: '运行 Demo', link: '/01-入门/运行Demo' }]
      },
      {
        text: '架构',
        items: [{ text: '模块划分', link: '/04-架构/模块划分' }]
      },
      {
        text: '决策记录（ADR）',
        items: [
          { text: '0001 自研插件容器内核', link: '/adr/0001-自研插件容器内核' },
          { text: '0002 同步API与虚拟线程', link: '/adr/0002-同步API与虚拟线程' },
          { text: '0003 Jackson统一序列化与配置绑定', link: '/adr/0003-Jackson统一序列化与配置绑定' },
          { text: '0004 三支柱先行路线图', link: '/adr/0004-三支柱先行路线图' },
          { text: '0005 文档站与Pages部署', link: '/adr/0005-文档站VitePress与Pages部署' },
          { text: '0006 MCP接入采用官方JavaSDK', link: '/adr/0006-MCP接入采用官方JavaSDK' },
          { text: '0007 里程碑重排agent循环提前', link: '/adr/0007-里程碑重排agent循环提前' }
        ]
      },
      {
        text: '参考',
        items: [{ text: '已知限制', link: '/limitations' }]
      }
    ],
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一篇', next: '下一篇' },
    lastUpdated: {
      text: '最后更新',
      formatOptions: { formatter: (time: number) => new Date(time).toLocaleString('zh-CN') }
    }
  },
  lastUpdated: true
})

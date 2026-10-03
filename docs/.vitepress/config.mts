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
      { text: '入门', link: '/01-入门/快速开始' },
      { text: '指南', link: '/02-指南/组装你的第一个agent' },
      { text: '高级', link: '/03-高级/技能编写指南' },
      { text: '架构', link: '/04-架构/设计主线' },
      { text: '已知限制', link: '/limitations' },
      { text: 'ADR', link: '/adr/0001-自研插件容器内核' },
      { text: 'GitHub', link: 'https://github.com/dengdz/duo-harness' }
    ],
    sidebar: [
      {
        text: '入门',
        items: [{ text: '快速开始', link: '/01-入门/快速开始' }]
      },
      {
        text: '指南',
        items: [
          { text: '组装你的第一个 agent', link: '/02-指南/组装你的第一个agent' },
          { text: '权限与审批', link: '/02-指南/权限与审批' },
          { text: '会话管理与恢复', link: '/02-指南/会话管理与恢复' },
          { text: '斜杠命令', link: '/02-指南/斜杠命令' },
          { text: '模型与思考档位', link: '/02-指南/模型与思考档位' },
          { text: '导出与检索', link: '/02-指南/导出与检索' },
          { text: '子代理', link: '/02-指南/子代理' },
          { text: '插件中心', link: '/02-指南/插件中心' },
          { text: 'hooks', link: '/02-指南/hooks' },
          { text: 'MCP 接入', link: '/02-指南/MCP接入' }
        ]
      },
      {
        text: '高级',
        items: [
          { text: '技能编写指南', link: '/03-高级/技能编写指南' },
          { text: 'MCP 深入', link: '/03-高级/MCP深入' },
          { text: '多插件协同', link: '/03-高级/多插件协同' },
          { text: '插件包交货指南', link: '/03-高级/插件包交货指南' }
        ]
      },
      {
        text: '架构',
        items: [
          { text: '设计主线', link: '/04-架构/设计主线' },
          { text: '模块划分', link: '/04-架构/模块划分' }
        ]
      },
      {
        text: '参考',
        items: [
          { text: 'config 全量字段参考', link: '/05-参考/config全量字段参考' },
          { text: '插件配置参考', link: '/05-参考/插件配置参考' },
          { text: '插件扩展点清单', link: '/05-参考/插件扩展点清单' },
          { text: 'CLI 参考', link: '/05-参考/CLI参考' },
          { text: 'Web 界面使用说明', link: '/05-参考/Web界面使用说明' },
          { text: '工具目录', link: '/05-参考/工具目录' },
          { text: '术语表', link: '/05-参考/术语表' },
          { text: '技能写作规范', link: '/05-参考/技能写作规范' },
          { text: '会话事件类型表', link: '/05-参考/会话事件类型表' },
          { text: '版本与发布', link: '/05-参考/版本与发布' },
          { text: '已知限制', link: '/limitations' }
        ]
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

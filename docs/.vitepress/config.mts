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
      { text: '指南', link: '/02-指南/组装你的第一个agent' },
      { text: '架构', link: '/04-架构/设计主线' },
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
        text: '指南',
        items: [{ text: '组装你的第一个 agent', link: '/02-指南/组装你的第一个agent' }]
      },
      {
        text: '架构',
        items: [
          { text: '设计主线', link: '/04-架构/设计主线' },
          { text: '模块划分', link: '/04-架构/模块划分' }
        ]
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
          { text: '0007 里程碑重排agent循环提前', link: '/adr/0007-里程碑重排agent循环提前' },
          { text: '0008 交互机制与呈现分离', link: '/adr/0008-交互机制与呈现分离seam与answerer' },
          { text: '0009 治理计量切provider真实usage', link: '/adr/0009-治理计量切provider真实usage' },
          { text: '0010 SSE快照加游标增量回放', link: '/adr/0010-SSE快照加游标增量回放' },
          { text: '0011 CLI插件化呈现位对称与通用启动器', link: '/adr/0011-CLI插件化呈现位对称与通用启动器' },
          { text: '0012 本机工具族与权限预设', link: '/adr/0012-本机工具族与权限预设' },
          { text: '0013 会话尾部窗口快照与分页范式', link: '/adr/0013-会话尾部窗口快照与分页范式' },
          { text: '0014 事件快照读侧零拷贝与单趟窗口', link: '/adr/0014-事件快照读侧零拷贝与单趟窗口' },
          { text: '0015 subagent任务分解', link: '/adr/0015-subagent任务分解' },
          { text: '0016 M16至1.0里程碑规划', link: '/adr/0016-M16至1.0里程碑规划' },
          { text: '0017 子代理知识可见性模板opt-in', link: '/adr/0017-子代理知识可见性模板opt-in' },
          { text: '0018 并发工具调度', link: '/adr/0018-并发工具调度' },
          { text: '0019 扩展机制', link: '/adr/0019-扩展机制' },
          { text: '0020 斜杠命令与运行中治理', link: '/adr/0020-斜杠命令与运行中治理' },
          { text: '0021 web工具族', link: '/adr/0021-web工具族' },
          { text: '0022 输入面与会话工具', link: '/adr/0022-输入面与会话工具' },
          { text: '0023 沙箱出栈与M23+里程碑规划', link: '/adr/0023-沙箱出栈与M23+里程碑规划' },
          { text: '0024 M23至1.0里程碑版图重排', link: '/adr/0024-M23至1.0里程碑版图重排' },
          { text: '0025 M23执行与CLI体验三裁定', link: '/adr/0025-M23执行与CLI体验三裁定' },
          { text: '0026 M24权限与安全深化六裁定', link: '/adr/0026-M24权限与安全深化六裁定' },
          { text: '0027 技能写作规范移植与调用权分流', link: '/adr/0027-技能写作规范移植与调用权分流' }
        ]
      },
      {
        text: '参考',
        items: [
          { text: '插件配置参考', link: '/05-参考/插件配置参考' },
          { text: '工具目录', link: '/05-参考/工具目录' },
          { text: '术语表', link: '/05-参考/术语表' },
          { text: '会话事件类型表', link: '/05-参考/会话事件类型表' },
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

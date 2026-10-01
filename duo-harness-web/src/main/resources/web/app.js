'use strict';
// ===== Web 双面脚本层：§0 基础 → §1 api → §2 render → §3 sse → §4 app =====
// 与 index.html（骨架）、theme.css（样式）分文件维护；以 <script src> 在 body 尾
// 加载（DOM 就绪后执行，选择器不落空）。
'use strict';
// ===== §脚本 · 模块分区：§0 基础 → §1 api → §2 render → §3 sse → §4 app =====

// ----- §0 基础：选择器 + 页面错误可见化（呈现位自身可诊断） -----
const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
const errText = (err) => (err instanceof Error ? err.message : String(err));

// ----- §0.1 鉴权令牌（M24 工单 06，ADR-0026 决策五）-----
// URL 携 token 首载存 localStorage；全部 /api 请求统一经包装补 X-Duo-Token 头，
// SSE（EventSource 不支持自定义头）以 ?token= 查询串携带。
// 取舍记档：token 保留在地址栏（抹除会导致 F5 刷新时文档请求无 token 整页 403）——
// 泄漏面仅本机浏览器历史，个人工具可接受；刷新/收藏请使用带 token 的完整 URL。
// 约定：api 层所有 fetch 用字符串 URL + 纯对象 headers（Headers 实例不被包装展开）。
// localStorage 在隐私模式/禁用存储时会 throw——降级为内存态（当次会话有效）。
const duoToken = (() => {
  const fromUrl = new URLSearchParams(location.search).get('token') || '';
  let stored = '';
  try {
    if (fromUrl) localStorage.setItem('duoToken', fromUrl);
    stored = localStorage.getItem('duoToken') || '';
  } catch (e) { /* 存储不可用：仅本次会话内存态 */ }
  return fromUrl || stored;
})();

// ----- §0.2 标签身份（M24 工单 07）：每标签持久 tabId，服务端按标签绑定会话 -----
// sessionStorage 每浏览器标签一份（localStorage 同源全标签共享——会令所有标签同
// tabId、隔离归零），刷新不丢（F5 保留同一会话）；隐私模式/禁用存储降级为临时 id
// （刷新即新标签，与 token 降级同口径）。新标签（无记录）= 服务端新建会话；服务端
// 重启后旧 tabId 无记录同样新建——"无记录的旧标签等同新标签"。
const duoTabId = (() => {
  try {
    let id = sessionStorage.getItem('duoTabId');
    if (!id) {
      id = (crypto.randomUUID ? crypto.randomUUID()
          : 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10));
      sessionStorage.setItem('duoTabId', id);
    }
    return id;
  } catch (e) {
    return 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  }
})();

const _duoFetch = window.fetch.bind(window);
window.fetch = (url, opts = {}) => {
  if (typeof url === 'string' && url.startsWith('/api/')) {
    opts = { ...opts, headers: { ...(opts.headers || {}) } };
    // 标签身份随全部 /api 请求上报（SSE 走查询串——EventSource 不支持自定义头）
    opts.headers['X-Tab-Id'] = duoTabId;
    if (duoToken) opts.headers['X-Duo-Token'] = duoToken;
  }
  return _duoFetch(url, opts);
};

window.addEventListener('error', (e) => render.assistant('[页面错误] ' + e.message));
window.addEventListener('unhandledrejection', (e) => {
  render.assistant('[未处理异常] ' + errText(e.reason));
});

// 全局提示（toast）：网络/服务不可用等基础设施错误右下角可见，5s 自动消失；
// 执行过程错误仍走对话流错误卡——两类错误呈现位分离
function showToast(text, kind) {
  const box = document.createElement('div');
  box.className = 'toast' + (kind === 'info' ? ' info' : '');
  box.textContent = text;
  $('#toasts').appendChild(box);
  setTimeout(() => box.remove(), 5000);
}

/** hero 问候与示例 chips（M29 W3/W4）：六档时段问候 + 固定三条示例（点击填输入框）。
 * 顶层函数——render IIFE 内的 resetToHero 与启动段共同调用（M29 W3/W4 实测教训：
 * 该函数不依赖 render 内部状态，放 IIFE 内则顶层调用够不着）。 */
function fillHero() {
  const titleEl = $('#heroTitle');
  if (titleEl) {
    const h = new Date().getHours();
    const greeting = h >= 5 && h < 9 ? '早上好'
      : h >= 9 && h < 12 ? '上午好'
        : h >= 12 && h < 14 ? '中午好'
          : h >= 14 && h < 18 ? '下午好'
            : h >= 18 && h < 23 ? '晚上好' : '夜深了';
    titleEl.textContent = greeting + '，有什么想让我帮忙的吗';
  }
  const chips = $('#heroChips');
  if (chips && !chips.children.length) {
    for (const text of ['看看这个仓库的结构', '修复一个 bug 并跑测试', '查一下最近的会话记录']) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'hero-chip';
      b.textContent = text;
      b.addEventListener('click', () => {
        const input = $('#input');
        input.value = text;
        input.focus();
      });
      chips.appendChild(b);
    }
  }
}

// ----- §1 api：后端端点封装 -----
const api = {
  async status() { return (await fetch('/api/status')).json(); },
  async sessions() { return (await fetch('/api/sessions')).json(); },
  async search(q) {
    const res = await fetch('/api/search?q=' + encodeURIComponent(q));
    if (!res.ok) throw new Error((await res.text().catch(() => '')) || ('检索失败（HTTP ' + res.status + '）'));
    return res.json();
  },
  async page(before, sid) {
    // sid = 会话绑定（M26-06 复合游标）：服务端核对游标属于当前会话；
    // 409 = 换绑后在途翻页作废（预期竞态）——返回 null 由调用方静默丢弃，不当错误呈现
    const res = await fetch('/api/session/page?before=' + before + '&sid=' + encodeURIComponent(sid));
    if (res.status === 409) return null;
    if (!res.ok) throw new Error('分页请求失败（HTTP ' + res.status + '）');
    return res.json();
  },
  async sendMessage(text, attachments) {
    return fetch('/api/message', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(attachments && attachments.length ? { text, attachments } : { text })
    });
  },
  // 协作式中断（M23 工单 02）：与 CLI /stop、Ctrl+C 单击同语义——已流出内容保留，
  // 会话停可恢复态，再发消息即续接
  async stop() {
    return fetch('/api/stop', { method: 'POST' });
  },
  // 附件上传（M21 工单 04）：文件 → base64 → 入库，返回引用元数据（发送时随消息提交）
  async uploadAttachment(file) {
    const data = await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
      reader.onerror = () => reject(new Error('读取文件失败'));
      reader.readAsDataURL(file);
    });
    const res = await fetch('/api/attachment/upload', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ data, name: file.name })
    });
    if (!res.ok) throw new Error((await res.text().catch(() => '')) || ('HTTP ' + res.status));
    return res.json();
  },
  // 结构化回答协议（M16 工单 07）：审批 {decision}，提问/计划 {answers}——两形态互斥，
  // 服务端不做字符串嗅探（自由文本里的"拒绝"是普通回答，不是判定语义）
  async answer(payload) {
    const res = await fetch('/api/answer', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    return res.json();
  }
};

// ----- §1.5 todoPanel：输入框上方常驻折叠面板（ADR-0018）-----
// 数据源是 todo/write 事件（与后端 Session.todoProjection 同语义）：整表替换渲染；
// 新 user/message 清空（上一轮清单使命结束）、终版回复后保留（读完答案还能看到）。
// 空清单不渲染（hidden）；回放经 dispatch 单源分发自然重建终态。
const todoPanel = (() => {
  const el = $('#todoPanel');

  function parse(todosJson) {
    try {
      const todos = JSON.parse(todosJson || '[]');
      return Array.isArray(todos) ? todos : [];
    } catch (e) {
      return [];
    }
  }

  function summaryText(todos) {
    const done = todos.filter(t => t.status === 'completed').length;
    const active = todos.filter(t => t.status === 'in_progress').map(t => t.content);
    return done + '/' + todos.length + ' 已完成'
        + (active.length ? ' · ' + active.join('；') : '');
  }

  function update(todosJson) {
    const todos = parse(todosJson);
    if (todos.length === 0) {
      clear();
      return;
    }
    const details = document.createElement('details');
    details.className = 'todo-fold';
    const summary = document.createElement('summary');
    summary.textContent = '任务清单 · ' + summaryText(todos);
    const list = document.createElement('ul');
    list.className = 'todo-list';
    for (const item of todos) {
      const li = document.createElement('li');
      li.dataset.status = item.status || 'pending';
      const mark = document.createElement('span');
      mark.className = 'todo-mark';
      const label = document.createElement('span');
      label.textContent = item.content || '';
      li.append(mark, label);
      list.appendChild(li);
    }
    details.append(summary, list);
    el.innerHTML = '';
    el.appendChild(details);
    el.hidden = false;
  }

  function clear() {
    el.hidden = true;
    el.innerHTML = '';
  }

  return { update, clear, summaryText };
})();

// ----- §2 render：事件 → 卡片（9 种形态，对齐 prototype.html） -----
const render = (() => {
  const hero = $('#hero');
  /**
   * 渲染目标（M15 工单 05）：一套 DOM 容器 + 一组渲染状态——主对话与子任务抽屉
   * 各持一份，共用下面全部渲染函数（子 agent 与主 agent 的呈现形态一致）。
   */
  function makeTarget(container, isMain) {
    return {
      container, isMain,
      // 打开的工具卡：toolCallId → 卡元素（tool/result 回填同一张卡）
      toolCards: new Map(),
      // 子任务卡：子代理 id → 卡元素（completed 回填状态与结果）
      subagentCards: new Map(),
      // 尚无 toolCallId 的最近工具卡（兜底匹配下一条 result）
      lastOpenToolCard: null,
      lastOpenToolName: null,
      streamingBubble: null,
    };
  }

  const main = makeTarget($('#messages'), true);
  /** 当前渲染目标（同步切换：回放整批事件期间指向抽屉目标，其余时刻是主对话）。 */
  let t = main;

  /** 在指定目标上同步执行渲染（渲染函数读模块级 t；切换是同步的，无并发歧义）。 */
  function onTarget(target, fn) {
    const prev = t;
    t = target;
    try {
      return fn();
    } finally {
      t = prev;
    }
  }

  // 滚动合并到动画帧：回放/流式可瞬间到达数百帧 chunk，逐帧强制 reflow 会卡顿
  let scrollQueued = false;
  const scroll = () => {
    if (scrollQueued) return;
    scrollQueued = true;
    requestAnimationFrame(() => {
      scrollQueued = false;
      t.container.scrollTop = t.container.scrollHeight;
    });
  };

  function showMessages() {
    if (t.isMain && hero.style.display !== 'none') {
      hero.style.display = 'none';
      t.container.style.display = 'block';
    }
  }

  function user(text) {
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg user';
    const b = document.createElement('div');
    b.className = 'bubble';
    b.textContent = text;
    div.appendChild(b);
    t.container.appendChild(div);
    scroll();
  }

  function userImage(src, alt) {
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg user';
    const img = document.createElement('img');
    img.className = 'att-image';
    img.src = src;
    img.alt = alt || '附件图片';
    div.appendChild(img);
    t.container.appendChild(div);
    scroll();
  }

  function assistant(text) {
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.textContent = text;
    t.container.appendChild(div);
    scroll();
    return div;
  }

  /** 流式聚合（打字机指针，M29 工单 06 验收裁定）：chunks 全速进 buffer，渲染端
   * rAF 恒速推进已显示指针——积压越多推进越快（追平防堆积）、无积压则恒速约
   * 60 字/秒，观感为逐字细流；markdown 对指针前缀节流渲染（≥60ms 一次），收口
   * finishAssistant 整段定稿。数据源 SSE chunk 本身成批到达，指针把批次平滑成流。 */
  function chunk(text) {
    showMessages();
    if (!t.streamingBubble) {
      t.streamingBubble = document.createElement('div');
      t.streamingBubble.className = 'msg assistant streaming';
      t.container.appendChild(t.streamingBubble);
    }
    t.chunkBuffer = (t.chunkBuffer || '') + text;
    if (!t.typeRAF) {
      t.typeShown = 0;
      t.typeLastParse = 0;
      t.typeRAF = requestAnimationFrame(typeTick);
    }
  }

  /** 打字机帧推进：指针恒速/自适应前进，前缀节流渲染。 */
  function typeTick(now) {
    if (!t.streamingBubble) { t.typeRAF = null; return; }
    const backlog = (t.chunkBuffer || '').length - t.typeShown;
    if (backlog > 0) {
      // 自适应步长：积压小则逐字细流，积压大则加速消化（≥1/12 积压量，收敛防延迟）
      t.typeShown += Math.min(backlog, Math.max(1, Math.ceil(backlog / 12)));
      if (!t.typeLastParse || now - t.typeLastParse >= 60) {
        t.typeLastParse = now;
        t.streamingBubble.innerHTML = '';
        // 流式期逐步上色（M29 工单 08 用户验收定稿）：指针前缀直接跑高亮——关键字
        // 长出来即变色；中小代码块无感，数千行级长输出若卡顿可回退收口高亮
        t.streamingBubble.appendChild(renderMarkdown((t.chunkBuffer || '').slice(0, t.typeShown), true));
        scroll();
      }
    }
    t.typeRAF = requestAnimationFrame(typeTick);
  }

  /**
   * Markdown 渲染体（模型回复专用）：marked 解析 → DOMPurify 消毒，顺序不可换——
   * 消毒必须作用于解析后的 HTML。系统/错误消息不走此路（保持 textContent）。
   * vendor 库缺失时降级纯文本：渲染增强不可用不阻断对话。
   * highlightCode=true 时对代码块跑 highlight.js（M29 工单 08：定稿/思考卡传入，
   * 打字机流式期不传——流式期代码块纯等宽、收口上色，ZCode 同款时序，防长代码块
   * 每 60ms 重高亮卡顿）；hljs 缺失时跳过高亮，同样不阻断。
   */
  function renderMarkdown(text, highlightCode) {
    const body = document.createElement('div');
    body.className = 'md-body';
    if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') {
      body.textContent = text;
      return body;
    }
    body.innerHTML = DOMPurify.sanitize(marked.parse(text));
    if (highlightCode && typeof hljs !== 'undefined') {
      body.querySelectorAll('pre code').forEach(el => {
        try { hljs.highlightElement(el); } catch (e) { /* 单块高亮失败不阻断渲染 */ }
      });
    }
    return body;
  }

  /** 流式正文气泡封段（M29 工单 12 执行序修复）：一轮文本结束（工具调用出现）时把
   *  当前气泡就地定格为独立叙述段——下一轮 chunk 从当前位置新建气泡，工具卡不再被
   *  顶部气泡压住。多轮任务的叙述-行动-叙述-行动-正文按真实执行序呈现。 */
  function sealStreamingBubble() {
    if (!t.streamingBubble) return;
    if (t.typeRAF) { cancelAnimationFrame(t.typeRAF); t.typeRAF = null; }
    const bubble = t.streamingBubble;
    const text = t.chunkBuffer || '';
    if (text.trim()) {
      bubble.classList.remove('streaming');
      bubble.innerHTML = '';
      bubble.appendChild(renderMarkdown(text, true));
    } else {
      bubble.remove();
    }
    t.streamingBubble = null;
    t.chunkBuffer = '';
    t.typeShown = 0;
    t.typeLastParse = 0;
  }

  function finishAssistant(text, reasoning) {
    showMessages();
    // 打字机循环清场（正文与思考两条指针）：收口整段渲染为定稿（指针态作废）
    if (t.typeRAF) { cancelAnimationFrame(t.typeRAF); t.typeRAF = null; }
    if (t.reasoningRAF) { cancelAnimationFrame(t.reasoningRAF); t.reasoningRAF = null; }
    t.chunkBuffer = '';
    t.reasoningBuffer = '';
    // 流式期实时呈现的思考卡定稿：移除滚动卡，换收口折叠卡（默认收起，markdown 渲染）
    if (t.reasoningStreamCard) { t.reasoningStreamCard.remove(); t.reasoningStreamCard = null; }
    // 流式期间保持纯文本追加（半截 markdown 渲染会闪烁），assistant/message 收口时整段渲染；
    // 历史回放无对应 chunk 流，同样走此分支
    let bubble;
    if (t.streamingBubble) {
      bubble = t.streamingBubble;
      bubble.classList.remove('streaming');
      bubble.textContent = '';
      t.streamingBubble = null;
    } else {
      bubble = document.createElement('div');
      bubble.className = 'msg assistant';
      t.container.appendChild(bubble);
    }
    // 思考折叠卡在正文气泡之前（时序语义：先思考后回答）；非思考会话 reasoning
    // 缺席不建卡——非思考模型零变化
    if (reasoning) bubble.before(reasoningCard(reasoning));
    bubble.appendChild(renderMarkdown(text, true));
    // 产物预览卡（M29 工单 12 用户裁定，present 退役后的接棒形态；ZCode
    // AssistantPreviewCards 同语义）：回复定稿时从文本自动提取产物型文件路径成卡
    const previews = extractPreviewPaths(text);
    if (previews.length) {
      const group = document.createElement('div');
      group.className = 'preview-cards';
      for (const p of previews) group.appendChild(filePreviewRow(p));
      bubble.after(group);
    }
    scroll();
  }

  /** 产物路径提取（ZCode conversation-preview-artifacts 简化版）：回复文本中的
   *  产物型扩展名路径（md/html/office/pdf/图片/音视频）→ 去重保序，上限 10 张
   *  （ZCode 可见上限同值）；无 stat 校验——提取自刚定稿的回复，模型刚写过这些文件。 */
  function extractPreviewPaths(text) {
    const re = /(?:^|[\s`"'(\[（【,，、；;：:]|[^\w./-])(\/?(?:[\p{L}\p{N}@._\-]+\/)*[\p{L}\p{N}@._\-]+\.(?:md|markdown|html?|docx?|xlsx?|pptx?|pdf|png|jpe?g|gif|svg|webp|mp4|mov|webm|mp3|wav|csv|json))(?=$|[\s`"')\]）】,，。！？；:!?])/gimu;
    const seen = new Set();
    const out = [];
    for (const m of (text || '').matchAll(re)) {
      let p = m[1];
      // 剥 URL host（https://host/path/x.pdf 会连 host 提取——M29 审查）
      if (/^[a-z]+:\/\//i.test(p)) p = p.replace(/^[a-z]+:\/\/[^/]+\//i, '/');
      if (!p || seen.has(p) || p.length < 4 || p.length > 256) continue;
      // 排除代码围栏语言标注（```java）与域名（example.com 无路径斜杠且不在产物扩展白名单内的情况已被正则挡）
      seen.add(p);
      out.push(p);
      if (out.length >= 10) break;
    }
    return out;
  }

  /** 思考内容剥壳（M29 工单 12 自测修复）：anthropic 形态的 reasoning 是带 signature
   *  的 JSON 信封（{"type":"thinking","thinking":"…"}，回传协议要求）——展示层剥出
   *  纯思考文本；非 JSON（deepseek-reasoner 纯文本）原样返回，坏 JSON 兜底原文。 */
  function reasoningDisplayText(reasoning) {
    if (reasoning && reasoning.startsWith('{')) {
      try {
        const parsed = JSON.parse(reasoning);
        if (parsed && typeof parsed.thinking === 'string') return parsed.thinking;
      } catch (e) { /* 坏 JSON 走原文 */ }
    }
    return reasoning;
  }

  /**
   * 思考折叠卡（M29 工单 06）：原生 details 零依赖、默认收起；内容走与正文同
   * 一 markdown 管线（完成态一次性渲染，无流式重解析成本——ZCode 纯文本是
   * 流式性能决策，duo 完成态不必照搬，用户验收裁定格式化）。
   */
  function reasoningCard(reasoning) {
    const card = document.createElement('div');
    card.className = 'msg reasoning-card';
    const details = document.createElement('details');
    details.className = 'reasoning';
    const summary = document.createElement('summary');
    summary.textContent = '💭 思考';
    const body = document.createElement('div');
    body.className = 'reasoning-body';
    body.appendChild(renderMarkdown(reasoningDisplayText(reasoning), true));
    details.append(summary, body);
    card.appendChild(details);
    return card;
  }

  /** 文件类型描述（成果卡副标题与图标用；扩展名 → 图标 + 中文名）。 */
  function fileTypeDescriptor(path) {
    const leaf = path.split('/').pop() || path;
    const ext = (leaf.includes('.') ? leaf.split('.').pop() : '').toLowerCase();
    const table = {
      md: ['📝', 'Markdown'], json: ['🧾', 'JSON'], html: ['🌐', 'HTML'], htm: ['🌐', 'HTML'],
      css: ['🎨', 'CSS'], js: ['📜', 'JavaScript'], ts: ['📜', 'TypeScript'],
      yml: ['⚙️', 'YAML'], yaml: ['⚙️', 'YAML'], xml: ['🧾', 'XML'],
      png: ['🖼️', '图片'], jpg: ['🖼️', '图片'], jpeg: ['🖼️', '图片'], gif: ['🖼️', '图片'], svg: ['🖼️', 'SVG'],
      txt: ['📄', '文本'], pdf: ['📕', 'PDF'],
    };
    const [icon, label] = table[ext] || ['📄', ext ? ext.toUpperCase() + ' 文件' : '文件'];
    const dir = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) || '/' : '.';
    return { leaf, icon, label, dir };
  }

  /** ZCode 式文件预览行（AssistantPreviewCards 形态）：图标底座 + 文件名/副标题 + 复制按钮。 */
  function filePreviewRow(path) {
    const desc = fileTypeDescriptor(path);
    const row = document.createElement('div');
    row.className = 'file-preview-card';
    const iconBox = document.createElement('div');
    iconBox.className = 'file-icon-box';
    iconBox.textContent = desc.icon;
    const main = document.createElement('div');
    main.className = 'file-preview-main';
    const title = document.createElement('div');
    title.className = 'file-preview-title';
    title.textContent = desc.leaf;
    const sub = document.createElement('div');
    sub.className = 'file-preview-sub';
    sub.textContent = desc.label + ' · ' + desc.dir;
    main.append(title, sub);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'file-copy-btn';
    btn.dataset.action = 'copy-path';
    btn.dataset.path = path;
    btn.title = '复制完整路径：' + path;
    btn.textContent = '复制路径';
    row.append(iconBox, main, btn);
    return row;
  }

  /** 思考流式卡（M29 工单 06）：增量进 buffer，与正文同款打字机指针匀速流出——
   * 增量事件是 256 字符窗口聚合（成批到达），直追加即成批蹦；展开态纯文本
   * （增量半截 markdown 渲染会闪），收口 assistant/message 到达时移除换定稿
   * 折叠卡；回放不走此路（replaying 分支跳过，回放由 message.reasoning 一次渲染）。 */
  /** 思考流式卡定稿（M29 工单 12 顺序修复）：一轮思考结束（工具调用出现/正文收口/中断）
   *  时把展开滚动卡就地转为收起折叠卡留在原位——多轮工具循环中每轮思考卡跟该轮工具卡走，
   *  不跨轮粘连（下一轮思考建新卡）。 */
  function finalizeReasoningStream() {
    if (!t.reasoningStreamCard) return;
    if (t.reasoningRAF) { cancelAnimationFrame(t.reasoningRAF); t.reasoningRAF = null; }
    // 打字机积压补齐：定格即全文（轮次封段时 buffer 可能还有未流出字符——回放同步
    // 批量分发时增量全在 buffer，不补齐则卡内容全空，自测实证）
    if (t.reasoningStreamBody && t.reasoningBuffer) {
      t.reasoningStreamBody.textContent = t.reasoningBuffer;
    }
    const details = t.reasoningStreamCard.querySelector('details.reasoning');
    if (details) {
      details.open = false;
      const summary = details.querySelector('summary');
      if (summary) summary.textContent = '💭 思考';
    }
    t.reasoningStreamCard = null;
    t.reasoningStreamBody = null;
    t.reasoningBuffer = '';
    t.reasoningShown = 0;
  }

  function reasoningStream(delta) {
    showMessages();
    if (!t.reasoningStreamCard) {
      const details = document.createElement('details');
      details.className = 'reasoning';
      details.open = true;
      const summary = document.createElement('summary');
      summary.textContent = '💭 思考中…';
      const body = document.createElement('div');
      body.className = 'reasoning-body';
      details.append(summary, body);
      const card = document.createElement('div');
      card.className = 'msg reasoning-card';
      card.appendChild(details);
      t.reasoningStreamCard = card;
      t.reasoningStreamBody = body;
      t.reasoningBuffer = '';
      // 流式思考卡在正文流式气泡之前（时序语义：先思考后回答）
      if (t.streamingBubble) t.streamingBubble.before(card);
      else t.container.appendChild(card);
      if (!t.reasoningRAF) {
        t.reasoningShown = 0;
        t.reasoningRAF = requestAnimationFrame(reasoningTick);
      }
    }
    t.reasoningBuffer += delta;
  }

  /** 思考打字机帧推进：指针匀速/自适应前进（与正文 typeTick 同参数语义），吸底跟随。 */
  function reasoningTick() {
    if (!t.reasoningStreamCard) { t.reasoningRAF = null; return; }
    const backlog = (t.reasoningBuffer || '').length - t.reasoningShown;
    if (backlog > 0) {
      t.reasoningShown += Math.min(backlog, Math.max(1, Math.ceil(backlog / 12)));
      t.reasoningStreamBody.textContent = (t.reasoningBuffer || '').slice(0, t.reasoningShown);
      t.reasoningStreamBody.scrollTop = t.reasoningStreamBody.scrollHeight;
      scroll();
    }
    t.reasoningRAF = requestAnimationFrame(reasoningTick);
  }

  /** 线性 SVG 图标（M29 工单 12 ZCode 同款：lucide path 与各渲染器真实分配对齐——
   *  读/搜一族共用放大镜、未识别统一扳手，语义族共用是 ZCode 原生哲学；name → path，size 默认 16）。 */
  const ICON_PATHS = {
    terminal: '<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16v-4a4 4 0 0 1 4-4"/><path d="m9 13 2 2-2 2"/>', // execute: SquareTerminalIcon
    search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>', // read/search/explore 共用: SearchIcon
    file: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/>', // write 文件卡: FileIcon
    edit: '<path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/>', // edit: PencilIcon
    list: '<rect x="3" y="5" width="6" height="6" rx="1"/><path d="m3 17 2 2 4-4"/><path d="M13 6h8"/><path d="M13 12h8"/><path d="M13 18h8"/>', // todo: ListTodoIcon
    skill: '<path d="m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72"/><path d="m14 7 3 3"/><path d="M5 6v4"/><path d="M19 14v4"/><path d="M10 2v2"/><path d="M7 8H3"/><path d="M21 16h-4"/><path d="M11 3H9"/>', // skill: WandSparkles
    lightbulb: '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>', // ask-question: CircleHelpIcon
    book: '<path d="M12 7v14"/><path d="M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z"/><path d="M12 7a4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6"/>', // read-session-context: BookOpenTextIcon
    sparkles: '<path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"/><path d="M20 3v4"/><path d="M22 5h-4"/><path d="M4 17v2"/><path d="M5 18H3"/>', // 记忆写入：ZCode 无对应，保留（skill 让出后不撞车）
    globe: '<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"/><path d="M2 12h20"/>', // web_fetch：ZCode 网站卡 GlobeIcon 同款
    'file-output': '<path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M4 22h14a2 2 0 0 0 2-2V7l-5-5H6a2 2 0 0 0-2 2v4"/><path d="M3 15h6"/><path d="M6 12v6"/>', // task-output: FileOutputIcon
    'circle-stop': '<circle cx="12" cy="12" r="10"/><rect x="9" y="9" width="6" height="6" rx="1"/>', // task-stop: CircleStopIcon
    bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
    wrench: '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>', // fallback: WrenchIcon（未识别语义）
    chevron: '<path d="m9 18 6-6-6-6"/>',
    'circle-check': '<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>',
    'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
    circle: '<circle cx="12" cy="12" r="10"/>',
  };
  /** 工具图标分配（ZCode 渲染器对齐：读/搜一族共用放大镜；未识别走 wrench 兜底）。 */
  const TOOL_ICONS = {
    bash: 'terminal', read: 'search', write: 'file', edit: 'edit',
    glob: 'search', grep: 'search', session_search: 'book',
    memory_write: 'sparkles', tool_stats: 'wrench', skill: 'skill',
    web_fetch: 'globe', todo_write: 'list', task_output: 'file-output',
    task_stop: 'circle-stop', read_image: 'search',
  };
  function toolIcon(name) {
    return TOOL_ICONS[name] || 'wrench';
  }
  /** 工具中文名映射（M29 工单 12 用户裁定：每类工具有对应中文名；未映射回退原名）。 */
  const TOOL_LABELS = {
    bash: '终端', read: '读取', write: '写入', edit: '编辑',
    glob: '查找文件', grep: '搜索内容', session_search: '会话搜索',
    memory_write: '记忆写入', tool_stats: '工具统计', skill: '加载技能',
    web_fetch: '抓取网页', todo_write: '任务清单',
    ask_user: '提问', task_output: '任务输出', task_stop: '停止任务',
    read_image: '查看图片', exit_plan_mode: '计划呈交',
  };
  function toolLabel(name) {
    return TOOL_LABELS[name] || name;
  }

  function icon(name, size) {
    const s = size || 16;
    return '<svg class="ticon" width="' + s + '" height="' + s + '" viewBox="0 0 24 24" fill="none"'
      + ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
      + (ICON_PATHS[name] || ICON_PATHS.wrench) + '</svg>';
  }

  /** 参数摘要（M29 W5 + 工单 12 段结构重设计）：常见工具的关键参数一行摘要；
   *  其余回退 null（通用卡展示摘要行 + 参数原文折叠）。 */
  function paramSummary(toolName, argsJson) {
    try {
      const a = JSON.parse(argsJson || '{}');
      if ((toolName === 'write' || toolName === 'edit' || toolName === 'read') && a.path) return String(a.path);
      if (toolName === 'bash' && a.command) return String(a.command);
      if (toolName === 'glob' && a.pattern) return String(a.pattern);
      if (toolName === 'grep' && (a.query || a.pattern)) return String(a.query || a.pattern);
      if (toolName === 'web_fetch' && a.url) return String(a.url);
      if (toolName === 'memory_write' && a.content) return String(a.content);
      if (toolName === 'session_search' && (a.query || a.keyword)) return String(a.query || a.keyword);
    } catch (e) { /* 回退原 JSON */ }
    return null;
  }

  /** 分型卡公共骨架 v3（M29 工单 12 用户裁定：无卡片边框，融入对话流——ZCode
   *  ToolLayout 原样：收起态就是一行摘要，无边框无背景；主体内容盒自绘边框）。
   *  HITL 交互卡与成果预览大卡保持有框（ZCode 同款分工）。 */
  function typedCardShell(opts) {
    showMessages();
    const card = document.createElement('div');
    card.className = 'tcard';
    const row = document.createElement('div');
    row.className = 'tcard-row';
    // ZCode ToolLayout 同款（ToolLayout.tsx:139-147 设计注释）：运行态不用 spinner 徽标——
    // 类别词挂扫光；行尾常驻状态槽，成功留空（报忧不报喜），失败填点线「执行失败」
    row.innerHTML = icon(opts.icon) + '<span class="tcard-label sweep"></span>'
      + '<span class="tcard-primary"></span>'
      + '<span class="tcard-status"></span>'
      + '<span class="tcard-chevron">' + icon('chevron', 14) + '</span>';
    row.querySelector('.tcard-label').textContent = opts.label;
    row.querySelector('.tcard-primary').textContent = opts.primary || '';
    if (opts.secondary) {
      const sec = document.createElement('span');
      sec.className = 'tcard-secondary';
      sec.textContent = opts.secondary;
      sec.title = opts.secondary;
      row.insertBefore(sec, row.querySelector('.tcard-status'));
    }
    if (opts.badgeHolder) {
      opts.badgeHolder.classList.add('tcard-data-badge');
      row.insertBefore(opts.badgeHolder, row.querySelector('.tcard-status'));
    }
    const body = document.createElement('div');
    body.className = 'tcard-body';
    body.style.display = opts.open ? 'block' : 'none';
    card.append(row, body);
    row.addEventListener('click', (e) => {
      if (card.classList.contains('error')) return; // 失败卡不展开（错误全文走悬停浮窗，用户裁定）
      if (e.target.closest('.tcard-status, .badge, [data-action]')) return; // 状态词（失败浮窗锚点）与按钮不吃行开合
      const open = body.style.display === 'block';
      body.style.display = open ? 'none' : 'block';
      card.classList.toggle('open', !open);
    });
    return { card, row, body };
  }

  function mountTypedCard(card, event) {
    if (event.toolCallId) {
      t.toolCards.set(event.toolCallId, card);
    }
    t.container.appendChild(card);
    scroll();
  }

  /** 通用工具卡（未分型工具回退，M29 工单 12 段结构重设计）：🔧 工具名 + 参数摘要行
   *  （paramSummary 命中即一行关键参数，pattern/query/url 等）+ 参数原文折叠 + 结果折叠。 */
  function toolCall(event) {
    // 行 = 图标 + 中文工具名 + 参数摘要（paramSummary 命中即 url/query/pattern 等上主信息）；
    // 摘要未命中才在展开体放参数原文盒（复杂参数全量 JSON）；空参数（{}）不渲染
    const summary = paramSummary(event.toolName, event.text);
    const { card, body } = typedCardShell({
      icon: toolIcon(event.toolName), label: toolLabel(event.toolName),
      primary: summary || '',
    });
    const rawText = (event.text || '').trim();
    if (!summary && rawText && rawText !== '{}') {
      const raw = document.createElement('div');
      raw.className = 'param-raw';
      raw.textContent = event.text || '';
      body.appendChild(raw);
    }
    if (event.toolCallId) {
      t.toolCards.set(event.toolCallId, card);
    }
    t.container.appendChild(card);
    scroll();
    return card;
  }

  /** bash 终端卡（M29 工单 12 终态）：行摘要即命令（不重复建命令盒），输出由 tool/result 填展开体。 */
  function terminalCard(event) {
    const { card } = typedCardShell({
      icon: 'terminal', label: '终端',
      primary: paramSummary('bash', event.text) || '',
    });
    mountTypedCard(card, event);
  }

  /** edit 行级 diff 卡（M29 工单 12 行式化）：编辑 · 仓库相对路径 + diff 徽标 +
   *  行级 ± 着色展开体（朴素逐行对比，240px 内滚——ZCode max-h-60 对齐）。 */
  function editCard(event) {
    let path = '', oldS = '', newS = '';
    try {
      const a = JSON.parse(event.text || '{}');
      path = String(a.path || '');
      oldS = a.old_string == null ? '' : String(a.old_string);
      newS = a.new_string == null ? '' : String(a.new_string);
    } catch (e) { /* 回退通用卡 */ }
    if (!path) {
      toolCall(event);
      return;
    }
    const desc = fileTypeDescriptor(path);
    const oldLines = oldS ? oldS.split('\n') : [];
    const newLines = newS ? newS.split('\n') : [];
    const badgeHolder = document.createElement('span');
    badgeHolder.className = 'diff-count';
    badgeHolder.innerHTML = '<span class="add-count"></span> <span class="del-count"></span>';
    badgeHolder.querySelector('.add-count').textContent = '+' + newLines.length;
    badgeHolder.querySelector('.del-count').textContent = '-' + oldLines.length;
    // 主摘要 = 仓库相对路径全名（用户裁定：直观可见是哪个文件），副信息 = 类型
    const { card, body } = typedCardShell({
      icon: 'edit', label: '编辑',
      primary: path, secondary: desc.label, badgeHolder,
    });
    const diffBody = document.createElement('div');
    diffBody.className = 'diff-body';
    const addLine = (cls, text) => {
      const line = document.createElement('div');
      line.className = 'diff-line ' + cls;
      line.dataset.sign = cls === 'del' ? '-' : '+';
      line.textContent = text;
      diffBody.appendChild(line);
    };
    for (const l of oldLines) addLine('del', l);
    for (const l of newLines) addLine('add', l);
    body.appendChild(diffBody);
    mountTypedCard(card, event);
  }

  /** memory_write 记忆卡（M29 工单 12 用户裁定）：主行只工具名，写入的记忆内容进展开体。 */
  function memoryCard(event) {
    let content = '';
    try {
      content = String(JSON.parse(event.text || '{}').content || '');
    } catch (e) { /* 回退通用卡 */ }
    if (!content) {
      toolCall(event);
      return;
    }
    const { card, body } = typedCardShell({
      icon: 'sparkles', label: '记忆写入',
    });
    const box = document.createElement('div');
    box.className = 'result-inbody';
    box.appendChild(renderMarkdown(content, true)); // 记忆内容按 markdown 渲染，与正文排版一致
    body.appendChild(box);
    mountTypedCard(card, event);
  }

  /** write/read 文件卡（M29 工单 12）：写入/读取 · 仓库相对路径 + 类型副信息。
   *  write 展开体展示写入的内容（content 参数）；read 内容由 tool/result 填充。 */
  function fileCard(event) {
    let path = '', content = '';
    try {
      const a = JSON.parse(event.text || '{}');
      path = String(a.path || '');
      content = a.content == null ? '' : String(a.content);
    } catch (e) { /* 回退通用卡 */ }
    if (!path) {
      toolCall(event);
      return;
    }
    const isWrite = event.toolName === 'write';
    const desc = fileTypeDescriptor(path);
    const { card, body } = typedCardShell({
      icon: isWrite ? 'file' : 'search', label: isWrite ? '写入' : '读取', // ZCode：read→SearchIcon
      primary: path, secondary: desc.label,
    });
    if (isWrite && content) {
      const preview = document.createElement('div');
      preview.className = 'result-inbody'; // 文件内容同款干净代码块直出
      const pre = document.createElement('pre');
      pre.textContent = content;
      preview.appendChild(pre);
      body.appendChild(preview);
    }
    mountTypedCard(card, event);
  }

  /** skill 技能卡（M29 工单 12 行式化）：技能 · 名 · 参数 + 加载内容 100px 直出预览
   *  （toolResult 对 skill 特判填充——ZCode max-h-25 形态）。 */
  function skillCard(event) {
    let name = '', args = '';
    try {
      const a = JSON.parse(event.text || '{}');
      name = String(a.name || a.skill || '');
      args = a.args == null ? (a.arg == null ? '' : String(a.args)) : String(a.args);
    } catch (e) { /* 回退通用卡 */ }
    if (!name) {
      toolCall(event);
      return;
    }
    const { card, body } = typedCardShell({
      icon: 'skill', label: '加载技能',
      primary: name, secondary: args ? '参数 ' + args : '',
    });
    const preview = document.createElement('div');
    preview.className = 'skill-preview';
    preview.textContent = '加载中…';
    preview.hidden = true; // 结果到达后由 toolResult 特判填充并显示
    body.appendChild(preview); // 内容进展开体（用户裁定：点「加载技能」行才展开 SKILL 内容）
    mountTypedCard(card, event);
  }

  /** todo_write 工具行摘要卡（ADR-0018）：头行给 done/total 与活动任务，结果回填走通用路径。 */
  function todoCard(event) {
    showMessages();
    let todos = [];
    try {
      // tool/call 的 text 是参数对象 {"todos":[...]}；todo/write 事件的 text 才是裸数组
      const parsed = JSON.parse(event.text || '{}');
      if (Array.isArray(parsed)) todos = parsed;
      else if (parsed && Array.isArray(parsed.todos)) todos = parsed.todos;
    } catch (e) { /* 参数非法按空清单渲染，徽标由结果路径定夺 */ }
    const card = document.createElement('div');
    card.className = 'tcard'; // M29 工单 12 无框化：融入对话流（ZCode todo 行卡同款）
    const row = document.createElement('div');
    row.className = 'tcard-row'; // 点击行开合清单（M29 工单 12 用户裁定：对话中的清单可展开）
    row.innerHTML = icon('list') + '<span class="tcard-label sweep">任务清单</span>'
      + '<span class="todo-inline"></span>'
      + '<span class="tcard-status"></span>'
      + '<span class="tcard-chevron">' + icon('chevron', 14) + '</span>';
    row.querySelector('.todo-inline').textContent = todos.length ? todoPanel.summaryText(todos) : '';
    const list = document.createElement('ul');
    list.className = 'todo-list';
    list.style.display = 'none'; // 内联初始化（首击判定读内联——M29 审查首击无响应修复）
    card.append(row, list);
    row.addEventListener('click', (e) => {
      if (card.classList.contains('error')) return; // 失败卡不展开（同 typedCardShell 口径）
      if (e.target.closest('.tcard-status')) return;
      const open = list.style.display !== 'none';
      list.style.display = open ? 'none' : 'block';
      card.classList.toggle('open', !open);
    });
    if (todos.length) {
      const todoList = document.createElement('div');
      todoList.className = 'todo-list-body';
      for (const td of todos) {
        const li = document.createElement('div');
        const st = td.status === 'completed' ? 'completed' : (td.status === 'in_progress' ? 'in_progress' : 'pending');
        // ZCode todo.tsx:17-45 同款语义：完成绿勾图标+文字划线变浅、进行中静态右箭头
        // （源注释：避免与加载动画语义混淆）、待办空心圈
        li.className = 'todo-item ' + st;
        li.innerHTML = icon(st === 'completed' ? 'circle-check' : (st === 'in_progress' ? 'arrow-right' : 'circle'), 14)
          + '<span class="todo-item-text"></span>';
        li.querySelector('.todo-item-text').textContent = td.title || td.content || '';
        todoList.appendChild(li);
      }
      list.appendChild(todoList);
    }
    if (event.toolCallId) {
      t.toolCards.set(event.toolCallId, card);
    }
    t.lastOpenToolCard = card;
    t.lastOpenToolName = 'todo_write';
    t.container.appendChild(card);
    scroll();
    return card;
  }

  // ask_user 的结果即回答文本：冻结其提问卡，不再渲染普通工具卡
  function toolResult(event) {
    if (event.toolName === 'ask_user') {
      resolveInteractionDock(); // dock 待答卡移入消息流（M29 工单 12 停靠模型）
      // 回放场景（无 dock 卡可冻结）：补 ask_user 冻结摘要行——问题从最近的
      // question/requested 事件取，回答从本 result 的 text 取（M29 工单 12 用户裁定）
      // 回放场景：优先按 toolCallId 找回 call 阶段冻结的问题卡，就地补回答行（合一呈现）
      const frozen = event.toolCallId && t.toolCards.get(event.toolCallId);
      if (frozen) {
        const a = document.createElement('div');
        a.className = 'ask-frozen-a';
        a.textContent = (event.text || '').trim();
        frozen.querySelector('.tcard-body').appendChild(a);
        t.toolCards.delete(event.toolCallId);
        return;
      }
      if (!t.container.querySelector('.interactive[data-tool-name="ask_user"]')
          && !t.container.querySelector('.ask-frozen')) {
        askFrozenLine('（提问）', (event.text || '').trim()); // 无卡可冻结的兜底（正常路径 dock 卡已注册按 id 找回）
      } else {
        resolveByToolName('ask_user', '✓ 已回答：' + (event.text || '').trim(), true);
      }
      return;
    }
    // exit_plan_mode 的结果即复核结论：冻结其计划呈交卡
    if (event.toolName === 'exit_plan_mode') {
      const text = (event.text || '').trim();
      resolveByToolName('exit_plan_mode', text, text.includes('已获批准'));
      return;
    }
    // skill 结果 = 技能加载内容：填 .skill-preview 限高直出（M29 工单 12 对齐 ZCode 预览形态）
    if (event.toolName === 'skill') {
      const card = (event.toolCallId && t.toolCards.get(event.toolCallId)); // 无兜底：缺 id 的结果忽略（M29 审查：旧兜底会误填 todo 卡）
      if (!card) return;
      const failed = event.isError || /执行被拒绝|执行失败/.test(event.text || '');
      const label = card.querySelector('.tcard-label');
      if (label) label.classList.remove('sweep');
      const status = card.querySelector('.tcard-status');
      if (status && failed) {
        status.textContent = '执行失败';
        status.className = 'tcard-status fail';
        status.dataset.error = (event.text || '').trim(); // 悬停 CSS 浮窗数据源
        card.classList.add('error');
      }
      const preview = card.querySelector('.skill-preview');
      if (preview) {
        preview.hidden = false;
        preview.innerHTML = '';
        preview.appendChild(renderMarkdown(reasoningDisplayText(event.text || '（无输出）'), true));
      }
      return;
    }
    const card = (event.toolCallId && t.toolCards.get(event.toolCallId)); // 无兜底：缺 id 的结果忽略（M29 审查：旧兜底会误填 todo 卡）
    if (!card) return;
    const failed = event.isError || /执行被拒绝|执行失败/.test(event.text || '');
    // 运行态收尾（对齐 ZCode ToolLayout）：类别词扫光停止；成功态状态槽留空（报忧不报喜），
    // 失败态槽内点线「执行失败」+ 悬停 title 看错误全文（statusLabel+tooltip 语义）
    const label = card.querySelector('.tcard-label');
    if (label) label.classList.remove('sweep');
    const status = card.querySelector('.tcard-status');
    if (status && failed) {
      status.textContent = '执行失败';
      status.className = 'tcard-status fail';
      status.dataset.error = (event.text || '').trim(); // 悬停 CSS 浮窗数据源
      card.classList.add('error');
      // 失败态不展开（M29 工单 12 用户裁定：错误全文只在悬停浮窗）——收起已开的展开体
      const openBody = card.querySelector('.tcard-body');
      if (openBody) { openBody.style.display = 'none'; card.classList.remove('open'); }
    }
    // 旧式实卡（成果申报等 .card 族）徽标收尾：成功移除 ⟳ 运行中（报忧不报喜，
    // 与行卡口径一致）；失败换失败徽标。回放/完成后 ⟳ 永挂即此缺口。
    const legacyBadge = card.querySelector('.tool > .badge');
    if (legacyBadge) {
      if (failed) {
        legacyBadge.className = 'badge badge-fail';
        legacyBadge.textContent = '✗ 失败';
        legacyBadge.title = (event.text || '').trim();
      } else {
        legacyBadge.remove();
      }
    }
    // 治理提醒（[提醒] 前缀）拆出为独立标注块
    const text = event.text || '';
    const remindIdx = text.indexOf('[提醒]');
    const resultPart = remindIdx >= 0 ? text.slice(0, remindIdx).trim() : text;
    const remindPart = remindIdx >= 0 ? text.slice(remindIdx).trim() : '';
    // 结果统一填入行卡展开体（M29 工单 12「行 = 摘要，点行 = 展开结果」模型）：
    // 结果盒 mono 直出在 body 内；失败卡不填（不展开，错误全文走悬停浮窗——用户裁定）。
    if (remindPart) {
      // 治理提醒标注块先于免结果段 return 渲染（M29 审查：提前 return 会吞 [提醒] 段）
      const remind = document.createElement('div');
      remind.className = 'remind';
      remind.textContent = remindPart;
      t.container.insertBefore(remind, card.nextSibling);
    }
    if (['edit', 'write', 'todo_write', 'memory_write'].includes(event.toolName) && !failed) {
      return; // 成功免结果段：diff/文件行/清单内联/记忆行卡已是结果语义
    }
    const body = card.querySelector('.tcard-body');
    if (body && !failed && resultPart) {
      const box = document.createElement('div');
      box.className = 'result-inbody';
      // 展开体 = 干净代码块直出（用户裁定：对齐模型引用块形态——mono 12 单块，
      // 不走 markdown 解析：缩进行不会被误识别成代码块、退出码不会成孤段）
      const pre = document.createElement('pre');
      pre.textContent = resultPart || '（无输出）';
      box.appendChild(pre);
      body.appendChild(box);
    }
    if (remindPart) {
      const remind = document.createElement('div');
      remind.className = 'remind';
      remind.textContent = remindPart;
      t.container.insertBefore(remind, card.nextSibling);
    }
    if (event.toolCallId && t.toolCards.get(event.toolCallId) === card) {
      t.lastOpenToolCard = null;
      t.lastOpenToolName = '';
    }
    scroll();
  }

  /** HITL 交互卡（审批 / 计划呈交 / 提问共用骨架）：事件委托接管点击，无需逐卡挂监听。 */
  function interactiveCard(event, dockPending, mountEl) {
    if (dockPending) { interactionDockCard(event); return; }
    showMessages();
    const isPlan = event.toolName === 'exit_plan_mode';
    const card = document.createElement('div');
    card.className = 'card interactive';
    card.dataset.toolName = event.toolName || '';
    card.dataset.cardId = event.toolCallId || ''; // 卡片 id（M24 工单 02：按 id 精确回填）
    let questionText = '[待审批] 工具 ' + (event.toolName || '') + ' 请求执行';
    let planBody = null;
    if (isPlan) {
      questionText = '[计划呈交] 请审阅以下计划';
      try { planBody = JSON.parse(event.text || '{}').plan || event.text; } catch (e) { planBody = event.text; }
    }
    const title = document.createElement('div');
    title.className = 'question';
    title.textContent = questionText;
    card.appendChild(title);
    const pre = document.createElement('pre');
    if (isPlan) {
      pre.className = 'plan';
      let planBody = event.text || '';
      try { planBody = JSON.parse(event.text || '{}').plan || event.text; } catch (e) { /* 原样展示 */ }
      pre.textContent = planBody;
    } else {
      pre.textContent = event.text || '';
    }
    card.appendChild(pre);
    const choices = document.createElement('div');
    choices.className = 'choices';
    if (isPlan) {
      choices.innerHTML =
        '<button class="choice primary" data-action="plan-approve">批准，开始执行</button>' +
        '<button class="choice" data-action="plan-reject">打回，继续完善计划</button>';
    } else {
      choices.innerHTML =
        '<button class="choice primary" data-action="answer" data-decision="approve">批准本次执行</button>' +
        '<button class="choice" data-action="answer" data-decision="always-project">总是允许（项目）</button>' +
        '<button class="choice" data-action="answer" data-decision="always-session">仅本会话</button>' +
        '<button class="choice" data-action="answer" data-decision="reject">拒绝</button>';
    }
    card.appendChild(choices);
    if (isPlan) {
      const free = document.createElement('div');
      free.className = 'free-input';
      free.innerHTML = '<input placeholder="打回时给模型的修改意见…"><button class="choice" data-action="plan-reject">打回</button>';
      card.appendChild(free);
    }
    (mountEl || t.container).appendChild(card); // mountEl=dock 时挂 dock（M29 审查：interactionDockCard 对非 ask_user 透传 dock——伪停靠修复）
    scroll();
    return card;
  }

  /** ask_user 冻结摘要行（M29 工单 12 用户裁定：历史只留问题与回答，不渲染选择卡）——
   *  ZCode ask-question 冻结行同款：问题（深色）+ 答案（浅色副行）。 */
  function askFrozenLine(question, answer) {
    showMessages();
    // 行=「询问用户 + 问题摘要」，点行展开问题全文与用户选择的内容（统一行卡模型）
    const { card, body } = typedCardShell({
      icon: 'lightbulb', label: '询问用户',
      primary: question || '（提问）',
    });
    card.querySelector('.tcard-primary').title = question || ''; // 行上截断，悬停看全文
    const q = document.createElement('div');
    q.className = 'ask-frozen-q';
    q.textContent = question || '（提问）';
    body.appendChild(q);
    if (answer) {
      const a = document.createElement('div');
      a.className = 'ask-frozen-a';
      a.textContent = answer;
      body.appendChild(a);
    }
    t.container.appendChild(card);
    scroll();
    return card;
  }

  /** 交互卡底部停靠（M29 工单 12 对齐 ZCode bottom dock）：待答卡脱离消息流渲染在
   *  #interactionDock（composer 上方），并遮蔽 composer（isBlockedByInteraction 同款
   *  ——回答/收口后由 resolveInteractionDock 恢复）。回放路径不走 dock（历史交互卡
   *  在消息流内冻结呈现）。 */
  function interactionDockCard(event) {
    const dock = document.querySelector('#interactionDock');
    if (!dock) return;
    dock.innerHTML = '';
    dock.hidden = false;
    document.querySelector('.composer').style.display = 'none';
    if (event.toolName === 'ask_user') questionCard(event, false, dock);
    else interactiveCard(event, false, dock);
    scroll();
  }

  /** dock 卡收口：待答卡移入消息流（保留作答态 DOM，历史完整）、恢复 composer。 */
  function resolveInteractionDock() {
    const dock = document.querySelector('#interactionDock');
    const card = dock?.firstElementChild; // dock 内唯一待答卡（resolveCard 已摘 .interactive 的作答态卡同样要搬运——按类查会 miss 致冻结卡丢失，M29 审查冒烟实锤）
    if (card) {
      t.container.appendChild(card);
      showMessages();
    }
    if (dock) {
      dock.innerHTML = '';
      dock.hidden = true;
    }
    document.querySelector('.composer').style.display = '';
    scroll();
  }

  /** 构建 ask_user 提问卡 DOM（dock/消息流两用，纯构建不挂载）。 */
  function questionCard(event, dockPending, mountEl) {
    // 挂起期去重（镜像计划卡先例）：question/requested 已渲染在途卡时，完成时的
    // tool/call 不再重复出卡（tool/result 随后冻结在途卡）
    if (t.container.querySelector('.interactive[data-tool-name="ask_user"]')) return;
    if (dockPending) { interactionDockCard(event); return; } // 走统一 dock（M29 工单 12 停靠模型）
    showMessages();
    let question = event.text || '';
    let options = [];
    try {
      const parsed = JSON.parse(event.text || '{}');
      if (parsed.question) question = parsed.question;
      if (Array.isArray(parsed.options)) options = parsed.options;
    } catch (e) { /* 纯文本问题 */ }
    const card = document.createElement('div');
    card.className = 'card interactive';
    card.dataset.toolName = 'ask_user';
    card.dataset.cardId = event.toolCallId || ''; // 卡片 id：question/requested 携请求 id → 作答按 id 精确回填
    const title = document.createElement('div');
    title.className = 'question';
    title.textContent = '[提问] ' + question;
    card.appendChild(title);
    if (options.length) {
      const choices = document.createElement('div');
      choices.className = 'choices';
      options.forEach((opt, i) => {
        const btn = document.createElement('button');
        btn.className = 'choice';
        btn.dataset.action = 'answer-value';
        btn.dataset.value = opt;
        btn.textContent = (i + 1) + '. ' + opt;
        choices.appendChild(btn);
      });
      card.appendChild(choices);
    }
    const free = document.createElement('div');
    free.className = 'free-input';
    free.innerHTML = '<input placeholder="或直接输入你的回答…"><button class="choice" data-action="answer-free">回答</button>';
    card.appendChild(free);
    (mountEl || t.container).appendChild(card);
    if (event.toolCallId) {
      t.toolCards.set(event.toolCallId, card); // dock 冻结回填按 id 找回（M29 审查：未注册致重复冻结行）
    }
    scroll();
    return card;
  }

  /** 冻结交互卡：摘除选项区，追加结果标注（幂等）。 */
  function resolveCard(card, note, ok) {
    if (!card || !card.classList.contains('interactive')) return;
    card.classList.remove('interactive');
    $$('.choices, .free-input', card).forEach(el => el.remove());
    const done = document.createElement('div');
    done.style.cssText = 'font-size:12px;font-weight:600;color:' + (ok ? 'var(--ok)' : 'var(--danger)');
    done.textContent = note;
    card.appendChild(done);
  }

  function resolveByToolName(toolName, note, ok) {
    const cards = $$('.interactive', t.container).filter(c => c.dataset.toolName === toolName);
    resolveCard(cards[cards.length - 1], note, ok);
  }

  /** approval/decided 回放/实时：按 toolName 找最后一张同工具交互卡冻结。 */
  function approvalDecided(event) {
    resolveInteractionDock(); // dock 待答审批卡移入消息流（M29 工单 12 停靠模型）
    const denied = (event.text || '').startsWith('deny');
    const cards = $$('.interactive', t.container).filter(c => c.dataset.toolName === (event.toolName || ''));
    const card = cards[cards.length - 1];
    if (event.toolName === 'exit_plan_mode') {
      resolveCard(card, denied ? '✗ 计划未获批准' : '✓ 计划已获批准', !denied);
    } else {
      resolveCard(card, denied ? '✗ 已拒绝' : '✓ 已批准', !denied);
    }
  }

  // 斜杠命令行（M19，ADR-0020 决策 5）：用户敲的命令以命令形态呈现（不进模型历史，
  // 纯呈现）；结果行随 command/done 到达——回放经同一 dispatch，刷新可见
  function commandLine(ev) {
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg user';
    const b = document.createElement('div');
    b.className = 'bubble cmd';
    b.textContent = '❯ /' + ev.toolName + (ev.text ? ' ' + ev.text : '');
    div.appendChild(b);
    t.container.appendChild(div);
    scroll();
  }

  function commandResult(ev, replaying) {
    if (!ev.text) return; // 空结果（如 /exit）不渲染
    if (ev.toolName === 'export' && ev.text.startsWith('/api/session/export')) {
      // /export（M21 工单 09）：done 结果即下载端点 URL——触发下载流
      // （Content-Disposition 命名，浏览器直接落盘）；URL 文本照常渲染可查。
      // URL 自含 sessionId（M26-07 显式寻址）——导出跟随命令发起时的当前会话；
      // 附加 token（M26-07 收口补）：a 点击是导航不走 fetch 头通道，鉴权开启时
      // 缺 token 会被 fail-closed 栅栏 403（用户验收实测发现）。
      // 回放不下载（用户验收实测发现）：命令审计事件随会话历史重放（切会话/翻页/
      // 刷新可见），自动下载只对实时命令生效——否则每次切回含 /export 的会话都
      // 重新触发一次下载
      if (!replaying) {
        const a = document.createElement('a');
        a.href = ev.text + (duoToken ? '&token=' + encodeURIComponent(duoToken) : '');
        document.body.appendChild(a);
        a.click();
        a.remove();
        showToast('正在下载导出文件…', 'info');
      }
    }
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg cmdresult';
    div.textContent = ev.text;
    t.container.appendChild(div);
    scroll();
  }

  /** 中断标记（M23 语义的前端中性呈现）：定格流式气泡 + 灰色标记行——中断是用户
   * 主动操作不是执行异常（回放投影为 [已中断] 前缀气泡，实时/回放语义一致）。 */
  function interruptedMark() {
    // 中断：两条打字机循环停止；思考卡保留原样（已到内容可见）并补齐 buffer 尾巴，
    // 正文按「已流出保留」语义整段定稿（引用用局部变量——先冲刷后清状态，M29 审查：
    // 原实现先置空 streamingBubble 导致 1272 行冲刷恒不执行、未流出字符静默丢失）
    const bubble = t.streamingBubble;
    if (bubble) bubble.classList.remove('streaming');
    if (t.typeRAF) { cancelAnimationFrame(t.typeRAF); t.typeRAF = null; }
    if (t.reasoningRAF) { cancelAnimationFrame(t.reasoningRAF); t.reasoningRAF = null; }
    if (t.reasoningStreamBody && t.reasoningBuffer) {
      t.reasoningStreamBody.textContent = t.reasoningBuffer;
    }
    if (bubble && t.chunkBuffer) {
      bubble.innerHTML = '';
      bubble.appendChild(renderMarkdown(t.chunkBuffer, true));
    }
    t.streamingBubble = null;
    t.chunkBuffer = '';
    t.reasoningBuffer = '';
    t.reasoningStreamCard = null;
    t.reasoningStreamBody = null;
    showMessages();
    const mark = document.createElement('div');
    mark.className = 'interrupted-mark';
    mark.textContent = '⏸ 已中断（已流出内容已保留，继续对话即续接）';
    t.container.appendChild(mark);
    scroll();
  }

  function runError(text) {
    // 流式中断/异常收口：半开的流式气泡就地定格（摘流式态与光标），错误卡随后追加——
    // 中断收口没有 assistant/message，气泡不停格就一直带光标悬着
    if (t.streamingBubble) {
      t.streamingBubble.classList.remove('streaming');
      t.streamingBubble = null;
    }
    showMessages();
    const card = document.createElement('div');
    card.className = 'card error';
    card.innerHTML = '<div class="tool">⚠ <b>执行异常</b></div>';
    const body = document.createElement('div');
    body.className = 'result-body';
    body.textContent = text || '';
    card.appendChild(body);
    t.container.appendChild(card);
    scroll();
  }

  /**
   * 子任务卡（M15 工单 05）：spawned 引用事件建卡（运行中态，携模板与任务描述），
   * completed 按 id 回填终态——「已被中止」「未正常完成」分别呈中断/失败，
   * 正常完成呈完成态 + 结果概要折叠 + 子会话回放入口。
   */
  /** 子代理名 hash 8 色（M29 工单 12；subagentColors 思路——同名同色、异名散开）。 */
  function agentColorClass(name) {
    let h = 0;
    for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    return 'agent-color-' + (h % 8);
  }

  function subagentSpawned(event) {
    showMessages();
    let task = event.text || '';
    try { task = JSON.parse(event.text || '{}').task || task; } catch (e) { /* 纯文本载荷 */ }
    const card = document.createElement('div');
    card.className = 'tcard subagent'; // M29 工单 12 无框化
    card.innerHTML = '<div class="tool">🤖 <b>子任务</b> · <span class="agent-name"></span> <span class="badge badge-run">⟳ 运行中</span></div>'
      + '<div class="subagent-task"></div>';
    const nameSpan = card.querySelector('.agent-name');
    nameSpan.className = 'agent-name ' + agentColorClass(event.toolName || '');
    nameSpan.textContent = event.toolName || '';
    nameSpan.title = event.toolName || ''; // 悬停全名（截断兜底，ZCode AgentNameText 同款）
    card.querySelector('.subagent-task').textContent = task;
    card.dataset.task = task; // 抽屉开场语境（打开入口据此携带任务描述）
    if (event.toolCallId) {
      t.subagentCards.set(event.toolCallId, card);
    }
    t.container.appendChild(card);
    scroll();
  }

  function subagentCompleted(event) {
    const card = (event.toolCallId && t.subagentCards.get(event.toolCallId)) || null;
    if (!card) return; // 卡不在场（回放窗口外）：终局已由模型回复承载，不重复呈现
    const text = event.text || '';
    const aborted = text.includes('已被中止');
    const failed = !aborted && text.includes('未正常完成');
    const badge = card.querySelector('.badge');
    if (badge) {
      badge.className = 'badge ' + (aborted || failed ? 'badge-fail' : 'badge-ok');
      badge.textContent = aborted ? '✗ 已中断' : (failed ? '✗ 失败' : '✓ 完成');
    }
    card.classList.add(aborted || failed ? 'failed' : 'done');
    if (!aborted && !failed) {
      const idx = text.indexOf('最终回答：');
      const answer = idx >= 0 ? text.slice(idx + '最终回答：'.length).trim() : text;
      const details = document.createElement('details');
      details.className = 'result';
      const summary = document.createElement('summary');
      summary.textContent = '▸ 结果概要（点击展开）';
      const body = document.createElement('div');
      body.className = 'result-body';
      body.textContent = answer;
      details.append(summary, body);
      card.appendChild(details);
      const open = document.createElement('button');
      open.className = 'choice subagent-open';
      open.dataset.action = 'open-subagent';
      open.dataset.agentId = event.toolCallId || '';
      open.dataset.task = card.dataset.task || '';
      open.textContent = '查看子任务全程';
      card.appendChild(open);
    }
    scroll();
  }

  /** 会话清空（/new）：回到 EmptyHero 初始态。 */
  function resetToHero() {
    resetForReplay();
    t.container.style.display = 'none';
    hero.style.display = 'flex';
    fillHero();
  }

  /** 重连复位：清空渲染区，等本轮全量回放重建（幂等——重连不该叠加重複历史）。 */
  function resetForReplay() {
    // 两条打字机 rAF 先取消（对已分离节点空转）；流式状态七字段全清——
    // tail-snapshot 重连发生在思考/正文流中时，残留引用会把回放增量写进
    // 幽灵卡（M29 审查 major：思考内容整轮不可见 + 脏 chunkBuffer 拼前缀）
    if (t.typeRAF) { cancelAnimationFrame(t.typeRAF); t.typeRAF = null; }
    if (t.reasoningRAF) { cancelAnimationFrame(t.reasoningRAF); t.reasoningRAF = null; }
    t.container.innerHTML = '';
    t.toolCards.clear();
    t.subagentCards.clear();
    t.lastOpenToolCard = null;
    t.streamingBubble = null;
    t.chunkBuffer = '';
    t.typeShown = 0;
    t.reasoningStreamCard = null;
    t.reasoningStreamBody = null;
    t.reasoningBuffer = '';
    t.reasoningShown = 0;
    // todo 面板属于渲染区：整窗替换的基线必须清（/new 与切换会话不经增量回放，
    // 上一会话的清单不得残留——清空后由回放流里的 todo/write 重建终态）
    todoPanel.clear();
  }

  /**
   * 事件 → 纯渲染（无网络/状态副作用）：SSE handle 与分页前置渲染共用的单源分发。
   * 回放期 chunk 照常渲染（BUG-20260915-03）：碎片流入 t.streamingBubble、assistant/message
   * 收口整段覆盖——进行中轮次刷新不空窗，且不复发 0913-04 碎片化（防碎片化不以丢弃为手段）。
   */
  function dispatch(ev, replaying = false) {
    if (ev.type === 'user/attachment') {
      // 附件引用块（M21 工单 04）：text 为引用 JSON，图片经授权读取端点回字节
      try {
        const ref = JSON.parse(ev.text);
        userImage('/api/attachment/read?id=' + ref.attachmentId, ref.name || '附件图片');
      } catch (e) { /* 坏行跳过 */ }
    }
    else if (ev.type === 'user/message') {
      user(ev.text);
      todoPanel.clear(); // 新轮开始：上一轮清单使命结束（与 todoProjection 清空语义一致）
    }
    else if (ev.type === 'assistant/chunk') chunk(ev.text);
    // 思考增量实时与回放同渲染（M29 工单 12 自测修复）：anthropic 形态的思考随工具轮
    // 发生（message 收口无思考），回放若跳过增量则工具轮思考全丢——轮次封段由
    // tool/call 的 finalizeReasoningStream 统一处理，实时/回放同构
    else if (ev.type === 'assistant/reasoning') reasoningStream(ev.text);
    else if (ev.type === 'assistant/message') finishAssistant(ev.text, ev.reasoning);
    else if (ev.type === 'tool/call') {
      // 工具调用出现 = 上一轮思考与叙述结束（M29 工单 12 执行序修复）：流式思考卡与
      // 叙述气泡就地定段，工具卡与后续内容按真实执行序追加——实时与回放同构
      finalizeReasoningStream();
      sealStreamingBubble();
      // ask_user 的 tool/call 按成对提交设计在完成后才落盘：实时流里问题卡已由
      // question/requested 前置事件渲染（BUG-20260929-01），补渲染只会出重复卡；
      // 仅回放（旧会话无前置事件）时由 tool/call 出卡
      if (ev.toolName === 'ask_user') {
        // 回放不渲染选择卡（M29 工单 12 用户裁定：历史只留问题与回答摘要，
        // ZCode ask-question 冻结行同款）——实时待答走 dock，收口时在消息流
        // 补冻结摘要行；旧会话（无 tool/result 收尾）回退只渲染问题行
        if (replaying) {
          const q = (() => { try { return JSON.parse(ev.text || '{}').question || ''; } catch (e) { return ''; } })();
          // 登记冻结卡：tool/result 到达时按 toolCallId 找回这张卡补回答——
          // 防同一次提问被 call/result 两条回放路径冻结两次（call 冻结问题 + result 再冻一对占位）
          const card = render.askFrozenLine(q || ev.text, null);
          if (ev.toolCallId) t.toolCards.set(ev.toolCallId, card);
          return;
        }
        return; // 实时待答卡走 dock
      }
      else if (ev.toolName === 'exit_plan_mode') interactiveCard(ev); // 计划呈交卡恒在消息流（不走 dock）
      else if (ev.toolName === 'todo_write') todoCard(ev);
      else {
        // present 退役（M29 工单 12 用户裁定）：工具停注册；历史 tool/call 回放走通用卡
        // 兜底（不炸）；deliverable/presented 事件保持静默（消费方为检索索引与导出）
        // 工具卡分型（M29 工单 12）：每类工具专属形态，未分型回退通用卡
        if (ev.toolName === 'bash') terminalCard(ev);
        else if (ev.toolName === 'edit') editCard(ev);
        else if (ev.toolName === 'write' || ev.toolName === 'read') fileCard(ev);
        else if (ev.toolName === 'skill') skillCard(ev);
        else if (ev.toolName === 'memory_write') memoryCard(ev);
        else toolCall(ev);
      }
    } else if (ev.type === 'tool/result') toolResult(ev);
    else if (ev.type === 'todo/write') todoPanel.update(ev.text);
    else if (ev.type === 'question/requested') {
      // 提问前置事件（BUG-20260929-01）：ask 前落盘携请求 id——提问卡据此在挂起期间
      // 实时渲染；完成时的 tool/call 因同卡在途被 questionCard 内去重跳过。
      // 回放跳过（M29 工单 12 用户裁定）：历史由 ask_user 冻结摘要行呈现，不留选择卡
      if (replaying) return;
      questionCard(ev, true); // 实时待答卡走 dock
    }
    else if (ev.type === 'approval/requested') {
      // 计划复核双留痕去重（BUG-20260917-04 后续）：同会话内 tool/call 已渲染计划卡时，
      // 审计事件不再重复渲染；跨会话场景（CLI 发起、Web 作答）本会话无 tool/call，照常渲染
      if (ev.toolName === 'exit_plan_mode' &&
          t.container.querySelector('.interactive[data-tool-name="exit_plan_mode"]')) return;
      interactiveCard(ev, !replaying); // 实时待答卡走 dock（回放进消息流冻结）
    }
    else if (ev.type === 'approval/decided') approvalDecided(ev);
    else if (ev.type === 'subagent/spawned') subagentSpawned(ev);
    else if (ev.type === 'subagent/completed') subagentCompleted(ev);
    else if (ev.type === 'command/run') commandLine(ev);
    else if (ev.type === 'command/done') commandResult(ev, replaying);
    else if (ev.type === 'assistant/interrupted') interruptedMark();
    else if (ev.type === 'run/error') runError(ev.text);
  }

  /**
   * 前置渲染更早历史（工单 02）：现有内容整体搬移 → 更早事件按日志序渲染 → 接回，
   * DOM 节点引用不动（工具卡 Map 与冻结态审批卡全部保持有效）；渲染前记录滚动
   * 高度锚点，渲染后补偿 scrollTop——顶部加内容视窗不跳屏。
   */
  function prependEvents(events) {
    const prevHeight = t.container.scrollHeight;
    const prevTop = t.container.scrollTop;
    const rest = document.createDocumentFragment();
    while (t.container.firstChild) rest.appendChild(t.container.firstChild);
    for (const ev of events) dispatch(ev, true); // 翻页渲染的是历史——不触发实时副作用
    t.container.appendChild(rest);
    t.container.scrollTop = t.container.scrollHeight - prevHeight + prevTop;
  }

  /**
   * 子任务抽屉渲染（M15 工单 05）：在指定容器上新建渲染目标并整批回放子会话事件
   * ——与主对话共用全部渲染函数（消息气泡 / 工具卡 / 子任务卡形态一致）。
   * 返回该目标，供抽屉关闭时丢弃（状态随目标对象一起回收）。
   */
  function replayInto(container, events, taskDescription) {
    container.innerHTML = '';
    const target = makeTarget(container, false);
    onTarget(target, () => {
      // 任务描述作开场（子会话日志从播种背景或子任务首条消息起，界面给出语境锚点）
      if (taskDescription) {
        const note = document.createElement('div');
        note.className = 'drawer-note';
        note.textContent = '子任务：' + taskDescription;
        container.appendChild(note);
      }
      for (const ev of events) {
        if (ev.type === 'subagent/seed-boundary') {
          // 种子边界（ADR-0015 决策 4 的审计可区分要求在呈现层的体现）
          const mark = document.createElement('div');
          mark.className = 'drawer-seed-mark';
          mark.textContent = '↑ 以上为继承的父对话背景 ｜ ' + (ev.text || '种子边界')
              + ' ｜ 以下为子代理自身行为 ↓';
          container.appendChild(mark);
          continue;
        }
        dispatch(ev, true); // 抽屉渲染的是历史回放——不触发实时副作用
      }
    });
    return target;
  }

  return {
    user, assistant, chunk, finishAssistant, toolCall, toolResult,
    interactiveCard, questionCard, approvalDecided, resolveCard,
    resolveInteractionDock, // M29 工单 12 停靠模型：§4 委托层乐观收口调用（IIFE 边界暴露）
    askFrozenLine, // M29 工单 12：§3 回放冻结摘要行调用（IIFE 边界暴露）
    resetToHero, resetForReplay, showMessages, dispatch, prependEvents, replayInto
  };
})();

// ----- §3 sse：EventSource 生命周期 + 回放边界帧（replay/start / replay/done）+ 加载锚点 -----
const sse = (() => {
  // 已加载最早期事件的日志序号（分页 before 锚点，工单 02）：带 id 帧取最小值；
  // 整窗替换（tail-snapshot 头帧）后重置重记
  let oldestLoaded = null;
  let replaying = true; // 头帧与 done 帧之间为回放：状态面刷新合并到 done 一次（防逐帧 fetch 风暴）
  let frameSession = ''; // 当前流所属会话（M26-06）：replay/start 帧学习——事件帧 id 前缀不符即丢弃

  function handle(event) {
    if (event.type === 'replay/start') {
      // 尾部窗口快照（首连/刷新/切换，ADR-0013）→ 整窗替换并记录窗口头（分页/无刷新切换消费）；
      // 增量（断线补齐）→ 保留页面已有内容（ADR-0010）
      if (event.sessionId) frameSession = event.sessionId; // 换绑重连：学习新会话（M26-06）
      if (event.mode === 'tail-snapshot') {
        render.resetForReplay();
        oldestLoaded = null;
        app.setTailWindow({ hasMore: !!event.hasMore, earlierCount: event.earlierCount || 0 });
      }
      replaying = true;
      app.clearSendBusy();
      return;
    }
    if (event.type === 'replay/done') {
      replaying = false;
      app.clearSendBusy();
      app.afterReplay();
      app.refreshStatus(); // 回放期的逐帧刷新合并到此刻一次
      return;
    }
    if (event.type === 'session/title') {
      // 标题实时生成（工单 06）：标签页立即更新；侧栏标题走 refreshSessions 拉取——
      // title append 已落盘，紧随的 /api/sessions 必返回新标题（标签页/侧栏两消费面各自接通）
      document.title = event.text;
      app.refreshSessions();
      return;
    }
    // 渲染单源（render.dispatch）；chunk 逐帧仅渲染——一次回复可达数百帧，状态面
    // 刷新交给 5s 轮询，其余事件帧后刷新一次。回放标志随帧传递（M28 验收实测修复）：
    // replay/start 与 done 之间的历史事件带 replaying=true——/export 等命令的实时
    // 副作用（自动下载）不得随刷新/切换的历史重放再次触发（与分页/抽屉路径同口径）
    render.dispatch(event, replaying);
    if (event.type === 'assistant/message' || event.type === 'run/error'
        || event.type === 'assistant/interrupted') app.clearSendBusy();
    if (event.type !== 'assistant/chunk') app.refreshStatus();
  }

  let source = null;

  /** 主动断开（切换/新建无刷新换绑前调用，工单 03）：EventSource.close 后浏览器不再自动重连。 */
  function disconnect() {
    if (source) {
      source.close();
      source = null;
    }
  }

  function connect() {
    disconnect(); // 重连路径幂等：先清旧连接再建（首连时为空操作）
    source = new EventSource('/api/events?token=' + encodeURIComponent(duoToken)
        + '&tabId=' + encodeURIComponent(duoTabId));
    source.onopen = () => {
      app.clearSendBusy(); // 断线期间可能错过解除帧——连接建立即复位
    };
    source.onmessage = (e) => {
      // 复合游标帧 id（M26-06）：「会话id#序号」——序号段更新锚点；会话段与 start 帧
      // 学得的 frameSession 不符即丢弃该帧（防串台第二道防线，主力校验在服务端）
      const m = /^([^#]+)#(\d+)$/.exec(e.lastEventId || '');
      if (m && frameSession && m[1] !== frameSession) return;
      const id = m ? Number(m[2]) : NaN;
      if (!Number.isNaN(id) && (oldestLoaded === null || id < oldestLoaded)) oldestLoaded = id;
      try {
        handle(JSON.parse(e.data));
      } catch (err) {
        // 单帧异常不拖垮连接：错误可见化后继续
        render.assistant('[页面错误] ' + (err instanceof Error ? err.message : String(err)));
      }
    };
    source.onerror = () => {
      // 断连由浏览器自动重连（EventSource 自动携带 Last-Event-ID 游标，服务端只补缺失段）
    };
  }

  function setOldest(value) { oldestLoaded = value; }

  return { connect, disconnect, oldest: () => oldestLoaded, setOldest };
})();

// ----- §4 app：装配（composer / 侧栏 / 状态面 / 事件委托） -----
const app = (() => {
  let currentSessionId = '';

  // ---- 交互卡事件委托：#messages 上统一接管 data-action 点击 ----
  // 交互卡事件委托（M29 工单 12 停靠模型）：#messages 与 #interactionDock 两容器
  // 共用同一处理器——dock 待答卡在独立容器里，点击也要进委托
  const interactionDelegate = async (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const card = btn.closest('.interactive');
    const action = btn.dataset.action;
    try {
      if (action === 'answer') {
        // M24 工单 02：按卡片 id 精确回填；always-* 由服务端生成 allow 规则——
        // 高危命令服务端按拒绝处理（与 CLI 非候选语义对齐），卡片冻结为已作答
        const decision = btn.dataset.decision || (btn.dataset.approved === 'true' ? 'approve' : 'reject');
        const always = decision.startsWith('always-');
        render.resolveCard(card, always ? '✓ 已作答（总是允许请求已提交）'
            : (decision === 'reject' ? '✗ 已拒绝' : '✓ 已批准'), decision !== 'reject');
        // dock 待答卡乐观收口（M29 工单 12）：回答即恢复 composer——tool/result 的
        // ask_user 收尾要等整轮 LLM 回复完才落盘，dock 不等它（消息流冻结已就位）
        if (card.closest('#interactionDock')) render.resolveInteractionDock();
        await api.answer({ id: card.dataset.cardId, decision });
      } else if (action === 'answer-value') {
        const value = btn.dataset.value || '';
        render.resolveCard(card, '✓ 已回答：' + value, true);
        if (card.closest('#interactionDock')) render.resolveInteractionDock();
        await api.answer({ id: card.dataset.cardId, answers: [value] });
      } else if (action === 'answer-free') {
        const input = $('.free-input input', card);
        const value = input ? input.value.trim() : '';
        if (!value) return;
        render.resolveCard(card, '✓ 已回答：' + value, true);
        await api.answer({ id: card.dataset.cardId, answers: [value] });
      } else if (action === 'plan-approve') {
        // values[0] = 批准选项（options[0] 位置约定，InteractionRequest.isApproved 单点判定）
        render.resolveCard(card, '✓ 已批准，开始执行', true);
        await api.answer({ id: card.dataset.cardId, answers: ['批准，开始执行'] });
      } else if (action === 'plan-reject') {
        const input = $('.free-input input', card);
        const feedback = input ? input.value.trim() : '';
        const verdict = feedback || '继续计划（可直接输入你的修改意见）';
        render.resolveCard(card, feedback ? '✗ 已打回，反馈：' + feedback : '✗ 已打回', false);
        // 打回走 answer 形态（按卡片 id 精确回填，C2 工单 07）
        await api.answer({ id: card.dataset.cardId, answers: [verdict] });
      } else if (action === 'copy-path') {
        // 成果卡路径行（M29 工单 07）：点击复制路径；剪贴板不可用（非安全上下文等）toast 兜底
        const path = btn.dataset.path || '';
        try {
          await navigator.clipboard.writeText(path);
          if (btn.dataset.busy) return; // 双击竞态守卫（M29 审查：二连点永停「✓ 已复制」）
        btn.dataset.busy = '1';
        const original = btn.textContent;
          btn.textContent = '✓ 已复制';
          btn.classList.add('copied');
          setTimeout(() => { btn.textContent = original; btn.classList.remove('copied'); }, 1200);
        } catch (err) {
          showToast('复制失败（已选中，可手动复制）：' + path, 'info');
          window.getSelection().selectAllChildren(btn);
        }
      } else if (action === 'open-subagent') {
        // 子任务回放入口（M15 工单 05）：完成态子任务卡 → 右侧抽屉只读回放
        await openSubagentReplay(btn.dataset.agentId || '', btn.dataset.task || '');
      }
    } catch (err) {
      render.assistant('[未处理异常] ' + (err instanceof Error ? err.message : String(err)));
    }
  };
  $('#messages').addEventListener('click', interactionDelegate);
  $('#interactionDock').addEventListener('click', interactionDelegate);

  // ---- 失败浮窗定位（M29 工单 12）：词下方居中 + 钳制卡界内——纯 CSS 锚点要么越中栏
  //      （锚词、词靠左时左穿）要么偏（锚卡右缘、词在左时距离远），故悬停时 JS 算一次
  //      写 CSS 变量（--fly-left 相对词），伪元素照渲染；mouseover 委托、每次重算（廉价）。
  $('#messages').addEventListener('mouseover', (e) => {
    const word = e.target.closest('.tcard-status.fail');
    if (!word) return;
    const wr = word.getBoundingClientRect();
    const card = word.closest('.tcard');
    if (!card) return;
    const cr = card.getBoundingClientRect();
    const w = cr.width / 2 - 8; // 浮窗占中栏内容宽一半（与 CSS 50cqw-8px 同口径）
    let left = wr.left + wr.width / 2 - w / 2; // 理想：词下方居中
    left = Math.max(cr.left + 8, Math.min(left, cr.right - 8 - w)); // 钳到卡（中栏）界内
    word.style.setProperty('--fly-left', Math.round(left - wr.left) + 'px');
  });

  // ---- 子任务抽屉（M15 工单 05）：完成态子任务卡的"查看子任务全程"——从右侧滑出，
  // 内容复用主对话渲染器（消息气泡 / 工具卡 / 子任务卡形态一致），含 fork 播种背景段；
  // 只读回放（服务端静态读，不持子会话锁、不可续写） ----
  async function openSubagentReplay(agentId, taskDescription) {
    if (!agentId) return;
    const drawer = $('#subagentDrawer');
    const body = $('#subagentBody');
    $('#subagentTitle').textContent = '子任务全程 · ' + agentId;
    drawer.hidden = false;
    void drawer.offsetWidth; // 强制重排：让下面的过渡从"屏外"起播（不用 rAF——后台标签会被节流）
    drawer.classList.add('open');
    body.innerHTML = '<div class="drawer-loading">载入子任务全程…</div>';

    let data;
    try {
      const res = await fetch('/api/subagent/events?id=' + encodeURIComponent(agentId));
      if (!res.ok) {
        body.innerHTML = '<div class="drawer-loading">拉取失败（HTTP ' + res.status + '）</div>';
        return;
      }
      data = await res.json();
    } catch (err) {
      body.innerHTML = '<div class="drawer-loading">拉取失败：' + errText(err) + '</div>';
      return;
    }
    if (!data.found) {
      body.innerHTML = '<div class="drawer-loading">子会话文件不存在</div>';
      return;
    }
    // 复用主对话渲染器整批回放（子 agent 与主 agent 呈现一致）
    render.replayInto(body, data.events || [], taskDescription);
  }

  function closeSubagentDrawer() {
    const drawer = $('#subagentDrawer');
    drawer.classList.remove('open');
    setTimeout(() => { drawer.hidden = true; $('#subagentBody').innerHTML = ''; }, 200);
  }
  $('#subagentClose').addEventListener('click', closeSubagentDrawer);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('#subagentDrawer').hidden) { closeSubagentDrawer(); return; }
    // Esc = 拒绝当前审批（M23 工单 03，ADR-0025）：**最旧**一张未冻结的审批卡——与
    // 服务端 complete() 的 FIFO 最旧完成语义对齐（审查修复：多卡时不点新卡造成
    // 视觉与语义错位）；复用拒绝按钮点击（冻结卡 + POST deny），计划/提问卡不受影响
    const pending = $$('.card.interactive', document.getElementById('messages'))
      .filter(c => c.dataset.toolName !== 'exit_plan_mode'
        && c.querySelector('[data-action="answer"][data-approved="false"]'));
    if (pending.length) pending[0]
      .querySelector('[data-action="answer"][data-approved="false"]').click();
  });


  // ---- composer：发送（不本地回显，用户气泡由 SSE user/message 渲染） ----
  // 发送按钮三态（验收反馈：停止并入同钮，不再独立按钮）——
  // send=发送可点 / thinking=受理中禁用（思考中…）/ stop=执行中可点（协作式中断）；
  // 键盘回车始终走发送（执行中回车 = 注入，与 CLI 同语义），停止只能点钮触发
  let sendMode = 'send'; // 当前按钮态（点击分流：stop 态点钮 = 中断，其余 = 发送）
  function setSendMode(mode) {
    sendMode = mode;
    const btn = $('#send');
    btn.disabled = mode === 'thinking';
    btn.classList.toggle('stop', mode === 'stop');
    btn.textContent = mode === 'thinking' ? '思考中…' : (mode === 'stop' ? '停止' : '发送');
    btn.title = mode === 'stop' ? '协作式中断当前任务（已流出内容保留，再发消息即续接）' : '';
  }

  function clearSendBusy() {
    setSendMode('send');
  }

  let sendInFlight = false; // 请求在途闸：只拦重入，不拦"思考中"——执行中发消息是合法注入

  // ---- 附件（M21 工单 04）：拖拽/粘贴上传入列，发送时随消息提交 ----
  const pendingAttachments = [];
  const attChips = document.createElement('div');
  attChips.className = 'att-chips';
  document.querySelector('.composer').appendChild(attChips);

  function addPendingAttachment(meta) {
    if (pendingAttachments.some(a => a.attachmentId === meta.attachmentId)) return; // 同图不重列入列
    pendingAttachments.push(meta);
    renderAttChips();
  }

  function renderAttChips() {
    attChips.innerHTML = '';
    for (const meta of pendingAttachments) {
      const chip = document.createElement('span');
      chip.className = 'att-chip';
      chip.textContent = (meta.name || meta.attachmentId.slice(0, 8)) + ' · ' + Math.max(1, Math.round(meta.bytes / 1024)) + 'KB';
      const remove = document.createElement('button');
      remove.className = 'att-remove';
      remove.textContent = '×';
      remove.onclick = () => {
        const i = pendingAttachments.indexOf(meta);
        if (i >= 0) pendingAttachments.splice(i, 1);
        renderAttChips();
      };
      chip.appendChild(remove);
      attChips.appendChild(chip);
    }
  }

  function handleAttachmentFiles(files) {
    for (const file of files) {
      if (!file.type || !file.type.startsWith('image/')) {
        showToast('仅支持图片附件（png/jpeg/gif/webp）', 'err');
        continue;
      }
      api.uploadAttachment(file)
        .then(addPendingAttachment)
        .catch(err => showToast('上传失败：' + errText(err), 'err'));
    }
  }

  async function send() {
    const input = $('#input');
    const text = input.value.trim();
    const attachments = pendingAttachments.splice(0); // 取走待发清单
    if ((!text && !attachments.length) || sendInFlight) return; // 受理中重入忽略
    atClose(); // 发送即收起 @ 补全（下拉态不应跨消息残留）
    if (!attachments.length) attChips.innerHTML = ''; // 无附件发送时清可能残留的空壳
    input.value = '';
    sendInFlight = true;
    setSendMode('thinking');
    render.showMessages();
    try {
      const res = await api.sendMessage(text, attachments);
      if (res.status === 202) {
        // 202 空体 = 正常受理（异步执行）——同钮转【停止】；带体 = 结构化受理（M19）：
        // injected = 运行中注入（agent 仍在跑，保持【停止】）；command = 斜杠命令（命中执行
        // 的结果走事件流渲染，拒绝类无事件、text 随体 toast——未知命令/适用面/busy）
        const body = await res.text();
        if (!body) {
          setSendMode('stop');
          return;
        }
        let ack = {};
        try { ack = JSON.parse(body); } catch (e) { /* 兼容旧纯文本体 */ }
        if (ack.outcome === 'injected') {
          showToast(ack.text || '已注入，待当前步骤完成', 'info');
          setSendMode('stop'); // 注入即 agent 在跑：保持停止态到收口帧
        } else {
          setSendMode('send');
          if (ack.outcome === 'command' && ack.text) showToast(ack.text, 'info');
        }
        return;
      }
      setSendMode('send');
      if (res.status === 409) showToast('已有对话在执行中，请稍候', 'info');
      else showToast('消息发送失败（HTTP ' + res.status + '）');
      pendingAttachments.unshift(...attachments); // 失败返还：附件不丢
      renderAttChips();
    } catch (err) {
      pendingAttachments.unshift(...attachments);
      renderAttChips();
      setSendMode('send');
      showToast('消息发送失败：' + errText(err));
    } finally {
      sendInFlight = false;
    }
  }
  $('#send').addEventListener('click', () => {
    if (sendMode === 'stop') {
      doStop();
      return;
    }
    send();
  });
  $('#input').addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
  // 停止（M23 工单 02，验收反馈并入发送按钮）：协作式中断——202 受理后保持停止态到
  // 中断收口（assistant/interrupted 灰色标记到达即复位，不再弹受理 toast——收敛是
  // 毫秒级，标记行本身就是反馈）；409 = 已无任务（并发收口），直接回发送
  async function doStop() {
    try {
      const res = await api.stop();
      if (res.status === 202) {
        // 保持停止态等收口标记；无额外提示
      } else if (res.status === 409) {
        showToast('当前无执行中任务', 'info');
        setSendMode('send');
      } else {
        showToast('中断请求失败（HTTP ' + res.status + '）', 'err');
      }
    } catch (err) {
      showToast('中断请求失败：' + errText(err), 'err');
    }
  }
  // 附件入口（M21 工单 04）：粘贴与拖拽图片 → 上传入列（vision 关闭时端点 409 提示）
  $('#input').addEventListener('paste', (e) => handleAttachmentFiles(e.clipboardData.files));

  // ---- @file 补全下拉（M21 工单 07，ADR-0022 决策 7）----
  // 触发规则与 agent.fileref.FileMentionGrammar 同口径：@ 前须行首/空白；@"..." 引号
  // 路径可含空格；选中即插入 mention 文本（零内容注入——内容永远由模型 read）
  function activeAtToken(text, caret) {
    for (let p = Math.min(caret, text.length) - 1; p >= 0; p--) {
      if (text[p] !== '@') continue;
      if (p !== 0 && !/\s/.test(text[p - 1])) continue; // @ 前须行首/空白
      if (text[p + 1] === '"') {
        const close = text.indexOf('"', p + 2);
        if (close >= 0 && close < caret) {
          return { token: text.slice(p + 2, close), start: p, end: close + 1 };
        }
        return { token: text.slice(p + 2, caret), start: p, end: caret, quoted: true };
      }
      const body = text.slice(p + 1, caret);
      if (/\s/.test(body)) return null; // 非引号路径含空白 = token 已闭合
      return { token: body, start: p, end: caret };
    }
    return null;
  }

  function formatMention(path, isDirectory) {
    let p = isDirectory && !path.endsWith('/') ? path + '/' : path;
    return /\s/.test(p) ? '@"' + p + '"' : '@' + p;
  }

  let atState = { items: [], selected: 0, open: false, requestId: 0 };

  function atClose() {
    atState.open = false;
    atState.items = [];
    $('#atDropdown').hidden = true;
    $('#atDropdown').innerHTML = '';
  }

  function atRenderList() {
    const box = $('#atDropdown');
    box.innerHTML = '';
    if (!atState.items.length) {
      const empty = document.createElement('div');
      empty.className = 'at-empty';
      empty.textContent = '无匹配路径';
      box.appendChild(empty);
      return;
    }
    atState.items.forEach((item, i) => {
      const row = document.createElement('div');
      row.className = 'at-item' + (i === atState.selected ? ' selected' : '');
      const dir = document.createElement('span');
      dir.className = 'at-dir';
      dir.textContent = item.directory ? '[目录]' : '[文件]';
      const path = document.createElement('span');
      path.textContent = item.path;
      row.append(dir, path);
      row.addEventListener('mousedown', (e) => { e.preventDefault(); atPick(i); });
      box.appendChild(row);
    });
    const sel = box.children[atState.selected];
    if (sel) sel.scrollIntoView({ block: 'nearest' });
  }

  function atPick(i) {
    const item = atState.items[i];
    if (!item) return;
    const input = $('#input');
    // 换行/多行输入下 activeAtToken 以整值 + 光标计算，选中替换 [start, end) 区间
    const active = activeAtToken(input.value, input.selectionStart || input.value.length);
    const mention = formatMention(item.path, item.directory) + ' ';
    if (active) {
      const before = input.value.slice(0, active.start);
      const after = input.value.slice(active.end);
      input.value = before + mention + after;
      const caret = before.length + mention.length;
      input.setSelectionRange(caret, caret);
    } else {
      input.value += mention;
    }
    atClose();
    input.focus();
  }

  async function atRefresh() {
    const input = $('#input');
    const active = activeAtToken(input.value, input.selectionStart || input.value.length);
    if (!active) { atClose(); return; }
    const rid = ++atState.requestId;
    try {
      const res = await fetch('/api/file-complete?q=' + encodeURIComponent(active.token));
      if (rid !== atState.requestId) return; // 过期响应丢弃
      if (!res.ok) { atClose(); return; }
      const data = await res.json();
      atState.items = data.suggestions || [];
      atState.selected = 0;
      atState.open = true;
      const box = $('#atDropdown');
      box.hidden = false;
      atRenderList();
    } catch (e) { atClose(); }
  }

  function initAtCompletion() {
    const input = $('#input');
    let timer = null;
    input.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(atRefresh, 120);
    });
    // capture：下拉打开时按键先于发送监听处理（Enter 选词不发消息）
    input.addEventListener('keydown', (e) => {
      if (!atState.open || !atState.items.length) return;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const delta = e.key === 'ArrowDown' ? 1 : -1;
        atState.selected = (atState.selected + delta + atState.items.length) % atState.items.length;
        atRenderList();
      } else if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        e.stopPropagation();
        atPick(atState.selected);
      } else if (e.key === 'Escape') {
        e.preventDefault();
        atClose();
      }
    }, true);
    // 点击面板外收起（mousedown 在 pick 的 preventDefault 之后不误关）
    document.addEventListener('mousedown', (e) => {
      if (atState.open && !$('#atDropdown').contains(e.target) && e.target !== input) atClose();
    });
  }
  const composerEl = document.querySelector('.composer');
  composerEl.addEventListener('dragover', (e) => e.preventDefault());
  composerEl.addEventListener('drop', (e) => { e.preventDefault(); handleAttachmentFiles(e.dataTransfer.files); });

  // ---- 新话题：无刷新换绑（工单 03）——断 SSE → POST new → 空态反馈 → 重连收新会话尾部快照 ----
  $('#newSession').addEventListener('click', async () => {
    sse.disconnect();
    try {
      const res = await fetch('/api/session/new', { method: 'POST' });
      if (!res.ok) {
        showToast('新建会话失败（HTTP ' + res.status + '）');
        sse.connect(); // 换绑未发生：恢复当前会话的事件流
        return;
      }
      render.resetToHero(); // 立即空态反馈；回放完成后 afterReplay 幂等兜底
      $('#input').value = '';
      sse.connect();
    } catch (err) {
      sse.connect();
      showToast('新建会话失败：' + errText(err));
    }
  });

  // ---- 侧栏：列出 + 切换 + 当前高亮 ----
  function relativeTime(ms) {
    const d = new Date(ms);
    const now = new Date();
    const hm = d.toTimeString().slice(0, 5);
    const dayDiff = Math.floor((now.setHours(0, 0, 0, 0) - new Date(d).setHours(0, 0, 0, 0)) / 86400000);
    if (dayDiff <= 0) return '今天 ' + hm;
    if (dayDiff === 1) return '昨天 ' + hm;
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hm;
  }

  // 切换会话（侧栏条目与检索命中共用，M21 工单 08 抽取）：返回是否换绑成功
  async function switchToSession(id) {
    sse.disconnect(); // 无刷新切换（工单 03）：断流期间旧会话不再推帧
    try {
      const res = await fetch('/api/session/switch', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id })
      });
      if (!res.ok) {
        // 服务端错误文案优先（如"会话已被占用：<id>"）——比状态码更有行动指向
        const detail = (await res.text().catch(() => '')).trim();
        showToast(detail || ('切换会话失败（HTTP ' + res.status + '）'));
        sse.connect(); // 换绑未发生：恢复原会话事件流（快照整窗重放，内容一致）
        return false;
      }
      $('#input').value = ''; // 切换清空输入框：未发送的字符属于原会话语境，不跨会话携带
      sse.connect(); // 重连收新会话尾部快照 → 整窗替换；侧栏高亮随 afterReplay 刷新
      return true;
    } catch (err) {
      sse.connect();
      showToast('切换会话失败：' + errText(err));
      return false;
    }
  }

  async function refreshSessions() {
    let data;
    try {
      data = await api.sessions();
    } catch (e) {
      showToast('会话列表刷新失败：' + errText(e));
      return;
    }
    const list = $('#sessionList');
    list.innerHTML = '';
    // 当前会话身份走响应顶层（M30-05 验收裁定：deferred 当前会话不进列表——列表只含真实会话，
    // ZCode draft 同语义）；currentTitle 缺省「新会话」，标签页标题同源
    if (data.currentId) currentSessionId = data.currentId;
    document.title = data.currentTitle || currentSessionId;
    for (const s of data.sessions) {
      const item = document.createElement('div');
      item.className = 'sidebar-item' + (s.current ? ' active' : '') + (s.occupied ? ' occupied' : '');
      // 状态点（M29 W2 修正）：仅当前会话运行中 → 转圈点；「使用中」不加点（meta 文字已表达，满屏灰点是噪音）
      const running = s.current && (sendMode === 'thinking' || sendMode === 'stop');
      if (running) {
        const dot = document.createElement('span');
        dot.className = 'session-dot';
        item.appendChild(dot);
      }
      const sid = document.createElement('div');
      sid.className = 'sid';
      sid.textContent = s.title || s.id; // 标题优先（工单 06），无标题回退 id
      // 走马灯（M29 W1）：hover 延迟 1s 启动（CSS animation-delay），仅文本溢出时滚动，播完复位
      sid.addEventListener('mouseenter', () => {
        if (sid.classList.contains('marqueeing') || sid.scrollWidth <= sid.clientWidth + 2) return;
        const overflow = sid.scrollWidth - sid.clientWidth;
        const dist = overflow + 24; // 滚出 + 尾部间隙（附录 A2 gap）
        sid.style.setProperty('--marquee-shift', -dist + 'px');
        sid.style.setProperty('--marquee-dur', Math.round((dist + sid.clientWidth) / 40 * 1000) + 'ms'); // 40px/s
        sid.classList.add('marqueeing');
        const done = () => { sid.classList.remove('marqueeing'); sid.removeEventListener('animationend', done); };
        sid.addEventListener('animationend', done);
      });
      const meta = document.createElement('div');
      meta.className = 'meta';
      // meta 只留最新对话时间（M29 W2 用户裁定，参考 ZCode）：占用警示由灰显样式承担，
      // 点击撞锁仍有报错兜底——不再叠「当前/使用中」文字前缀
      meta.textContent = relativeTime(s.lastModifiedMs);
      item.append(sid, meta);
      item.addEventListener('click', async () => {
        if (s.current) return;
        await switchToSession(s.id);
      });
      list.appendChild(item);
    }
    $('#chatHint').textContent = '会话 ' + currentSessionId + ' · /new 开新话题';
    // 切换/重放后把当前高亮项滚入视野（block:nearest——已可见时不动，验收反馈①）
    const active = list.querySelector('.sidebar-item.active');
    if (active) active.scrollIntoView({ block: 'nearest' });
  }

  // ---- 侧栏搜索（M21 工单 08）：回车检索 → 命中列表替换会话列表，点击命中切会话 ----
  // 服务未装配时端点 503，toast 给出"未装配"提示——搜索框常驻但故障可见
  function initSessionSearch() {
    const box = $('#sessionSearch');
    const results = $('#searchResults');
    const list = $('#sessionList');
    // 检索态语义（验收反馈②精修）：有命中 → 替换会话列表占满侧栏；
    // 无命中 → 紧凑提示、列表照常可见（换会话不必先清搜索）。× / 空回车 / 切换命中后还原
    const dismiss = () => {
      results.hidden = true;
      results.innerHTML = '';
      results.classList.remove('has-hits');
      list.hidden = false;
    };
    box.addEventListener('keydown', async (e) => {
      if (e.key !== 'Enter') return;
      const q = box.value.trim();
      if (!q) {
        dismiss();
        return;
      }
      let data;
      try {
        data = await api.search(q);
      } catch (err) {
        showToast(errText(err));
        return;
      }
      results.innerHTML = '';
      results.classList.toggle('has-hits', data.hits.length > 0);
      list.hidden = data.hits.length > 0;
      results.hidden = false;
      const head = document.createElement('div');
      head.className = 'search-head';
      const label = document.createElement('span');
      label.textContent = '“' + q + '” · ' + data.hits.length + ' 个会话命中';
      const close = document.createElement('button');
      close.textContent = '×';
      close.title = '关闭检索结果';
      close.addEventListener('click', dismiss);
      head.append(label, close);
      results.appendChild(head);
      if (!data.hits.length) {
        const empty = document.createElement('div');
        empty.className = 'search-empty';
        empty.textContent = '无命中——检索只覆盖会话正文（消息/工具/清单），不含标题与元数据';
        results.appendChild(empty);
        return;
      }
      for (const hit of data.hits) {
        const item = document.createElement('div');
        item.className = 'sidebar-item search-hit';
        const sid = document.createElement('div');
        sid.className = 'sid';
        sid.textContent = hit.title || hit.sessionId;
        const meta = document.createElement('div');
        meta.className = 'meta';
        meta.textContent = hit.eventType + ' · ' + relativeTime(hit.lastModifiedMs);
        const snippet = document.createElement('div');
        snippet.className = 'snippet';
        snippet.textContent = hit.snippet; // 【】命中标记由后端 snippet 给出，纯文本呈现
        item.append(sid, meta, snippet);
        item.addEventListener('click', async () => {
          if (await switchToSession(hit.sessionId)) {
            dismiss();
            box.value = '';
          }
        });
        results.appendChild(item);
      }
    });
  }

  // ---- 状态面：上下文占用 + 插件快照 + 工具清单 ----
  const fmt = (n) => n.toLocaleString('en-US');
  // 持续性故障只报一次（5s 轮询不节流会刷屏）；恢复后计数清零、不播报恢复
  let statusFailures = 0;
  function renderContext(context) {
    // 占用与治理判定同源同口径（ADR-0009）：fromProvider=false 是估算兜底，必须标注不冒充实测
    const line = $('#contextLine');
    if (!context) {
      line.textContent = '—';
      line.classList.remove('near-threshold');
      return;
    }
    const pct = context.windowTokens > 0 ? (context.tokens * 100 / context.windowTokens).toFixed(1) : '0';
    const source = context.fromProvider ? '实测' : '估算';
    // 压缩熔断态（M25 工单 05）：true = 自动压缩暂停（会话照常，/compact 不受限）
    const tripped = context.compactionTripped ? ' · ⚠ 压缩已熔断（自动压缩暂停）' : '';
    line.textContent = fmt(context.tokens) + ' / ' + fmt(context.windowTokens)
        + ' tokens（' + pct + '%，压缩阈值 ' + fmt(context.thresholdTokens) + ' · ' + source + tripped + '）';
    line.classList.toggle('near-threshold', context.tokens >= context.thresholdTokens);
  }

  async function refreshStatus() {
    try {
      const data = await api.status();
      statusFailures = 0;
      renderContext(data.context);
      const plugins = $('#plugins tbody');
      plugins.innerHTML = '';
      for (const p of data.plugins) {
        const row = plugins.insertRow();
        row.innerHTML = '<td class="mono"></td><td class="state state-' + p.state + '"></td>';
        row.cells[0].textContent = p.name;
        row.cells[1].textContent = p.state;
      }
      // 连接器状态（M24 工单 05）：MCP 等外部连接器生命周期标注（GAVE_UP 标红）
      const connLine = $('#connectorStatus');
      const connectors = data.connector;
      if (!connectors || !connectors.length) {
        connLine.textContent = '—';
      } else {
        connLine.innerHTML = '';
        for (const c of connectors) {
          const item = document.createElement('div');
          item.className = 'bg-task mono';
          item.textContent = c.server + '：' + c.state + (c.detail ? '（' + c.detail + '）' : '');
          if (c.state === 'GAVE_UP') item.style.color = 'var(--danger)';
          connLine.appendChild(item);
        }
      }
      const tools = $('#tools tbody');
      tools.innerHTML = '';
      for (const t of data.tools) {
        const row = tools.insertRow();
        row.innerHTML = '<td class="mono"></td><td class="desc"></td>';
        row.cells[0].textContent = t.name;
        row.cells[1].textContent = t.description;
      }
      // 后台任务区块（M23 工单 06）：注册表缺席无字段 → 占位；终态保留呈现（收敛可见）
      const bgLine = $('#bgTasks');
      const tasks = data.backgroundTasks;
      if (!tasks || !tasks.length) {
        bgLine.textContent = '—';
      } else {
        bgLine.innerHTML = '';
        for (const t of tasks) {
          const item = document.createElement('div');
          item.className = 'bg-task mono';
          const stateText = t.state === 'RUNNING' ? '运行中' : (t.exitCode === 0 ? '完成(0)' : '结束(' + t.exitCode + ')');
          item.textContent = t.taskId + ' · ' + stateText + ' · ' + t.command;
          bgLine.appendChild(item);
        }
      }
    } catch (e) {
      statusFailures++;
      if (statusFailures === 1) showToast('状态刷新失败：' + errText(e));
    }
  }

  // ---- 尾部窗口与分页状态（ADR-0013）：快照头帧写入；分页加载与无刷新切换（工单 02/03）消费 ----
  let tailWindow = null;
  // 整窗代次：快照头帧递增——in-flight 的分页响应按代次校验，跨窗迟到即丢弃（防旧会话内容前置到新窗）
  let windowEpoch = 0;
  let loadingEarlier = false;

  function setTailWindow(window) {
    tailWindow = window;
    windowEpoch++;
    const more = $('#historyMore');
    if (more) {
      more.hidden = !window || !window.hasMore;
      more.textContent = window && window.hasMore ? '更早还有 ' + window.earlierCount + ' 条 · 向上滚动加载' : '';
    }
  }

  async function loadEarlier() {
    if (loadingEarlier || !tailWindow || !tailWindow.hasMore) return;
    const before = sse.oldest();
    if (before === null || before <= 0) {
      setTailWindow({ hasMore: false, earlierCount: 0 }); // 已到日志头：占位消失
      return;
    }
    loadingEarlier = true;
    const epoch = windowEpoch;
    try {
      const data = await api.page(before, currentSessionId);
      if (!data) return; // 409：游标属于旧会话（换绑后在途翻页）——静默丢弃，前端重新对齐
      if (epoch !== windowEpoch) return; // 加载期间窗口被整窗替换：结果过期丢弃
      if (data.sessionId !== currentSessionId) return; // M26-06：响应不属于当前会话（换绑后在途）——整页丢弃
      render.prependEvents(data.events);
      sse.setOldest(data.startEvent); // 锚点推进到新窗首事件：下次翻页取上一页而非重复本页
      setTailWindow({ hasMore: data.hasMore, earlierCount: data.earlierCount });
    } catch (err) {
      showToast('更早历史加载失败：' + errText(err));
    } finally {
      loadingEarlier = false;
      // 更早内容不足一屏时视窗仍在顶部，而滚动事件不再到来——补一发触发直至占位耗尽
      if (!$('#messages').hidden && $('#messages').scrollTop < 120) loadEarlier();
    }
  }

  // ---- 回放结束：无可见事件 → EmptyHero ----
  function afterReplay() {
    if (!$('#messages').children.length) {
      render.resetToHero();
    }
    refreshSessions();
  }

  refreshStatus();
  refreshSessions();
  initSessionSearch();
  initAtCompletion();
  setInterval(refreshStatus, 5000);

  // 状态面分区折叠（M29 W6）：details 容器是 index.html 静态骨架（refreshStatus
  // 只重渲 tbody），开合状态天然保持，无需额外持久化
  fillHero(); // 首载 hero 问候与示例 chips（M29 W3/W4；顶层函数——render IIFE 内 resetToHero 亦调用）

  // 滚动到顶加载更早历史（工单 02）：loading 标志防重入，加载后由 finally 补发直至占位耗尽
  $('#messages').addEventListener('scroll', () => {
    if ($('#messages').scrollTop < 120) loadEarlier();
  });

  return { refreshStatus, refreshSessions, afterReplay, clearSendBusy, setTailWindow, tailWindow: () => tailWindow };
})();

sse.connect();

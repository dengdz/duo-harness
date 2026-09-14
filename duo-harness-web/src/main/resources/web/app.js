'use strict';
// ===== Web 双面脚本层：§0 基础 → §1 api → §2 render → §3 sse → §4 app =====
// 与 index.html（骨架）、theme.css（样式）分文件维护；以 <script src> 在 body 尾
// 加载（DOM 就绪后执行，选择器不落空）。
'use strict';
// ===== §脚本 · 模块分区：§0 基础 → §1 api → §2 render → §3 sse → §4 app =====

// ----- §0 基础：选择器 + 页面错误可见化（呈现位自身可诊断） -----
const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
window.addEventListener('error', (e) => render.assistant('[页面错误] ' + e.message));
window.addEventListener('unhandledrejection', (e) => {
  const reason = e.reason instanceof Error ? e.reason.message : String(e.reason);
  render.assistant('[未处理异常] ' + reason);
});

// ----- §1 api：后端端点封装 -----
const api = {
  async status() { return (await fetch('/api/status')).json(); },
  async sessions() { return (await fetch('/api/sessions')).json(); },
  async sendMessage(text) {
    return fetch('/api/message', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text })
    });
  },
  async answer(approved, values) {
    const body = values ? { approved, values } : { approved };
    const res = await fetch('/api/answer', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    return res.json();
  }
};

// ----- §2 render：事件 → 卡片（9 种形态，对齐 prototype.html） -----
const render = (() => {
  const messages = $('#messages');
  const hero = $('#hero');
  // 打开的工具卡：toolCallId → 卡元素（tool/result 回填同一张卡）
  const toolCards = new Map();
  // 尚无 toolCallId 的最近工具卡（兜底匹配下一条 result）
  let lastOpenToolCard = null;
  let lastOpenToolName = null;
  let streamingBubble = null;

  const scroll = () => { messages.scrollTop = messages.scrollHeight; };

  function showMessages() {
    if (hero.style.display !== 'none') {
      hero.style.display = 'none';
      messages.style.display = 'block';
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
    messages.appendChild(div);
    scroll();
  }

  function assistant(text) {
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg assistant';
    div.textContent = text;
    messages.appendChild(div);
    scroll();
    return div;
  }

  /** 流式聚合：chunks 追加进同一个带光标的气泡；assistant/message 收口。 */
  function chunk(text) {
    showMessages();
    if (!streamingBubble) {
      streamingBubble = document.createElement('div');
      streamingBubble.className = 'msg assistant streaming';
      messages.appendChild(streamingBubble);
    }
    streamingBubble.textContent += text;
    scroll();
  }

  /**
   * Markdown 渲染体（模型回复专用）：marked 解析 → DOMPurify 消毒，顺序不可换——
   * 消毒必须作用于解析后的 HTML。系统/错误消息不走此路（保持 textContent）。
   * vendor 库缺失时降级纯文本：渲染增强不可用不阻断对话。
   */
  function renderMarkdown(text) {
    const body = document.createElement('div');
    body.className = 'md-body';
    if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') {
      body.textContent = text;
      return body;
    }
    body.innerHTML = DOMPurify.sanitize(marked.parse(text));
    return body;
  }

  function finishAssistant(text) {
    showMessages();
    // 流式期间保持纯文本追加（半截 markdown 渲染会闪烁），assistant/message 收口时整段渲染；
    // 历史回放无对应 chunk 流，同样走此分支
    let bubble;
    if (streamingBubble) {
      bubble = streamingBubble;
      bubble.classList.remove('streaming');
      bubble.textContent = '';
      streamingBubble = null;
    } else {
      bubble = document.createElement('div');
      bubble.className = 'msg assistant';
      messages.appendChild(bubble);
    }
    bubble.appendChild(renderMarkdown(text));
    scroll();
  }

  /** 工具合一卡：badge-run 起步，tool/result 回填徽标与可折叠结果。 */
  function toolCall(event) {
    showMessages();
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = '<div class="tool">🔧 <b></b> <span class="badge badge-run">⟳ 运行中</span></div><pre></pre>';
    card.querySelector('b').textContent = event.toolName || '';
    card.querySelector('pre').textContent = event.text || '';
    if (event.toolCallId) {
      toolCards.set(event.toolCallId, card);
    }
    lastOpenToolCard = card;
    lastOpenToolName = event.toolName || '';
    messages.appendChild(card);
    scroll();
    return card;
  }

  function toolResult(event) {
    // ask_user 的结果即回答文本：冻结其提问卡，不再渲染普通工具卡
    if (event.toolName === 'ask_user') {
      resolveByToolName('ask_user', '✓ 已回答：' + (event.text || '').trim(), true);
      return;
    }
    // exit_plan_mode 的结果即复核结论：冻结其计划呈交卡
    if (event.toolName === 'exit_plan_mode') {
      const text = (event.text || '').trim();
      resolveByToolName('exit_plan_mode', text, text.includes('已获批准'));
      return;
    }
    const card = (event.toolCallId && toolCards.get(event.toolCallId)) || lastOpenToolCard;
    if (!card) return;
    const failed = event.isError || /执行被拒绝|执行失败/.test(event.text || '');
    const badge = card.querySelector('.badge');
    if (badge) {
      badge.className = 'badge ' + (failed ? 'badge-fail' : 'badge-ok');
      badge.textContent = failed ? '✗ 失败' : '✓ 成功';
    }
    if (failed) card.classList.add('error');
    // 结果体可折叠；治理提醒（[提醒] 前缀）拆出为独立标注块
    const text = event.text || '';
    const remindIdx = text.indexOf('[提醒]');
    const resultPart = remindIdx >= 0 ? text.slice(0, remindIdx).trim() : text;
    const remindPart = remindIdx >= 0 ? text.slice(remindIdx).trim() : '';
    if (resultPart) {
      const details = document.createElement('details');
      details.className = 'result';
      const summary = document.createElement('summary');
      summary.textContent = '▸ 结果（点击展开/收起）';
      const body = document.createElement('div');
      body.className = 'result-body';
      body.textContent = resultPart;
      details.append(summary, body);
      card.appendChild(details);
    }
    if (remindPart) {
      const remind = document.createElement('div');
      remind.className = 'remind';
      remind.textContent = remindPart;
      messages.insertBefore(remind, card.nextSibling);
    }
    if (event.toolCallId && toolCards.get(event.toolCallId) === card) {
      lastOpenToolCard = null;
      lastOpenToolName = '';
    }
    scroll();
  }

  /** HITL 交互卡（审批 / 计划呈交 / 提问共用骨架）：事件委托接管点击，无需逐卡挂监听。 */
  function interactiveCard(event) {
    showMessages();
    const isPlan = event.toolName === 'exit_plan_mode';
    const card = document.createElement('div');
    card.className = 'card interactive';
    card.dataset.toolName = event.toolName || '';
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
        '<button class="choice primary" data-action="answer" data-approved="true">批准本次执行</button>' +
        '<button class="choice" data-action="answer" data-approved="false">拒绝</button>';
    }
    card.appendChild(choices);
    if (isPlan) {
      const free = document.createElement('div');
      free.className = 'free-input';
      free.innerHTML = '<input placeholder="打回时给模型的修改意见…"><button class="choice" data-action="plan-reject">打回</button>';
      card.appendChild(free);
    }
    messages.appendChild(card);
    scroll();
    return card;
  }

  /** 提问卡（ask_user 经 tool/call 呈现：问题 + 选项 + 自由输入）。 */
  function questionCard(event) {
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
    messages.appendChild(card);
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
    const cards = $$('#messages .interactive').filter(c => c.dataset.toolName === toolName);
    resolveCard(cards[cards.length - 1], note, ok);
  }

  /** approval/decided 回放/实时：按 toolName 找最后一张同工具交互卡冻结。 */
  function approvalDecided(event) {
    const denied = (event.text || '').startsWith('deny');
    const cards = $$('#messages .interactive').filter(c => c.dataset.toolName === (event.toolName || ''));
    const card = cards[cards.length - 1];
    if (event.toolName === 'exit_plan_mode') {
      resolveCard(card, denied ? '✗ 计划未获批准' : '✓ 计划已获批准', !denied);
    } else {
      resolveCard(card, denied ? '✗ 已拒绝' : '✓ 已批准', !denied);
    }
  }

  function runError(text) {
    showMessages();
    const card = document.createElement('div');
    card.className = 'card error';
    card.innerHTML = '<div class="tool">⚠ <b>执行异常</b></div>';
    const body = document.createElement('div');
    body.className = 'result-body';
    body.textContent = text || '';
    card.appendChild(body);
    messages.appendChild(card);
    scroll();
  }

  /** 会话清空（/new）：回到 EmptyHero 初始态。 */
  function resetToHero() {
    resetForReplay();
    messages.style.display = 'none';
    hero.style.display = 'flex';
  }

  /** 重连复位：清空渲染区，等本轮全量回放重建（幂等——重连不该叠加重複历史）。 */
  function resetForReplay() {
    messages.innerHTML = '';
    toolCards.clear();
    lastOpenToolCard = null;
    streamingBubble = null;
  }

  return {
    user, assistant, chunk, finishAssistant, toolCall, toolResult,
    interactiveCard, questionCard, approvalDecided, resolveCard,
    resetToHero, resetForReplay, showMessages
  };
})();

// ----- §3 sse：EventSource 生命周期 + 回放/实时边界（replay/done） -----
const sse = (() => {
  let replayed = true; // 边界帧前为回放：历史 chunk 不渲染（以 assistant/message 为准）

  function handle(event) {
    if (event.type === 'replay/done') { replayed = false; app.afterReplay(); return; }
    if (event.type === 'assistant/chunk' && replayed) return; // 历史碎片不回放
    if (event.type === 'user/message') render.user(event.text);
    else if (event.type === 'assistant/chunk') render.chunk(event.text);
    else if (event.type === 'assistant/message') render.finishAssistant(event.text);
    else if (event.type === 'tool/call') {
      if (event.toolName === 'ask_user') render.questionCard(event);
      else if (event.toolName === 'exit_plan_mode') render.interactiveCard(event);
      else render.toolCall(event);
    } else if (event.type === 'tool/result') render.toolResult(event);
    else if (event.type === 'approval/requested') render.interactiveCard(event);
    else if (event.type === 'approval/decided') render.approvalDecided(event);
    else if (event.type === 'run/error') render.runError(event.text);
    app.refreshStatus();
  }

  function connect() {
    const source = new EventSource('/api/events');
    // 连接/重连服务端都全量回放：复位回放门并清空渲染区，按回放语义重建（幂等）
    source.onopen = () => {
      replayed = true;
      render.resetForReplay();
    };
    source.onmessage = (e) => {
      try {
        handle(JSON.parse(e.data));
      } catch (err) {
        // 单帧异常不拖垮连接：错误可见化后继续
        render.assistant('[页面错误] ' + (err instanceof Error ? err.message : String(err)));
      }
    };
    source.onerror = () => {
      // 断连由浏览器自动重连；重连后服务端会重放当前会话
    };
  }

  return { connect, isReplaying: () => replayed };
})();

// ----- §4 app：装配（composer / 侧栏 / 状态面 / 事件委托） -----
const app = (() => {
  let currentSessionId = '';

  // ---- 交互卡事件委托：#messages 上统一接管 data-action 点击 ----
  $('#messages').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const card = btn.closest('.interactive');
    const action = btn.dataset.action;
    try {
      if (action === 'answer') {
        const approved = btn.dataset.approved === 'true';
        render.resolveCard(card, approved ? '✓ 已批准' : '✗ 已拒绝', approved);
        await api.answer(approved);
      } else if (action === 'answer-value') {
        const value = btn.dataset.value || '';
        render.resolveCard(card, '✓ 已回答：' + value, true);
        await api.answer(true, [value]);
      } else if (action === 'answer-free') {
        const input = $('.free-input input', card);
        const value = input ? input.value.trim() : '';
        if (!value) return;
        render.resolveCard(card, '✓ 已回答：' + value, true);
        await api.answer(true, [value]);
      } else if (action === 'plan-approve') {
        // ExitPlanModeTool 口径：approved=true 且 values[0] 精确等于批准选项
        render.resolveCard(card, '✓ 已批准，开始执行', true);
        await api.answer(true, ['批准，开始执行']);
      } else if (action === 'plan-reject') {
        const input = $('.free-input input', card);
        const feedback = input ? input.value.trim() : '';
        const verdict = feedback || '继续计划（可直接输入你的修改意见）';
        render.resolveCard(card, feedback ? '✗ 已打回，反馈：' + feedback : '✗ 已打回', false);
        // 打回也是 approved=true + 反馈文本（approved=false 会落 fail-closed 文案）
        await api.answer(true, [verdict]);
      }
    } catch (err) {
      render.assistant('[未处理异常] ' + (err instanceof Error ? err.message : String(err)));
    }
  });

  // ---- composer：发送（不本地回显，用户气泡由 SSE user/message 渲染） ----
  async function send() {
    const input = $('#input');
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    render.showMessages();
    const res = await api.sendMessage(text);
    if (res.status === 409) render.assistant('[提示] 已有对话在执行中，请稍候。');
  }
  $('#send').addEventListener('click', send);
  $('#input').addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });

  // ---- 新话题：服务端开新会话（SSE 随换绑），本页重连取回放 ----
  $('#newSession').addEventListener('click', async () => {
    const res = await fetch('/api/session/new', { method: 'POST' });
    if (!res.ok) return;
    render.resetToHero();
    location.reload(); // 重连 SSE 回放全新会话（EmptyHero），侧栏随之刷新
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

  async function refreshSessions() {
    const data = await api.sessions();
    const list = $('#sessionList');
    list.innerHTML = '';
    for (const s of data.sessions) {
      const item = document.createElement('div');
      item.className = 'sidebar-item' + (s.current ? ' active' : '');
      if (s.current) currentSessionId = s.id;
      const sid = document.createElement('div');
      sid.className = 'sid';
      sid.textContent = s.id;
      const meta = document.createElement('div');
      meta.className = 'meta';
      meta.textContent = (s.current ? '当前 · ' : '') + relativeTime(s.lastModifiedMs);
      item.append(sid, meta);
      item.addEventListener('click', async () => {
        if (s.current) return;
        await fetch('/api/session/switch', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: s.id })
        });
        location.reload(); // 重连 SSE 回放所切换的会话
      });
      list.appendChild(item);
    }
    $('#chatHint').textContent = '会话 ' + currentSessionId + ' · /new 开新话题';
  }

  // ---- 状态面：上下文占用 + 插件快照 + 工具清单 ----
  const fmt = (n) => n.toLocaleString('en-US');
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
    line.textContent = fmt(context.tokens) + ' / ' + fmt(context.windowTokens)
        + ' tokens（' + pct + '%，压缩阈值 ' + fmt(context.thresholdTokens) + ' · ' + source + '）';
    line.classList.toggle('near-threshold', context.tokens >= context.thresholdTokens);
  }

  async function refreshStatus() {
    try {
      const data = await api.status();
      renderContext(data.context);
      const plugins = $('#plugins tbody');
      plugins.innerHTML = '';
      for (const p of data.plugins) {
        const row = plugins.insertRow();
        row.innerHTML = '<td class="mono"></td><td class="state state-' + p.state + '"></td>';
        row.cells[0].textContent = p.name;
        row.cells[1].textContent = p.state;
      }
      const tools = $('#tools tbody');
      tools.innerHTML = '';
      for (const t of data.tools) {
        const row = tools.insertRow();
        row.innerHTML = '<td class="mono"></td><td class="desc"></td>';
        row.cells[0].textContent = t.name;
        row.cells[1].textContent = t.description;
      }
    } catch (e) { /* 状态面失败不阻断对话 */ }
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
  setInterval(refreshStatus, 5000);

  return { refreshStatus, refreshSessions, afterReplay };
})();

sse.connect();

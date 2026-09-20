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

// ----- §1 api：后端端点封装 -----
const api = {
  async status() { return (await fetch('/api/status')).json(); },
  async sessions() { return (await fetch('/api/sessions')).json(); },
  async search(q) {
    const res = await fetch('/api/search?q=' + encodeURIComponent(q));
    if (!res.ok) throw new Error((await res.text().catch(() => '')) || ('检索失败（HTTP ' + res.status + '）'));
    return res.json();
  },
  async page(before) {
    const res = await fetch('/api/session/page?before=' + before);
    if (!res.ok) throw new Error('分页请求失败（HTTP ' + res.status + '）');
    return res.json();
  },
  async sendMessage(text, attachments) {
    return fetch('/api/message', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(attachments && attachments.length ? { text, attachments } : { text })
    });
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

  /** 流式聚合：chunks 追加进同一个带光标的气泡；assistant/message 收口。 */
  function chunk(text) {
    showMessages();
    if (!t.streamingBubble) {
      t.streamingBubble = document.createElement('div');
      t.streamingBubble.className = 'msg assistant streaming';
      t.container.appendChild(t.streamingBubble);
    }
    t.streamingBubble.textContent += text;
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
      t.toolCards.set(event.toolCallId, card);
    }
    t.lastOpenToolCard = card;
    t.lastOpenToolName = event.toolName || '';
    t.container.appendChild(card);
    scroll();
    return card;
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
    card.className = 'card';
    const head = document.createElement('div');
    head.className = 'tool';
    const name = document.createElement('b');
    name.textContent = '任务清单';
    const inline = document.createElement('span');
    inline.className = 'todo-inline';
    inline.textContent = todos.length ? todoPanel.summaryText(todos) : '';
    const badge = document.createElement('span');
    badge.className = 'badge badge-run';
    badge.textContent = '⟳ 运行中';
    head.append('🧹 ', name, ' ', inline, ' ', badge);
    card.appendChild(head);
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
      resolveByToolName('ask_user', '✓ 已回答：' + (event.text || '').trim(), true);
      return;
    }
    // exit_plan_mode 的结果即复核结论：冻结其计划呈交卡
    if (event.toolName === 'exit_plan_mode') {
      const text = (event.text || '').trim();
      resolveByToolName('exit_plan_mode', text, text.includes('已获批准'));
      return;
    }
    const card = (event.toolCallId && t.toolCards.get(event.toolCallId)) || t.lastOpenToolCard;
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
      t.container.insertBefore(remind, card.nextSibling);
    }
    if (event.toolCallId && t.toolCards.get(event.toolCallId) === card) {
      t.lastOpenToolCard = null;
      t.lastOpenToolName = '';
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
    t.container.appendChild(card);
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
    t.container.appendChild(card);
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

  function commandResult(ev) {
    if (!ev.text) return; // 空结果（如 /exit）不渲染
    if (ev.toolName === 'export' && ev.text.startsWith('/api/session/export')) {
      // /export（M21 工单 09）：done 结果即下载端点 URL——触发下载流
      // （Content-Disposition 命名，浏览器直接落盘）；URL 文本照常渲染可查
      const a = document.createElement('a');
      a.href = ev.text;
      document.body.appendChild(a);
      a.click();
      a.remove();
      showToast('正在下载导出文件…', 'info');
    }
    showMessages();
    const div = document.createElement('div');
    div.className = 'msg cmdresult';
    div.textContent = ev.text;
    t.container.appendChild(div);
    scroll();
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
    t.container.appendChild(card);
    scroll();
  }

  /**
   * 子任务卡（M15 工单 05）：spawned 引用事件建卡（运行中态，携模板与任务描述），
   * completed 按 id 回填终态——「已被中止」「未正常完成」分别呈中断/失败，
   * 正常完成呈完成态 + 结果概要折叠 + 子会话回放入口。
   */
  function subagentSpawned(event) {
    showMessages();
    let task = event.text || '';
    try { task = JSON.parse(event.text || '{}').task || task; } catch (e) { /* 纯文本载荷 */ }
    const card = document.createElement('div');
    card.className = 'card subagent';
    card.innerHTML = '<div class="tool">🤖 <b></b> <span class="badge badge-run">⟳ 运行中</span></div>'
      + '<div class="subagent-task"></div>';
    card.querySelector('b').textContent = '子任务 · 模板 ' + (event.toolName || '');
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
  }

  /** 重连复位：清空渲染区，等本轮全量回放重建（幂等——重连不该叠加重複历史）。 */
  function resetForReplay() {
    t.container.innerHTML = '';
    t.toolCards.clear();
    t.subagentCards.clear();
    t.lastOpenToolCard = null;
    t.streamingBubble = null;
    // todo 面板属于渲染区：整窗替换的基线必须清（/new 与切换会话不经增量回放，
    // 上一会话的清单不得残留——清空后由回放流里的 todo/write 重建终态）
    todoPanel.clear();
  }

  /**
   * 事件 → 纯渲染（无网络/状态副作用）：SSE handle 与分页前置渲染共用的单源分发。
   * 回放期 chunk 照常渲染（BUG-20260915-03）：碎片流入 t.streamingBubble、assistant/message
   * 收口整段覆盖——进行中轮次刷新不空窗，且不复发 0913-04 碎片化（防碎片化不以丢弃为手段）。
   */
  function dispatch(ev) {
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
    else if (ev.type === 'assistant/message') finishAssistant(ev.text);
    else if (ev.type === 'tool/call') {
      if (ev.toolName === 'ask_user') questionCard(ev);
      else if (ev.toolName === 'exit_plan_mode') interactiveCard(ev);
      else if (ev.toolName === 'todo_write') todoCard(ev);
      else toolCall(ev);
    } else if (ev.type === 'tool/result') toolResult(ev);
    else if (ev.type === 'todo/write') todoPanel.update(ev.text);
    else if (ev.type === 'approval/requested') {
      // 计划复核双留痕去重（BUG-20260917-04 后续）：同会话内 tool/call 已渲染计划卡时，
      // 审计事件不再重复渲染；跨会话场景（CLI 发起、Web 作答）本会话无 tool/call，照常渲染
      if (ev.toolName === 'exit_plan_mode' &&
          t.container.querySelector('.interactive[data-tool-name="exit_plan_mode"]')) return;
      interactiveCard(ev);
    }
    else if (ev.type === 'approval/decided') approvalDecided(ev);
    else if (ev.type === 'subagent/spawned') subagentSpawned(ev);
    else if (ev.type === 'subagent/completed') subagentCompleted(ev);
    else if (ev.type === 'command/run') commandLine(ev);
    else if (ev.type === 'command/done') commandResult(ev);
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
    for (const ev of events) dispatch(ev);
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
        dispatch(ev);
      }
    });
    return target;
  }

  return {
    user, assistant, chunk, finishAssistant, toolCall, toolResult,
    interactiveCard, questionCard, approvalDecided, resolveCard,
    resetToHero, resetForReplay, showMessages, dispatch, prependEvents, replayInto
  };
})();

// ----- §3 sse：EventSource 生命周期 + 回放边界帧（replay/start / replay/done）+ 加载锚点 -----
const sse = (() => {
  // 已加载最早期事件的日志序号（分页 before 锚点，工单 02）：带 id 帧取最小值；
  // 整窗替换（tail-snapshot 头帧）后重置重记
  let oldestLoaded = null;
  let replaying = true; // 头帧与 done 帧之间为回放：状态面刷新合并到 done 一次（防逐帧 fetch 风暴）

  function handle(event) {
    if (event.type === 'replay/start') {
      // 尾部窗口快照（首连/刷新/切换，ADR-0013）→ 整窗替换并记录窗口头（分页/无刷新切换消费）；
      // 增量（断线补齐）→ 保留页面已有内容（ADR-0010）
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
    // 刷新交给 5s 轮询，其余事件帧后刷新一次
    render.dispatch(event);
    if (event.type === 'assistant/message' || event.type === 'run/error') app.clearSendBusy();
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
    source = new EventSource('/api/events');
    source.onopen = () => {
      app.clearSendBusy(); // 断线期间可能错过解除帧——连接建立即复位
    };
    source.onmessage = (e) => {
      const id = parseInt(e.lastEventId, 10);
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
  $('#messages').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const card = btn.closest('.interactive');
    const action = btn.dataset.action;
    try {
      if (action === 'answer') {
        const approved = btn.dataset.approved === 'true';
        render.resolveCard(card, approved ? '✓ 已批准' : '✗ 已拒绝', approved);
        await api.answer({ decision: approved ? 'approve' : 'reject' });
      } else if (action === 'answer-value') {
        const value = btn.dataset.value || '';
        render.resolveCard(card, '✓ 已回答：' + value, true);
        await api.answer({ answers: [value] });
      } else if (action === 'answer-free') {
        const input = $('.free-input input', card);
        const value = input ? input.value.trim() : '';
        if (!value) return;
        render.resolveCard(card, '✓ 已回答：' + value, true);
        await api.answer({ answers: [value] });
      } else if (action === 'plan-approve') {
        // ExitPlanModeTool 口径：values[0] 精确等于批准选项
        render.resolveCard(card, '✓ 已批准，开始执行', true);
        await api.answer({ answers: ['批准，开始执行'] });
      } else if (action === 'plan-reject') {
        const input = $('.free-input input', card);
        const feedback = input ? input.value.trim() : '';
        const verdict = feedback || '继续计划（可直接输入你的修改意见）';
        render.resolveCard(card, feedback ? '✗ 已打回，反馈：' + feedback : '✗ 已打回', false);
        // 打回走 answers 形态（批准选项文本即"继续计划"语义；decision 形态属审批卡专用）
        await api.answer({ answers: [verdict] });
      } else if (action === 'open-subagent') {
        // 子任务回放入口（M15 工单 05）：完成态子任务卡 → 右侧抽屉只读回放
        await openSubagentReplay(btn.dataset.agentId || '', btn.dataset.task || '');
      }
    } catch (err) {
      render.assistant('[未处理异常] ' + (err instanceof Error ? err.message : String(err)));
    }
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
    if (e.key === 'Escape' && !$('#subagentDrawer').hidden) closeSubagentDrawer();
  });


  // ---- composer：发送（不本地回显，用户气泡由 SSE user/message 渲染） ----
  // 发送受理中禁用按钮（"…"），202 后转"思考中…"——保持到本轮处理完成（assistant/message）
  // 或出错（run/error）才解除：期间服务端单飞 busy，按钮态与之精确对应；断线重连由回放复位兜底
  function setSendBusy(busy, label) {
    const btn = $('#send');
    btn.disabled = busy;
    btn.textContent = busy ? (label || '…') : '发送';
  }

  function clearSendBusy() {
    const btn = $('#send');
    if (btn.disabled) {
      btn.disabled = false;
      btn.textContent = '发送';
    }
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
    setSendBusy(true, '…');
    render.showMessages();
    try {
      const res = await api.sendMessage(text, attachments);
      if (res.status === 202) {
        // 202 空体 = 正常受理（异步执行）；带体 = 结构化受理（M19）：
        // injected = 运行中注入；command = 斜杠命令（命中执行的结果走事件流渲染，
        // 拒绝类无事件、text 随体 toast——未知命令/适用面/busy）
        const body = await res.text();
        if (!body) {
          setSendBusy(true, '思考中…');
          return;
        }
        setSendBusy(false);
        let ack = {};
        try { ack = JSON.parse(body); } catch (e) { /* 兼容旧纯文本体 */ }
        if (ack.outcome === 'injected') {
          showToast(ack.text || '已注入，待当前步骤完成', 'info');
        } else if (ack.outcome === 'command' && ack.text) {
          showToast(ack.text, 'info');
        }
        return;
      }
      setSendBusy(false);
      if (res.status === 409) showToast('已有对话在执行中，请稍候', 'info');
      else showToast('消息发送失败（HTTP ' + res.status + '）');
      pendingAttachments.unshift(...attachments); // 失败返还：附件不丢
      renderAttChips();
    } catch (err) {
      pendingAttachments.unshift(...attachments);
      renderAttChips();
      setSendBusy(false);
      showToast('消息发送失败：' + errText(err));
    } finally {
      sendInFlight = false;
    }
  }
  $('#send').addEventListener('click', send);
  $('#input').addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
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
    for (const s of data.sessions) {
      const item = document.createElement('div');
      item.className = 'sidebar-item' + (s.current ? ' active' : '') + (s.occupied ? ' occupied' : '');
      if (s.current) currentSessionId = s.id;
      const sid = document.createElement('div');
      sid.className = 'sid';
      sid.textContent = s.title || s.id; // 标题优先（工单 06），无标题回退 id
      const meta = document.createElement('div');
      meta.className = 'meta';
      // 占用标注（工单 05）：灰显 + "使用中"角标——只提供预期，点击仍可尝试（撞锁报错保留）
      meta.textContent = (s.current ? '当前 · ' : '') + (s.occupied ? '使用中 · ' : '') + relativeTime(s.lastModifiedMs);
      item.append(sid, meta);
      item.addEventListener('click', async () => {
        if (s.current) return;
        await switchToSession(s.id);
      });
      list.appendChild(item);
      if (s.current) document.title = s.title || s.id; // 标签页标题跟随当前会话（工单 06）
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
    line.textContent = fmt(context.tokens) + ' / ' + fmt(context.windowTokens)
        + ' tokens（' + pct + '%，压缩阈值 ' + fmt(context.thresholdTokens) + ' · ' + source + '）';
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
      const tools = $('#tools tbody');
      tools.innerHTML = '';
      for (const t of data.tools) {
        const row = tools.insertRow();
        row.innerHTML = '<td class="mono"></td><td class="desc"></td>';
        row.cells[0].textContent = t.name;
        row.cells[1].textContent = t.description;
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
      const data = await api.page(before);
      if (epoch !== windowEpoch) return; // 加载期间窗口被整窗替换：结果过期丢弃
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

  // 滚动到顶加载更早历史（工单 02）：loading 标志防重入，加载后由 finally 补发直至占位耗尽
  $('#messages').addEventListener('scroll', () => {
    if ($('#messages').scrollTop < 120) loadEarlier();
  });

  return { refreshStatus, refreshSessions, afterReplay, clearSendBusy, setTailWindow, tailWindow: () => tailWindow };
})();

sse.connect();

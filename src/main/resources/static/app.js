const $ = (selector, parent = document) => parent.querySelector(selector);
const app = $('#app');
const modal = $('#modal');
const labels = {
  READY: '已就绪', QUEUED: '排队中', PROCESSING: '解析中', RUNNING: '执行中',
  SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  TODO: '待开始', IN_PROGRESS: '进行中', DONE: '已完成',
  READING: '阅读中', BLOCKED: '有疑问', PRIVATE: '仅自己', GROUP: '课题组共享',
  IMPORT: '资料解析', MEETING_DRAFT: '组会草稿', MINUTE_ACTIONS: '待办提取',
  MEETING_PREPARATION: '组会准备', SOURCE_LOOKUP: '资料查找', ACTION_EXTRACTION: '待办整理',
};
const navigation = [
  ['home', '工作概览', 'grid'], ['library', '文献与笔记', 'book'],
  ['meetings', '组会空间', 'calendar'], ['tasks', '行动清单', 'check'],
  ['jobs', '处理记录', 'activity'], ['measurements', '使用评估', 'chart'],
];
const state = {
  token: sessionStorage.getItem('research-token') || '', user: null, config: null,
  workspaces: [], workspace: null, documents: [], meetings: [], tasks: [], jobs: [], members: [], summaries: [],
  view: 'home', selectedMeeting: null, epoch: 0, pollBusy: false, authMode: 'login',
};
let toastTimeout;

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[character]);
}
const esc = escapeHtml;

function icon(name) {
  const paths = {
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    book: '<path d="M3 4h6a4 4 0 0 1 4 4v13a5 5 0 0 0-4-2H3zM13 8a4 4 0 0 1 4-4h4v15h-4a5 5 0 0 0-4 2"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 11h18m-14 4h3m4 0h3"/>',
    check: '<rect x="3" y="3" width="18" height="18" rx="3"/><path d="m7 12 3 3 7-7"/>',
    activity: '<path d="M2 12h5l3-8 4 16 3-8h5"/>',
    chart: '<path d="M4 3v17h17M9 16V9m5 7V5m5 11v-6"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>',
    logout: '<path d="M9 4H4v16h5m5-4 4-4-4-4M8 12h12"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    refresh: '<path d="M20 8a8 8 0 1 0 0 8M20 3v5h-5"/>',
    users: '<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3m2-16a3 3 0 0 1 0 6m2 10v-3a6 6 0 0 0-3-5"/>',
    file: '<path d="M14 2H5v20h14V7zm0 0v5h5M8 12h8m-8 4h6"/>',
    search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  };
  return `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.file}</svg>`;
}

function badge(status) {
  const style = ['READY', 'SUCCEEDED', 'DONE'].includes(status) ? 'success'
    : ['RUNNING', 'PROCESSING', 'IN_PROGRESS', 'READING'].includes(status) ? 'working'
    : ['FAILED', 'BLOCKED'].includes(status) ? 'error' : ['QUEUED', 'TODO'].includes(status) ? 'waiting' : '';
  return `<span class="status ${style}"><span class="dot"></span>${esc(labels[status] || status)}</span>`;
}

function empty(title, message, button = '') {
  return `<div class="empty">${icon('book')}<div><strong>${esc(title)}</strong></div><p>${esc(message)}</p>${button}</div>`;
}

function date(value) {
  return value ? String(value).slice(0, 10) : '未设置';
}

function time(value) {
  if (!value) return '';
  const utc = /Z$|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`;
  return new Date(utc).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function today(offset = 0) {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + offset);
  return day.toISOString().slice(0, 10);
}

function toast(message, error = false) {
  const element = $('#toast');
  element.textContent = message;
  element.className = `visible${error ? ' error' : ''}`;
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => { element.className = ''; }, error ? 7000 : 4000);
}

function resetSession() {
  state.epoch++;
  state.token = '';
  state.user = null;
  state.workspace = null;
  state.selectedMeeting = null;
  sessionStorage.removeItem('research-token');
  if (modal.open) modal.close();
}

async function api(path, { method = 'GET', body, key, signal } = {}) {
  const token = state.token;
  const epoch = state.epoch;
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers['Idempotency-Key'] = key;
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {
    method, headers, signal,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  const content = await response.text();
  if (epoch !== state.epoch) throw new DOMException('课题组或登录状态已变化', 'AbortError');
  let result;
  try { result = content ? JSON.parse(content) : null; }
  catch { throw new Error('服务返回了无法识别的内容，请确认后端已启动'); }
  if (!response.ok) {
    if (response.status === 401 && state.user && state.token === token) {
      resetSession();
      renderAuth();
    }
    const error = new Error(result?.message || `请求失败（${response.status}）`);
    error.status = response.status;
    throw error;
  }
  return result;
}

function workspacePath(suffix = '') {
  if (!state.workspace) throw new Error('请先创建或选择课题组');
  return `/api/workspaces/${encodeURIComponent(state.workspace.id)}${suffix}`;
}

function formError(form, message) {
  let error = $('.form-error', form);
  if (!error) {
    error = document.createElement('div');
    error.className = 'form-error';
    error.setAttribute('role', 'alert');
    form.append(error);
  }
  error.textContent = message;
}

async function submit(form, action) {
  const button = $('button[type="submit"]', form);
  if (button?.disabled) return;
  if (button) button.disabled = true;
  formError(form, '');
  try { await action(new FormData(form)); }
  catch (error) { formError(form, error.message); }
  finally { if (button) button.disabled = false; }
}

function openModal(title, content) {
  modal.innerHTML = `<div class="modal-header"><h2 id="modal-title">${esc(title)}</h2><button type="button" class="icon-button" data-close aria-label="关闭">${icon('close')}</button></div><div class="modal-body">${content}</div>`;
  if (!modal.open) modal.showModal();
}

function modalError(error) {
  openModal('暂时无法打开', `<div class="notice error">${esc(error.message)}</div>`);
}

function heading(eyebrow, title, description, action = '') {
  return `<div class="page-heading"><div><span class="eyebrow">${eyebrow}</span><h1>${title}</h1><p>${description}</p></div>${action}</div>`;
}

function canEditMeeting(meeting) {
  return state.workspace?.role === 'OWNER' || meeting.createdBy === state.user.id;
}

async function loadData() {
  const epoch = state.epoch;
  const base = workspacePath();
  const values = await Promise.all(['documents', 'meetings', 'tasks', 'jobs', 'members', 'measurements']
    .map(resource => api(`${base}/${resource}`)));
  if (epoch !== state.epoch) return false;
  [state.documents, state.meetings, state.tasks, state.jobs, state.members, state.summaries] = values;
  return true;
}

async function selectWorkspace(id) {
  state.epoch++;
  state.workspace = state.workspaces.find(workspace => workspace.id === id) || null;
  state.selectedMeeting = null;
  state.documents = []; state.meetings = []; state.tasks = []; state.jobs = []; state.members = []; state.summaries = [];
  if (state.workspace) sessionStorage.setItem(`research-workspace-${state.user.id}`, state.workspace.id);
  renderShell(true);
  if (!state.workspace) { renderPage(); return; }
  try {
    if (await loadData()) renderPage();
  } catch (error) {
    if (error.name === 'AbortError' || !state.user) return;
    $('#main').innerHTML = `<p class="notice error">${esc(error.message)}</p><button class="button" data-action="refresh">重新加载</button>`;
    throw error;
  }
}

async function enter() {
  state.epoch++;
  const epoch = state.epoch;
  const [user, config, workspaces] = await Promise.all([api('/api/auth/me'), api('/api/system'), api('/api/workspaces')]);
  if (epoch !== state.epoch) return;
  state.user = user;
  state.config = config;
  state.workspaces = workspaces;
  const lastId = sessionStorage.getItem(`research-workspace-${user.id}`);
  await selectWorkspace(workspaces.find(workspace => workspace.id === lastId)?.id || workspaces[0]?.id);
}

function renderAuth() {
  app.innerHTML = `<div class="auth-layout">
    <section class="auth-story"><div class="brand"><span class="brand-mark">R</span><div><strong>Research Hub</strong><small>读有所获 · 行有所进</small></div></div>
      <div><span class="eyebrow" style="color:#b4c99c">YOUR RESEARCH, CONNECTED</span><h1>把每一次阅读，<br>连接到下一步研究。</h1><p>为课题组整理文献、准备组会、跟进待办。让资料和进展，留在同一个地方。</p>
      <div class="auth-steps"><div class="auth-step"><span>01</span>积累论文与阅读笔记</div><div class="auth-step"><span>02</span>准备有据可查的组会草稿</div><div class="auth-step"><span>03</span>把讨论变成下一步行动</div></div></div>
      <div class="auth-foot">RESEARCH HUB / 科研协作工作台</div></section>
    <main class="auth-form-area" id="main"><div class="auth-box"><span class="eyebrow">WELCOME TO YOUR WORKSPACE</span><h2 style="margin-top:15px">${state.authMode === 'login' ? '欢迎回来' : '开始你的科研空间'}</h2><p class="muted">${state.authMode === 'login' ? '登录后，继续上一次的阅读与思考。' : '创建账号，和课题组一起积累与推进。'}</p>
      <div class="auth-tabs"><button data-auth="login" class="${state.authMode === 'login' ? 'active' : ''}">登录</button><button data-auth="register" class="${state.authMode === 'register' ? 'active' : ''}">注册账号</button></div>
      <form id="auth-form">${state.authMode === 'register' ? '<label class="field">你的称呼<input name="displayName" autocomplete="name" maxlength="80" required placeholder="例如，小杨"></label>' : ''}
      <label class="field">邮箱<input name="email" type="email" autocomplete="email" maxlength="254" required placeholder="you@university.edu.cn"></label>
      <label class="field">密码<input name="password" type="password" autocomplete="${state.authMode === 'login' ? 'current-password' : 'new-password'}" minlength="8" maxlength="72" required placeholder="至少 8 个字符"></label>
      <div class="form-error" role="alert"></div><button type="submit" class="button">${state.authMode === 'login' ? '进入工作台' : '创建账号'}${icon('arrow')}</button></form>
      <p class="fine-print">首次使用？注册后可创建课题组，或请组内管理员通过邮箱添加你。</p></div></main></div>`;
  $('#auth-form').addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      const result = await api(`/api/auth/${state.authMode}`, { method: 'POST', body: Object.fromEntries(data) });
      state.token = result.token;
      sessionStorage.setItem('research-token', result.token);
      state.view = 'home';
      await enter();
    });
  });
}

function renderShell(loading = false) {
  const title = navigation.find(item => item[0] === state.view)?.[1] || '工作概览';
  app.innerHTML = `<div class="shell"><aside class="sidebar">
    <div class="brand"><span class="brand-mark">R</span><div><strong>Research Hub</strong><small>科研协作工作台</small></div></div>
    <div class="workspace-switch"><label for="workspace-select">WORKSPACE / 课题空间</label><select id="workspace-select" aria-label="选择课题组">${state.workspaces.length ? state.workspaces.map(workspace => `<option value="${esc(workspace.id)}" ${workspace.id === state.workspace?.id ? 'selected' : ''}>${esc(workspace.name)}</option>`).join('') : '<option value="">尚未加入课题组</option>'}</select><button class="button ghost" data-action="workspace">${icon('plus')}新建课题组</button></div>
    <nav class="nav" aria-label="工作台导航">${navigation.map(([view, name, symbol]) => `<button data-view="${view}" class="${state.view === view ? 'active' : ''}" ${state.view === view ? 'aria-current="page"' : ''}>${icon(symbol)}${name}</button>`).join('')}</nav>
    <div class="sidebar-bottom"><p class="footer-note">每一次整理，<br>都为下一次讨论留下一点线索。</p><div class="account"><span class="avatar">${esc(state.user.displayName.slice(0, 1))}</span><div class="account-name"><strong>${esc(state.user.displayName)}</strong><small>${state.workspace?.role === 'OWNER' ? '课题组管理员' : '课题组成员'}</small></div><button data-action="logout" class="icon-button" aria-label="退出登录">${icon('logout')}</button></div></div>
    </aside><div class="content"><header class="topbar"><div class="breadcrumb"><span>课题空间</span><span>/</span><strong>${esc(state.workspace?.name || '欢迎使用')}</strong><span>/</span><span>${title}</span></div><div class="row"><span class="status mode-label">${state.config.generationMode === 'local' ? '本地摘录模式' : '模型生成模式'}</span><button data-action="members" class="button secondary small">${icon('users')}成员</button><button data-action="logout" class="icon-button mobile-logout" style="display:none" aria-label="退出登录">${icon('logout')}</button></div></header>
    <main class="content-body" id="main">${loading ? '<div class="loading">正在读取课题组资料…</div>' : ''}</main></div></div>`;
  $('#workspace-select').addEventListener('change', event => {
    if (!allowLeave()) { event.target.value = state.workspace?.id || ''; return; }
    selectWorkspace(event.target.value).catch(handleError);
  });
}

function renderPage() {
  if (!state.workspace) {
    $('#main').innerHTML = heading('LET’S BEGIN', '为研究留一个空间', '创建课题组，或者请管理员通过你的注册邮箱添加你。')
      + empty('还没有课题组', '论文、阅读记录和组会，将从这里开始。', '<button class="button" data-action="workspace">创建课题组</button>');
    return;
  }
  const pages = { home: renderHome, library: renderLibrary, meetings: renderMeetings, tasks: renderTasks, jobs: renderJobs, measurements: renderMeasurements };
  pages[state.view]();
}

function renderHome() {
  const pending = state.tasks.filter(task => ['TODO', 'IN_PROGRESS'].includes(task.status));
  const active = state.jobs.filter(job => ['QUEUED', 'RUNNING'].includes(job.status));
  const next = [...state.meetings].filter(meeting => meeting.meetingDate >= today()).sort((a, b) => a.meetingDate.localeCompare(b.meetingDate))[0];
  const stats = [
    ['文献资料', state.documents.length, '最近 100 条可见资料', 'book'],
    ['组会记录', state.meetings.length, '最近 100 次组会', 'calendar'],
    ['待推进事项', pending.length, '最近 200 项中的未完成事项', 'check'],
    ['正在处理', active.length, '自己发起的后台任务', 'activity'],
  ];
  $('#main').innerHTML = heading('RESEARCH OVERVIEW', `你好，${esc(state.user.displayName)}`, '从一篇论文、一次讨论，到一个有记录的下一步。', '<button class="button" data-action="import">' + icon('plus') + '导入资料</button>')
    + `<div class="stats">${stats.map(([name, count, note, symbol]) => `<div class="stat"><div><span class="muted">${name}</span><strong>${count}</strong><small>${note}</small></div>${icon(symbol)}</div>`).join('')}</div>
    <div class="two-column"><div class="stack"><section class="card hero"><span class="eyebrow">MAKE ROOM FOR YOUR NEXT IDEA</span><h2>${next ? '下一次组会，准备好了么？' : '把阅读记录，整理成一次讨论。'}</h2><p>${next ? `${esc(next.title)} · ${date(next.meetingDate)}<br>汇总共享资料，核对引用，再带着问题一起讨论。` : '记录阅读收获，关联原文出处，让组会准备更有条理。'}</p><button class="button" ${next ? `data-meeting="${esc(next.id)}"` : 'data-action="new-meeting"'}>${next ? '进入组会空间' : '安排一次组会'}${icon('arrow')}</button><span class="hero-mark">${icon('book')}</span></section>
    <section class="card"><div class="card-title"><h2>最近加入的文献</h2><button class="text-button" data-view="library">全部文献 →</button></div>${state.documents.length ? `<div class="list">${state.documents.slice(0, 4).map(documentRow).join('')}</div>` : empty('第一份材料，等你加入', '支持 PDF、Markdown 和文本。', '<button class="button secondary small" data-action="import">导入资料</button>')}</section></div>
    <div class="stack"><section class="card"><div class="card-title"><h2>接下来要做</h2><button class="text-button" data-view="tasks">查看清单 →</button></div>${pending.length ? `<div class="list">${pending.slice(0, 4).map(task => `<div class="list-row"><span class="list-icon">${icon('check')}</span><div class="list-copy"><strong>${esc(task.title)}</strong><small>${esc(task.assigneeName || '待分配')} · ${task.dueDate ? '截止 ' + date(task.dueDate) : '未设截止日期'}</small></div>${badge(task.status)}</div>`).join('')}</div>` : empty('暂时没有待办', '在组会纪要中提取事项，审核后加入行动清单。')}</section>
    <section class="card"><span class="eyebrow">A SMALL RESEARCH ROUTINE</span><h2 style="margin-top:12px">让研究保持连贯</h2><div class="next-step"><span class="step-count">01</span><div><strong>留下一条阅读笔记</strong><p>记录收获、疑问和下一步想验证的事。</p></div></div><div class="next-step"><span class="step-count">02</span><div><strong>带着证据准备组会</strong><p>共享笔记进入草稿，引用可以回到原文。</p></div></div><div class="next-step"><span class="step-count">03</span><div><strong>把讨论继续下去</strong><p>确认待办、分配负责人，跟进完成状态。</p></div></div></section></div></div>`;
}

function documentRow(doc) {
  return `<div class="list-row"><span class="list-icon">${icon('file')}</span><div class="list-copy"><button class="text-button" data-document="${esc(doc.id)}"><strong>${esc(doc.title)}</strong></button><small>${esc(labels[doc.visibility])} · ${date(doc.createdAt)}${doc.pageCount ? ` · ${doc.pageCount} 页` : ''}</small></div>${badge(doc.status)}</div>`;
}

function renderLibrary() {
  $('#main').innerHTML = heading('READ & COLLECT', '文献与笔记', '存放资料，记下思考。私有资料仅对自己可见。', `<button class="button" data-action="import">${icon('plus')}导入资料</button>`)
    + `<div class="toolbar"><form class="search" id="search-form"><input name="q" aria-label="搜索资料" placeholder="搜索标题或正文关键词…" maxlength="200" required><button type="submit" class="button secondary">${icon('search')}搜索</button></form><button class="button ghost small" data-action="refresh">${icon('refresh')}刷新</button></div><div id="library-results">${libraryTable()}</div><p class="footer-note">显示最近 100 条可见资料。正文搜索采用关键词匹配，结果保留原文页号。</p>`;
  $('#search-form').addEventListener('submit', event => {
    event.preventDefault();
    const epoch = state.epoch;
    submit(event.currentTarget, async data => {
      const hits = await api(workspacePath('/search?q=' + encodeURIComponent(data.get('q'))));
      if (epoch !== state.epoch || state.view !== 'library') return;
      $('#library-results').innerHTML = `<div class="row spread" style="margin-bottom:18px"><span class="muted">找到 ${hits.length} 个相关片段</span><button class="text-button" data-action="clear-search">返回资料列表</button></div>`
        + (hits.length ? `<div class="stack">${hits.map(hit => `<article class="search-result"><div class="row spread"><h3><button class="text-button" data-document="${esc(hit.documentId)}">${esc(hit.title)}</button></h3><span class="status">第 ${hit.pageNumber} 页</span></div><p class="pre">${esc(hit.excerpt)}</p><small>片段 ${hit.chunkIndex + 1} · 关键词匹配分数 ${hit.score}</small></article>`).join('')}</div>` : empty('没有找到相关资料', '试试更短的关键词，或确认资料解析已完成。'));
    });
  });
}

function libraryTable() {
  if (!state.documents.length) return empty('从一份资料开始积累', '导入论文或粘贴文本，解析完成后可搜索正文、添加阅读笔记。', '<button class="button secondary" data-action="import">导入第一份资料</button>');
  return `<div class="card table-card table-scroll"><table class="data-table"><thead><tr><th>资料标题</th><th>可见范围</th><th>解析状态</th><th>加入日期</th><th></th></tr></thead><tbody>${state.documents.map(doc => `<tr><td><button class="text-button" data-document="${esc(doc.id)}">${esc(doc.title)}</button><small style="display:block;margin-top:6px">${esc(doc.filename)}${doc.pageCount ? ` · ${doc.pageCount} 页` : ''}</small></td><td>${esc(labels[doc.visibility])}</td><td>${badge(doc.status)}</td><td class="muted">${date(doc.createdAt)}</td><td><button class="button secondary small" data-document="${esc(doc.id)}">查看与笔记</button></td></tr>`).join('')}</tbody></table></div>`;
}

function importDialog() {
  openModal('加入一份研究资料', `<form id="import-form"><label class="field">资料标题<input name="title" required maxlength="240" placeholder="输入论文或资料名称"></label><label class="field">可见范围<select name="visibility"><option value="PRIVATE">仅自己可见</option><option value="GROUP">课题组共享 · 可用于组会草稿</option></select></label><div class="tabs"><button type="button" data-import-tab="file" class="active">上传文件</button><button type="button" data-import-tab="text">粘贴文本</button></div><div id="import-input"><label class="field">选择文件<input name="file" type="file" accept=".pdf,.md,.txt" required><small>支持文本型 PDF、Markdown、UTF-8 文本，最大 8MB。扫描件请先进行 OCR。</small></label></div><div class="form-error" role="alert"></div><div class="form-actions"><button type="button" data-close class="button secondary">取消</button><button type="submit" class="button">导入并解析</button></div></form>`);
  let inputMode = 'file';
  modal.querySelectorAll('[data-import-tab]').forEach(button => button.addEventListener('click', () => {
    inputMode = button.dataset.importTab;
    modal.querySelectorAll('[data-import-tab]').forEach(tab => tab.classList.toggle('active', tab === button));
    $('#import-input').innerHTML = inputMode === 'file'
      ? '<label class="field">选择文件<input name="file" type="file" accept=".pdf,.md,.txt" required><small>最大 8MB，文本型 PDF 最多 150 页。</small></label>'
      : '<label class="field">资料正文<textarea name="content" rows="9" maxlength="1000000" required placeholder="粘贴资料内容…"></textarea></label>';
  }));
  $('#import-form').addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      let result;
      if (inputMode === 'file') {
        const file = data.get('file');
        if (!file?.size || file.size > 8 * 1024 * 1024) throw new Error('请选择 1 字节到 8MB 的文件');
        result = await api(workspacePath('/documents'), { method: 'POST', body: data });
      } else result = await api(workspacePath('/documents/text'), { method: 'POST', body: Object.fromEntries(data) });
      modal.close();
      toast(result.duplicate ? '相同资料已经存在，已返回原记录；失败任务可在处理记录中重试' : '资料已加入，正在后台解析');
      if (await loadData()) { state.view = 'library'; renderShell(); renderPage(); }
    });
  });
}

async function documentDialog(id) {
  const epoch = state.epoch;
  const base = workspacePath('/documents/' + encodeURIComponent(id));
  const [doc, chunks, notes] = await Promise.all([api(base), api(base + '/chunks'), api(base + '/notes')]);
  if (epoch !== state.epoch) return;
  const own = notes.find(note => note.userId === state.user.id);
  const others = notes.filter(note => note.userId !== state.user.id);
  openModal(doc.title, `<div class="row wrap">${badge(doc.status)}<span class="status">${esc(labels[doc.visibility])}</span><small>${doc.pageCount} 页</small></div>${doc.error ? `<p class="notice error">${esc(doc.error)}</p>` : ''}
    ${!chunks.length ? '<p class="notice" style="margin-top:18px">正文尚未就绪。请在处理记录中查看进度、错误原因或重试。</p>' : `<details><summary>查看原文片段 · 共 ${chunks.length} 段</summary>${chunks.slice(0, 20).map(chunk => `<div class="chunk"><small>第 ${chunk.pageNumber} 页 · 片段 ${chunk.chunkIndex + 1}</small><div class="pre">${esc(chunk.content)}</div></div>`).join('')}${chunks.length > 20 ? '<small>页面展示前 20 段，完整数据可通过接口查看。</small>' : ''}</details>`}
    <div class="divider"></div><h3>我的阅读记录</h3><form id="note-form"><div class="field-row"><label class="field">阅读状态<select name="readingStatus">${['READING', 'DONE', 'BLOCKED'].map(status => `<option value="${status}" ${own?.readingStatus === status ? 'selected' : ''}>${labels[status]}</option>`).join('')}</select></label><label class="field">笔记可见范围<select name="visibility"><option value="PRIVATE">仅自己</option>${doc.visibility === 'GROUP' ? `<option value="GROUP" ${own?.visibility === 'GROUP' ? 'selected' : ''}>课题组共享</option>` : ''}</select></label></div><label class="field">阅读收获与待确认问题<textarea name="content" rows="6" maxlength="20000" placeholder="记下方法、实验条件、疑问和接下来的计划…">${esc(own?.content || '')}</textarea><small>共享笔记可用于组会草稿。当前记录版本为 ${own?.version || 0}。</small></label><div class="form-error" role="alert"></div><div class="form-actions"><button type="button" class="button secondary" data-reload-document="${esc(id)}">重新读取</button><button type="submit" class="button">保存阅读记录</button></div></form>
    ${others.length ? `<div class="divider"></div><h3>组员的共享笔记</h3>${others.map(note => `<div class="note-box"><div class="row spread"><strong>${esc(note.displayName)}</strong>${badge(note.readingStatus)}</div><p class="pre">${esc(note.content)}</p><small>${time(note.updatedAt)}</small></div>`).join('')}` : ''}`);
  $('#note-form').addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      await api(base + '/notes/mine', { method: 'PUT', body: { ...Object.fromEntries(data), version: own?.version || 0 } });
      toast('阅读记录已保存');
      await documentDialog(id);
    });
  });
}

function renderMeetings() {
  if (!state.meetings.some(meeting => meeting.id === state.selectedMeeting)) state.selectedMeeting = state.meetings[0]?.id || null;
  const meeting = state.meetings.find(item => item.id === state.selectedMeeting);
  $('#main').innerHTML = heading('PREPARE & DISCUSS', '组会空间', '准备草稿，记录讨论，再把决定变成可跟进的行动。', `<button class="button" data-action="new-meeting">${icon('plus')}新建组会</button>`)
    + (meeting ? `<div class="meeting-layout"><div class="meeting-list">${meetingListContent()}</div><section class="meeting-detail" id="meeting-detail"></section></div>`
      : empty('安排第一次组会', '选择阅读周期，汇总共享资料，提前留出核对草稿的时间。', '<button class="button secondary" data-action="new-meeting">新建组会</button>'));
  if (meeting) paintMeeting(meeting);
}

function meetingListContent() {
  return state.meetings.map(item => `<button class="meeting-item ${item.id === state.selectedMeeting ? 'selected' : ''}" data-meeting="${esc(item.id)}"><small>${date(item.meetingDate)}</small><strong>${esc(item.title)}</strong><small>${item.draft ? '已生成草稿' : '等待准备'} · ${item.minutes ? '已记录纪要' : '待记录纪要'}</small></button>`).join('');
}

function paintMeeting(meeting) {
  const editable = canEditMeeting(meeting);
  $('#meeting-detail').innerHTML = `<section class="card"><div class="meeting-header"><div><span class="eyebrow">${date(meeting.meetingDate)} / GROUP MEETING</span><h2 style="margin:12px 0 8px">${esc(meeting.title)}</h2><p class="muted" style="font-size:12px;margin:0">整理周期 ${date(meeting.periodStart)} — ${date(meeting.periodEnd)}（UTC）</p></div><button class="icon-button" data-action="refresh" aria-label="刷新组会">${icon('refresh')}</button></div>${!editable ? '<p class="notice" style="margin-top:18px">你可以查看共享组会。编辑和生成由组会创建者或管理员操作。</p>' : ''}</section>
    <section class="card"><div class="card-title"><div><h2>组会汇报草稿</h2><p class="muted" style="font-size:12px">汇总阅读记录与原文，生成后请核对结论。</p></div>${editable ? `<button class="button secondary small" data-generate="MEETING_DRAFT" data-id="${esc(meeting.id)}">${meeting.draft ? '重新生成' : '生成草稿'}</button>` : ''}</div><div id="draft-output">${draftContent(meeting.draft)}</div></section>
    <section class="card"><div class="card-title"><h2>组会纪要</h2><small>版本 ${meeting.minutesVersion}</small></div><form id="minutes-form" data-version="${meeting.minutesVersion}" data-meeting="${esc(meeting.id)}"><label class="field">讨论内容与下一步<textarea name="content" rows="7" maxlength="50000" required ${editable ? '' : 'readonly'} placeholder="记录讨论结论、已完成进展和后续安排…">${esc(meeting.minutes)}</textarea></label>${editable ? `<p class="notice">${state.config.generationMode === 'local' ? '本地模式下，请将明确事项单独写成 TODO:、待办：或 - [ ] 开头的行。' : '模型会提取纪要中的明确行动项，原文证据随提议一起保留。'} 保存纪要后再提取待办。</p><div class="form-error" role="alert"></div><div class="form-actions"><button type="button" class="button secondary" data-reload-meeting="${esc(meeting.id)}">重新读取</button><button type="submit" class="button">保存纪要</button></div>` : ''}</form></section>
    <section class="card"><div class="card-title"><div><h2>待确认的行动项</h2><p class="muted" style="font-size:12px">核对标题、负责人和日期，确认后加入行动清单。</p></div>${editable ? `<button class="button secondary small" data-generate="MINUTE_ACTIONS" data-id="${esc(meeting.id)}" ${!meeting.minutes || meeting.proposals ? 'disabled' : ''}>提取待办</button>` : ''}</div><div id="proposal-output">${proposalContent(meeting, editable)}</div></section>`;
  if (editable) {
    $('#minutes-form').addEventListener('submit', event => {
      event.preventDefault();
      const form = event.currentTarget;
      submit(form, async data => {
        const updated = await api(workspacePath(`/meetings/${meeting.id}/minutes`), {
          method: 'PUT', body: { content: data.get('content'), version: Number(form.dataset.version) },
        });
        replaceMeeting(updated);
        paintMeeting(updated);
        toast('纪要已保存，旧的未确认提议已清除');
      });
    });
    bindConfirmation(meeting);
  }
}

function draftContent(draft) {
  if (!draft) return empty('草稿将从共享记录中整理', '先导入共享资料、记下阅读收获，再点击生成草稿。');
  return `<p class="notice">${draft.mode === 'local' ? '本地摘录模式 · 当前内容为原文与记录的汇总。' : '模型生成模式 · 已检查引用是否存在，结论是否准确仍需人工核对。'}<br>${esc(draft.coverage)}</p><div>${draft.points.map(point => `<article class="draft-point"><p>${esc(point.text)}</p>${point.citations.map(citation => {
    const source = draft.sources.find(item => item.id === citation.sourceId);
    return `<details class="citation"><summary>${esc(source?.title || citation.sourceId)}${source?.pageNumber ? ` · 第 ${source.pageNumber} 页` : ''} · 查看原文</summary><blockquote>${esc(citation.quote)}</blockquote>${source?.type === 'DOCUMENT' || source?.type === 'NOTE' ? `<button class="text-button" data-document="${esc(source.resourceId)}">打开资料与笔记 →</button>` : ''}</details>`;
  }).join('')}</article>`).join('')}</div><p class="footer-note">生成时间 ${time(draft.generatedAt)}${draft.promptTokens != null ? ` · 输入 ${draft.promptTokens} / 输出 ${draft.completionTokens ?? '未返回'} Token` : ''}</p>`;
}

function memberOptions(selected = '') {
  return `<option value="">待分配</option>${state.members.map(member => `<option value="${esc(member.userId)}" ${selected === member.userId ? 'selected' : ''}>${esc(member.displayName)}</option>`).join('')}`;
}

function proposalContent(meeting, editable) {
  const items = meeting.proposals?.items;
  if (!items) return empty('先记录，再确认', '保存文字纪要并提取待办，提议会显示在这里。');
  if (!items.length) return empty('没有提取到明确事项', '可以补充纪要中的具体行动，保存后重新提取。');
  if (!editable) return items.map(item => `<div class="proposal"><strong>${esc(item.title)}</strong><p class="pre muted">${esc(item.evidenceQuote)}</p></div>`).join('');
  return `<form id="confirm-form" data-version="${meeting.proposalsVersion}">${items.map((item, index) => `<div class="proposal" data-proposal="${index}"><label class="check"><input name="selected" type="checkbox" value="${index}" aria-label="选择第 ${index + 1} 项待办"><strong>事项 ${index + 1}</strong></label><p class="evidence">纪要原文 · ${esc(item.evidenceQuote)}</p><label class="field">待办标题<input name="title" value="${esc(item.title)}" required maxlength="300"></label><div class="field-row"><label class="field">负责人<select name="assigneeId">${memberOptions()}</select></label><label class="field">截止日期<input name="dueDate" type="date"></label></div></div>`).join('')}<div class="form-error" role="alert"></div><div class="form-actions"><button type="submit" class="button">确认选中的待办</button></div></form>`;
}

function bindConfirmation(meeting) {
  $('#confirm-form')?.addEventListener('submit', event => {
    event.preventDefault();
    const form = event.currentTarget;
    submit(form, async () => {
      const selected = [...form.querySelectorAll('[name="selected"]:checked')];
      if (!selected.length) throw new Error('请至少勾选一项要创建的待办');
      const items = selected.map(checkbox => {
        const row = checkbox.closest('[data-proposal]');
        return { index: Number(checkbox.value), title: $('[name="title"]', row).value,
          assigneeId: $('[name="assigneeId"]', row).value || null, dueDate: $('[name="dueDate"]', row).value || null };
      });
      await api(workspacePath(`/meetings/${meeting.id}/confirm-actions`), {
        method: 'POST', body: { proposalVersion: Number(form.dataset.version), items },
      });
      selected.forEach(checkbox => { checkbox.checked = false; });
      toast('待办已确认，可到行动清单查看；相同确认会返回原任务');
      await loadData();
    });
  });
}

function replaceMeeting(meeting) {
  state.meetings = state.meetings.map(item => item.id === meeting.id ? meeting : item);
  const list = $('.meeting-list');
  if (list) list.innerHTML = meetingListContent();
}

function newMeetingDialog() {
  openModal('安排一次组会', `<form id="meeting-form"><label class="field">组会主题<input name="title" required maxlength="240" placeholder="例如，检索增强生成 · 本周阅读与实验"></label><label class="field">组会日期<input name="meetingDate" type="date" value="${today()}" required></label><div class="field-row"><label class="field">整理周期起始<input name="periodStart" type="date" value="${today(-7)}" required></label><label class="field">整理周期结束<input name="periodEnd" type="date" value="${today()}" required></label></div><p class="notice">按 UTC 日期汇总周期内的共享阅读记录、相关共享资料及未完成待办，最多覆盖 94 天。</p><div class="form-error" role="alert"></div><div class="form-actions"><button type="button" data-close class="button secondary">取消</button><button type="submit" class="button">创建组会</button></div></form>`);
  $('#meeting-form').addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      if (data.get('periodStart') > data.get('periodEnd')) throw new Error('整理周期的起始日期不能晚于结束日期');
      const result = await api(workspacePath('/meetings'), { method: 'POST', body: Object.fromEntries(data) });
      modal.close();
      if (await loadData()) {
        state.selectedMeeting = result.id;
        state.view = 'meetings';
        renderShell(); renderPage();
      }
      toast('组会已创建，可以开始准备草稿');
    });
  });
}

async function generate(button) {
  const { id, generate: kind } = button.dataset;
  if (kind === 'MINUTE_ACTIONS' && minutesDirty()) throw new Error('纪要有未保存修改，请先保存再提取待办');
  const key = crypto.randomUUID();
  button.disabled = true;
  try {
    const suffix = kind === 'MEETING_DRAFT' ? 'draft-jobs' : 'action-jobs';
    const job = await api(workspacePath(`/meetings/${id}/${suffix}`), { method: 'POST', key });
    state.jobs = [job, ...state.jobs.filter(item => item.id !== job.id)];
    toast('任务已提交，完成后会更新结果，也可在处理记录中查看进度');
  } finally { button.disabled = false; }
}

function renderTasks() {
  $('#main').innerHTML = heading('TURN DISCUSSION INTO ACTION', '行动清单', '明确下一步，在下一次讨论前看见进展。', '<button class="button secondary" data-action="refresh">' + icon('refresh') + '刷新</button>')
    + `<div class="toolbar"><span class="muted">最近 200 项课题组待办</span><select id="task-filter" class="filter" aria-label="筛选待办状态"><option value="">全部状态</option>${['TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED'].map(status => `<option value="${status}">${labels[status]}</option>`).join('')}</select></div><div id="task-results">${taskTable(state.tasks)}</div>`;
  $('#task-filter').addEventListener('change', event => {
    $('#task-results').innerHTML = taskTable(state.tasks.filter(task => !event.target.value || task.status === event.target.value));
  });
}

function taskTable(tasks) {
  if (!tasks.length) return empty('这里还没有对应的待办', '先在组会中保存纪要，提取并确认待办事项。', '<button class="button secondary" data-view="meetings">进入组会空间</button>');
  return `<div class="card table-card table-scroll"><table class="data-table"><thead><tr><th>待办事项</th><th>负责人</th><th>截止日期</th><th>状态</th><th>操作</th></tr></thead><tbody>${tasks.map(task => {
    const meeting = state.meetings.find(item => item.id === task.meetingId);
    const options = ['DONE', 'CANCELLED'].includes(task.status) ? [['TODO', '重新打开']]
      : [['IN_PROGRESS', '开始'], ['DONE', '完成'], ['CANCELLED', '取消']].filter(([status]) => status !== task.status);
    return `<tr><td><strong>${esc(task.title)}</strong><small style="display:block;margin-top:6px"><button class="text-button" data-meeting="${esc(task.meetingId)}">${esc(meeting?.title || '查看原组会')}</button></small></td><td>${esc(task.assigneeName || '待分配')}</td><td>${date(task.dueDate)}</td><td>${badge(task.status)}</td><td><div class="row wrap">${options.map(([status, text]) => `<button class="button secondary small" data-task="${esc(task.id)}" data-status="${status}" data-version="${task.version}">${text}</button>`).join('')}</div></td></tr>`;
  }).join('')}</tbody></table></div><p class="footer-note">负责人、待办创建者及管理员可以更新状态；他人的更新可能需要先刷新后再操作。</p>`;
}

async function transition(button) {
  button.disabled = true;
  try {
    const updated = await api(workspacePath('/tasks/' + button.dataset.task), {
      method: 'PATCH', body: { status: button.dataset.status, version: Number(button.dataset.version) },
    });
    state.tasks = state.tasks.map(task => task.id === updated.id ? updated : task);
    const filter = $('#task-filter')?.value;
    $('#task-results').innerHTML = taskTable(state.tasks.filter(task => !filter || task.status === filter));
    toast('待办状态已更新');
  } finally { button.disabled = false; }
}

function renderJobs() {
  $('#main').innerHTML = heading('EVERY STEP, RECORDED', '处理记录', '查看自己发起的后台任务，遇到问题时检查原因并重试。', `<button class="button secondary" data-action="refresh">${icon('refresh')}刷新</button>`)
    + (state.jobs.length ? `<div class="card table-card table-scroll"><table class="data-table"><thead><tr><th>处理内容</th><th>状态</th><th>执行次数</th><th>发起时间</th><th>操作</th></tr></thead><tbody>${state.jobs.map(job => `<tr><td><button class="text-button" data-job="${esc(job.id)}">${esc(labels[job.kind])}</button><small style="display:block;margin-top:6px">${esc(resourceTitle(job))}</small>${job.error ? `<small style="display:block;color:var(--red);margin-top:6px">${esc(job.error)}</small>` : ''}</td><td>${badge(job.status)}</td><td>${job.attempts}</td><td class="muted">${time(job.createdAt)}</td><td><div class="row wrap"><button class="button secondary small" data-job="${esc(job.id)}">查看步骤</button>${['FAILED', 'CANCELLED'].includes(job.status) ? `<button class="button secondary small" data-job-control="retry" data-id="${esc(job.id)}">重试</button>` : ['RUNNING', 'QUEUED'].includes(job.status) ? `<button class="button danger small" data-job-control="cancel" data-id="${esc(job.id)}">取消</button>` : ''}</div></td></tr>`).join('')}</tbody></table></div>` : empty('还没有处理记录', '导入资料或生成组会草稿后，这里会记录处理步骤。'))
    + '<p class="footer-note">自动更新正在处理的任务。最近 100 条记录仅展示自己发起的任务。</p>';
}

function resourceTitle(job) {
  return (job.kind === 'IMPORT' ? state.documents : state.meetings).find(item => item.id === job.resourceId)?.title || job.resourceId;
}

async function jobDialog(id) {
  const epoch = state.epoch;
  const [job, events] = await Promise.all([api(`/api/jobs/${id}`), api(`/api/jobs/${id}/events`)]);
  if (epoch !== state.epoch) return;
  openModal(labels[job.kind], `<div class="row spread wrap">${badge(job.status)}<button class="button secondary small" data-job="${esc(id)}">刷新步骤</button></div><p class="muted" style="margin-top:15px">${esc(resourceTitle(job))}</p>${job.error ? `<p class="notice error">${esc(job.error)}</p>` : ''}<ol class="job-detail-events">${events.map(event => `<li><strong>${esc(event.phase)}</strong><small>${time(event.createdAt)}</small><p>${esc(event.message)}</p></li>`).join('')}</ol><p class="footer-note mono">任务 ${esc(id)}</p>`);
}

function renderMeasurements() {
  $('#main').innerHTML = heading('MEASURE THE CHANGE', '使用评估', '记录真实任务的对比结果，把审核和修改耗时也算进去。', `<button class="button" data-action="measurement">${icon('plus')}记录一次对比</button>`)
    + '<p class="notice">这些数据来自用户手动记录。请使用难度接近的任务做对比，并如实保留质量未通过的样本。页面只展示已保存的数据。</p>'
    + (state.summaries.length ? `<div class="grid" style="margin-top:22px">${state.summaries.map(summary => `<section class="card"><div class="card-title"><h2>${labels[summary.scenario]}</h2><span class="status">${summary.samples} 组样本 · ${summary.participants} 位参与者</span></div><div class="metric-columns"><div><small>人工流程耗时中位数</small><div class="measurement-value">${minutes(summary.medianBaselineMs)} <small>分钟</small></div></div><div><small>辅助流程（含审核）中位数</small><div class="measurement-value">${minutes(summary.medianAssistedIncludingReviewMs)} <small>分钟</small></div></div></div><div class="divider"></div><div class="row spread quality"><span>配对耗时变化 <strong>${summary.medianReductionPercent.toFixed(1)}%</strong></span><span>质量通过率 <strong>${(summary.acceptanceRate * 100).toFixed(1)}%</strong></span></div><p class="footer-note">正值表示耗时减少，负值表示耗时增加。比例为逐组计算后的中位数，质量未通过样本也计入。</p></section>`).join('')}</div>` : empty('从一次真实使用开始', '记录原流程耗时、辅助流程操作耗时和审核耗时，积累可信的比较。'))
    + '<div style="margin-top:20px"><button class="button secondary" data-action="measurement-records">查看原始记录</button></div>';
}

function minutes(value) { return (Number(value) / 60000).toFixed(1); }

function measurementDialog() {
  openModal('记录一次任务对比', `<form id="measurement-form"><label class="field">任务类型<select name="scenario">${['MEETING_PREPARATION', 'SOURCE_LOOKUP', 'ACTION_EXTRACTION'].map(scenario => `<option value="${scenario}">${labels[scenario]}</option>`).join('')}</select></label><label class="field">原流程耗时（分钟）<input name="baseline" type="number" min="0.001" max="10080" step="0.001" required placeholder="实际记录的人工处理时间"></label><div class="field-row"><label class="field">辅助流程操作耗时（分钟）<input name="assisted" type="number" min="0" max="10080" step="0.001" required></label><label class="field">审核与修改耗时（分钟）<input name="review" type="number" min="0" max="10080" step="0.001" required></label></div><label class="check"><input type="checkbox" name="qualityAccepted">审核后达到本次任务的质量要求</label><label class="field">实验条件与观察<textarea name="notes" rows="3" maxlength="1000" placeholder="记录资料规模、任务难度、错误和修改情况…"></textarea></label><div class="form-error" role="alert"></div><div class="form-actions"><button type="button" data-close class="button secondary">取消</button><button type="submit" class="button">保存对比记录</button></div></form>`);
  $('#measurement-form').addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      await api(workspacePath('/measurements'), { method: 'POST', body: {
        scenario: data.get('scenario'), baselineMs: Math.round(Number(data.get('baseline')) * 60000),
        assistedMs: Math.round(Number(data.get('assisted')) * 60000), reviewMs: Math.round(Number(data.get('review')) * 60000),
        qualityAccepted: data.has('qualityAccepted'), notes: data.get('notes'),
      } });
      modal.close();
      if (await loadData()) renderPage();
      toast('对比记录已保存');
    });
  });
}

async function measurementRecordsDialog() {
  const epoch = state.epoch;
  const records = await api(workspacePath('/measurements/records'));
  if (epoch !== state.epoch) return;
  openModal('原始对比记录', records.length ? records.map(record => `<article class="note-box"><div class="row spread"><strong>${labels[record.scenario]}</strong><small>${time(record.createdAt)}</small></div><p>原流程 ${minutes(record.baselineMs)} 分钟 / 辅助操作 ${minutes(record.assistedMs)} 分钟 / 审核 ${minutes(record.reviewMs)} 分钟</p><p class="pre">${esc(record.notes)}</p><span class="status ${record.qualityAccepted ? 'success' : 'error'}">${record.qualityAccepted ? '质量通过' : '质量未通过'}</span></article>`).join('') : empty('还没有对比记录', '先记录一次真实使用，再回来查看。'));
}

function workspaceDialog() {
  openModal('创建课题组', '<form id="workspace-form"><label class="field">课题组名称<input name="name" required maxlength="120" placeholder="例如，智能系统研究组"></label><p class="notice">创建后你将成为管理员，可以通过成员的注册邮箱添加同学。</p><div class="form-error" role="alert"></div><div class="form-actions"><button type="button" class="button secondary" data-close>取消</button><button type="submit" class="button">创建课题组</button></div></form>');
  $('#workspace-form').addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      const created = await api('/api/workspaces', { method: 'POST', body: Object.fromEntries(data) });
      state.workspaces = await api('/api/workspaces');
      modal.close();
      state.view = 'home';
      await selectWorkspace(created.id);
      toast('课题组已创建，先加入一份资料吧');
    });
  });
}

function membersDialog() {
  if (!state.workspace) { workspaceDialog(); return; }
  openModal('课题组成员', `<div>${state.members.map(member => `<div class="member-line row"><span class="avatar">${esc(member.displayName.slice(0, 1))}</span><div class="list-copy"><strong>${esc(member.displayName)}</strong><small>${esc(member.email)}</small></div><span class="status">${member.role === 'OWNER' ? '管理员' : '成员'}</span></div>`).join('')}</div>${state.workspace.role === 'OWNER' ? '<div class="divider"></div><form id="member-form"><label class="field">添加已注册成员<input type="email" name="email" required maxlength="254" placeholder="输入成员的注册邮箱"></label><div class="form-error" role="alert"></div><div class="form-actions"><button type="submit" class="button">添加成员</button></div></form>' : '<p class="footer-note">需要添加成员时，请联系管理员。</p>'}`);
  $('#member-form')?.addEventListener('submit', event => {
    event.preventDefault();
    submit(event.currentTarget, async data => {
      state.members = await api(workspacePath('/members'), { method: 'POST', body: Object.fromEntries(data) });
      membersDialog();
      toast('成员已加入课题组');
    });
  });
}

function minutesDirty() {
  const form = $('#minutes-form');
  if (!form) return false;
  const textarea = $('[name="content"]', form);
  return textarea.value !== textarea.defaultValue;
}

function modalDirty() {
  if (!modal.open) return false;
  return [...modal.querySelectorAll('input:not([type=file]),textarea,select')].some(input => {
    if (input.type === 'checkbox') return input.checked !== input.defaultChecked;
    if (input.tagName === 'SELECT') {
      const original = [...input.options].find(option => option.defaultSelected) || input.options[0];
      return input.value !== original?.value;
    }
    return input.value !== input.defaultValue;
  }) || [...modal.querySelectorAll('input[type=file]')].some(input => input.files.length);
}

function allowLeave() {
  return !minutesDirty() || window.confirm('纪要有未保存的修改，确定离开并放弃这些修改吗？');
}

function handleError(error) {
  if (error.name !== 'AbortError') toast(error.message || '操作暂时无法完成，请重试', true);
}

async function refresh() {
  if (!allowLeave()) return;
  if (await loadData()) renderPage();
}

async function pollJobs() {
  if (state.pollBusy || !state.user || !state.workspace) return;
  if (!state.jobs.some(job => ['QUEUED', 'RUNNING'].includes(job.status))
      && !state.documents.some(doc => ['QUEUED', 'PROCESSING'].includes(doc.status))) return;
  const epoch = state.epoch;
  state.pollBusy = true;
  try {
    const jobs = await api(workspacePath('/jobs'));
    if (epoch !== state.epoch) return;
    const changed = jobs.some(job => state.jobs.find(old => old.id === job.id)?.status !== job.status);
    state.jobs = jobs;
    if (!changed) return;
    const previousVersion = Number($('#minutes-form')?.dataset.version);
    const previousDraft = JSON.stringify(state.meetings.find(meeting => meeting.id === state.selectedMeeting)?.draft);
    if (!(await loadData()) || epoch !== state.epoch) return;
    if (state.view === 'meetings') {
      const list = $('.meeting-list');
      if (list) list.innerHTML = meetingListContent();
      const meeting = state.meetings.find(item => item.id === state.selectedMeeting);
      if (meeting) {
        const output = $('#draft-output');
        if (output) output.innerHTML = draftContent(meeting.draft);
        // 纪要表单不随轮询重绘，版本一致且尚未开始确认时才填入新提议。
        const proposalOutput = $('#proposal-output');
        if (proposalOutput && !$('#confirm-form') && previousVersion === meeting.minutesVersion) {
          proposalOutput.innerHTML = proposalContent(meeting, canEditMeeting(meeting));
          bindConfirmation(meeting);
        }
        const extractButton = $('[data-generate="MINUTE_ACTIONS"]');
        if (extractButton) extractButton.disabled = !meeting.minutes || !!meeting.proposals;
        if (previousDraft !== JSON.stringify(meeting.draft) && meeting.draft) toast('组会草稿已更新，请核对引用和内容');
      }
    } else if (!modal.open && ['home', 'jobs'].includes(state.view)) renderPage();
    else if (state.view === 'library' && $('#library-results .data-table')) $('#library-results').innerHTML = libraryTable();
  } catch (error) {
    if (error.status === 401) handleError(error);
  } finally { state.pollBusy = false; }
}

async function click(event) {
  const target = event.target.closest('button');
  if (!target || target.disabled) return;
  if (target.hasAttribute('data-close')) {
    if (!modalDirty() || window.confirm('有未保存的输入，确定关闭吗？')) modal.close();
    return;
  }
  if (target.dataset.auth) { state.authMode = target.dataset.auth; renderAuth(); return; }
  if (target.dataset.view) {
    if (!allowLeave()) return;
    state.view = target.dataset.view;
    renderShell(); renderPage();
    return;
  }
  if (target.dataset.document) return documentDialog(target.dataset.document);
  if (target.dataset.reloadDocument) {
    if (modalDirty() && !window.confirm('重新读取会放弃未保存的笔记，继续吗？')) return;
    return documentDialog(target.dataset.reloadDocument);
  }
  if (target.dataset.meeting || target.dataset.reloadMeeting) {
    if (!allowLeave()) return;
    const id = target.dataset.meeting || target.dataset.reloadMeeting;
    const epoch = state.epoch;
    const meeting = await api(workspacePath('/meetings/' + id));
    if (epoch !== state.epoch) return;
    if (state.meetings.some(item => item.id === id)) replaceMeeting(meeting); else state.meetings.push(meeting);
    state.selectedMeeting = id; state.view = 'meetings';
    renderShell(); renderPage();
    return;
  }
  if (target.dataset.generate) return generate(target);
  if (target.dataset.task) return transition(target);
  if (target.dataset.job) return jobDialog(target.dataset.job);
  if (target.dataset.jobControl) {
    if (target.dataset.jobControl === 'cancel' && !window.confirm('确定取消这次处理吗？已有有效结果会保留，本次未完成结果会被丢弃。')) return;
    target.disabled = true;
    try {
      await api(`/api/jobs/${target.dataset.id}/${target.dataset.jobControl}`, { method: 'POST' });
      if (await loadData()) renderPage();
      toast(target.dataset.jobControl === 'retry' ? '任务已重新排队' : '任务已取消');
    } finally { target.disabled = false; }
    return;
  }
  const actions = {
    import: () => { if (state.workspace) importDialog(); else workspaceDialog(); },
    'new-meeting': () => { if (allowLeave()) { if (state.workspace) newMeetingDialog(); else workspaceDialog(); } },
    workspace: workspaceDialog, members: membersDialog, refresh,
    'clear-search': renderLibrary, measurement: measurementDialog, 'measurement-records': measurementRecordsDialog,
    logout: async () => {
      if (!allowLeave()) return;
      await api('/api/auth/logout', { method: 'POST' });
      resetSession(); renderAuth();
    },
  };
  if (actions[target.dataset.action]) return actions[target.dataset.action]();
}

document.addEventListener('click', event => { click(event).catch(handleError); });
modal.addEventListener('cancel', event => {
  if (modalDirty() && !window.confirm('有未保存的输入，确定关闭吗？')) event.preventDefault();
});
window.addEventListener('beforeunload', event => {
  if (minutesDirty() || modalDirty()) { event.preventDefault(); event.returnValue = ''; }
});

setInterval(pollJobs, 2000);
if (state.token) enter().catch(error => {
  if (!state.user) { resetSession(); renderAuth(); }
  else { renderShell(); $('#main').innerHTML = `<p class="notice error">${esc(error.message)}</p><button class="button" data-action="refresh">重新加载</button>`; }
  handleError(error);
});
else renderAuth();

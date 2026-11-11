/* 知识库页：列表 / 四步向导 / 详情页签，复用 app.js 的全局 api()/esc()/toast()/can()/state。 */
'use strict';

const KB_ROLE_RANK = { READER: 1, EDITOR: 2, ADMIN: 3, OWNER: 4 };
const KB_ROLE_LABEL = { READER: '只读', EDITOR: '编辑', ADMIN: '管理员', OWNER: '所有者' };
const KB_DISPLAY_LABEL = {
  NORMAL: '运行中', PARTIAL: '部分失败', INITIALIZING: '初始化中', EMPTY: '空知识库', DISABLED: '已停用',
};
const KB_SERIES_STATUS_LABEL = { DRAFT: '草稿', ACTIVE: '启用', DISABLED: '停用', DELETING: '删除中', DELETED: '已删除' };
const KB_INDEX_LABEL = { HYBRID: '混合检索', VECTOR: '向量检索', GRAPH: '图谱增强' };
const KB_DOC_LABEL = {
  PENDING: '排队中', PARSING: '解析中', CHUNKING: '分块中', INDEXING: '索引中',
  READY: '已就绪', FAILED: '失败', DISABLED: '已停用', DELETING: '清理中', DELETED: '已删除',
};
const KB_TASK_LABEL = {
  QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', CANCELING: '取消中', CANCELED: '已取消',
};
const KB_TASK_TYPE_LABEL = {
  INGEST: '文档入库', REBUILD: '重建索引', FULL_REBUILD: '全量重建', DELETE_CLEANUP: '删除清理', REPARSE: '重新解析',
};
const KB_TABS = [
  ['overview', '概览'], ['documents', '文档'], ['directories', '目录'], ['tags', '标签'],
  ['tasks', '任务'], ['retrieval', '检索'], ['members', '成员'], ['settings', '设置'],
];
const KB_WIZARD_STEPS = ['基础信息', '解析引擎', '分块设置', '高级设置'];

const KB = {
  ready: false,
  departments: null,
  view: 'list',
  listLoading: false,
  listError: '',
  list: { items: [], total: 0, page: 1, size: 12, keyword: '', scope: '', deptId: '', status: '' },
  series: null,
  tab: 'overview',
  tabLoading: false,
  data: null,
  docFilters: { directoryId: '', status: '', keyword: '' },
  selectedDocs: new Set(),
  taskStatus: '',
  retrievalForm: { query: '', topK: 5, threshold: 0.3, withAnswer: true },
  expandedParents: new Set(),
  uploading: false,
  wizard: null,
  configDraft: null,
  dialogSubmitHandler: null,
};

let kbPollTimer = null;

function kbEmptyData() {
  return {
    stats: null, docs: [], summary: null, dirs: [], tags: [], tasks: [], members: [],
    candidates: [], config: null, versions: [], retrieval: null,
  };
}

function kbRank() {
  return (KB.series && KB_ROLE_RANK[KB.series.role]) || 0;
}

function kbCan(minRole) {
  return kbRank() >= KB_ROLE_RANK[minRole];
}

function kbGet(root, path) {
  return path.split('.').reduce((acc, key) => (acc === null || acc === undefined ? undefined : acc[key]), root);
}

function kbSet(root, path, value) {
  const keys = path.split('.');
  let target = root;
  for (let i = 0; i < keys.length - 1; i += 1) {
    if (!target[keys[i]] || typeof target[keys[i]] !== 'object') target[keys[i]] = {};
    target = target[keys[i]];
  }
  target[keys[keys.length - 1]] = value;
}

function kbDeepMerge(base, patch) {
  if (patch === null || patch === undefined) return base;
  if (Array.isArray(patch)) return patch.slice();
  if (typeof patch !== 'object') return patch;
  const out = base && typeof base === 'object' && !Array.isArray(base) ? { ...base } : {};
  Object.keys(patch).forEach((key) => {
    out[key] = kbDeepMerge(base ? base[key] : undefined, patch[key]);
  });
  return out;
}

function kbClone(value) {
  return value === null || value === undefined ? value : JSON.parse(JSON.stringify(value));
}

function kbPill(tone, text) {
  return `<span class="kb-pill ${tone}">${esc(text)}</span>`;
}

function kbSeriesTone(status) {
  return { NORMAL: 'ok', PARTIAL: 'warn', INITIALIZING: 'info', EMPTY: 'muted', DISABLED: 'muted' }[status] || '';
}

function kbSeriesPill(series) {
  const display = series.displayStatus || series.status || '';
  return kbPill(kbSeriesTone(display), KB_DISPLAY_LABEL[display] || KB_SERIES_STATUS_LABEL[display] || display || '—');
}

function kbDocTone(status) {
  return { READY: 'ok', FAILED: 'bad', DISABLED: 'muted', PENDING: 'warn', PARSING: 'info', CHUNKING: 'info', INDEXING: 'info', DELETING: 'muted' }[status] || '';
}

function kbTaskTone(status) {
  return { SUCCEEDED: 'ok', FAILED: 'bad', RUNNING: 'info', QUEUED: 'warn', CANCELING: 'warn', CANCELED: 'muted' }[status] || '';
}

function kbFmtTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—';
}

function kbFmtBytes(value) {
  const size = Number(value || 0);
  if (size >= 1024 * 1024) return (size / (1024 * 1024)).toFixed(1) + ' MB';
  if (size >= 1024) return (size / 1024).toFixed(1) + ' KB';
  return size + ' B';
}

function kbFmtNum(value) {
  return Number(value || 0).toLocaleString('zh-CN');
}

function kbOptions(map, selected, placeholder) {
  const head = placeholder === undefined ? '' : `<option value="">${esc(placeholder)}</option>`;
  return head + Object.entries(map).map(([value, label]) => {
    const active = String(value) === String(selected) ? ' selected' : '';
    return `<option value="${esc(value)}"${active}>${esc(label)}</option>`;
  }).join('');
}

function kbIcon(name) {
  return typeof window.payrollAvatarMarkup === 'function' ? window.payrollAvatarMarkup(name) : '';
}

function kbDefaultConfig() {
  return {
    parse: {
      docTypes: ['PDF', 'DOCX', 'XLSX', 'MD'],
      perType: {
        PDF: { enabled: true, strategy: 'AUTO' },
        DOCX: { enabled: true, strategy: 'NATIVE' },
        XLSX: { enabled: true, strategy: 'NATIVE' },
        MD: { enabled: true, strategy: 'NATIVE' },
      },
      ocr: { languages: ['zh', 'en'], minConfidence: 0.6, keepLayout: true },
      options: { tableStructure: true, filterHeaderFooter: true, ocrEmbeddedImages: false },
    },
    chunk: {
      strategy: 'DEFAULT',
      common: { maxTokens: 512, overlapTokens: 64, minTokens: 50, keepTableWhole: true, tokenizer: 'cl100k_base' },
      byLine: { maxTokens: 512, dropEmpty: true, mergeShortLines: true },
      parentChild: { parentTokens: 1024, childTokens: 256, childOverlap: 32, maxChildrenPerParent: 8 },
      titleEnhanced: { template: '{doc} > {h1} > {h2}', keepTitlePath: true },
      custom: { separator: '\\n\\n', keepSeparator: true },
    },
    retrieval: {
      hybridEnabled: true,
      vectorWeight: 0.7,
      rerankEnabled: true,
      rerankModel: 'local-hash-rerank',
      citation: { enabled: true, granularity: 'PARAGRAPH' },
      recallTopK: 20,
      rerankTopN: 5,
      similarityThreshold: 0.3,
      noAnswerStrategy: 'EMPTY',
    },
  };
}

function kbDraftFromVersion(view) {
  const defaults = kbDefaultConfig();
  return {
    indexStrategy: view.indexStrategy || 'HYBRID',
    parse: kbDeepMerge(defaults.parse, view.parseConfig),
    chunk: kbDeepMerge(defaults.chunk, view.chunkConfig),
    retrieval: kbDeepMerge(defaults.retrieval, view.retrievalConfig),
    changeNote: '',
    rebuildMode: 'NONE',
  };
}

// ---------------------------------------------------------------- 初始化

function initKbTab() {
  const root = $('#kb-app');
  if (!root) return;
  if (!KB.ready) {
    root.addEventListener('click', kbOnClick);
    root.addEventListener('input', kbOnFieldInput);
    root.addEventListener('change', kbOnFieldChange);
    KB.ready = true;
  }
  ensureKbDepartments()
    .then(() => {
      if (KB.view === 'list') paintKb();
      else if (KB.view === 'wizard') paintKb();
    })
    .catch(() => {});
  if (KB.view === 'list') {
    loadKbList();
  } else if (KB.view === 'detail' && KB.series) {
    loadKbTabData();
  } else {
    paintKb();
  }
}

async function ensureKbDepartments() {
  if (KB.departments) return KB.departments;
  try {
    KB.departments = await api('/api/admin/departments?include_inactive=false');
  } catch (error) {
    KB.departments = [];
  }
  return KB.departments;
}

function paintKb() {
  const root = $('#kb-app');
  if (!root) return;
  if (KB.view === 'wizard') {
    root.innerHTML = renderKbWizard();
  } else if (KB.view === 'detail' && KB.series) {
    root.innerHTML = renderKbDetail();
  } else {
    root.innerHTML = renderKbList();
  }
}

// ---------------------------------------------------------------- 列表

async function loadKbList() {
  stopKbPolling();
  KB.view = 'list';
  KB.listLoading = true;
  KB.listError = '';
  paintKb();
  try {
    const params = new URLSearchParams();
    if (KB.list.keyword) params.set('keyword', KB.list.keyword);
    if (KB.list.scope) params.set('scope', KB.list.scope);
    if (KB.list.deptId) params.set('dept_id', KB.list.deptId);
    if (KB.list.status) params.set('status', KB.list.status);
    params.set('page', KB.list.page);
    params.set('size', KB.list.size);
    const page = await api('/api/kb/series?' + params.toString());
    if (KB.view !== 'list') return;
    KB.list.items = (page && page.items) || [];
    KB.list.total = (page && page.total) || 0;
  } catch (error) {
    KB.listError = error.message || String(error);
    KB.list.items = [];
    KB.list.total = 0;
  } finally {
    KB.listLoading = false;
    if (KB.view === 'list') paintKb();
  }
}

function renderKbList() {
  const departments = KB.departments || [];
  const deptOptions = departments.length
    ? '<option value="">全部部门</option>' + departments.map((dept) => {
      const active = String(dept.id) === String(KB.list.deptId) ? ' selected' : '';
      return `<option value="${dept.id}"${active}>${esc(dept.name)}</option>`;
    }).join('')
    : '<option value="">全部部门</option>';
  const pages = Math.max(1, Math.ceil(KB.list.total / KB.list.size));
  const cards = KB.list.items.map(renderKbCard).join('');
  return `
  <div class="kb-view">
    <div class="kb-head">
      <div>
        <div class="kb-eyebrow">KNOWLEDGE BASE</div>
        <h1 class="kb-title">知识库</h1>
        <p class="kb-sub">创建知识库、维护解析与分块配置，并对已就绪文档进行混合检索与引用问答。</p>
      </div>
      <div class="kb-head-actions">
        <button type="button" class="primary" data-kb-action="create" ${can('kb:create') ? '' : 'disabled'}>+ 新建知识库</button>
      </div>
    </div>
    <div class="toolbar filter-bar kb-filters">
      <label class="filter-field"><span>关键词</span><input id="kb-filter-keyword" type="search" placeholder="名称或描述" value="${esc(KB.list.keyword)}"></label>
      <label class="filter-field"><span>范围</span><select id="kb-filter-scope">
        <option value=""${KB.list.scope === '' ? ' selected' : ''}>全部可见</option>
        <option value="mine"${KB.list.scope === 'mine' ? ' selected' : ''}>我创建的</option>
      </select></label>
      <label class="filter-field"><span>部门</span><select id="kb-filter-dept">${deptOptions}</select></label>
      <label class="filter-field"><span>状态</span><select id="kb-filter-status">
        ${kbOptions({ ACTIVE: '启用', DISABLED: '停用', DRAFT: '草稿' }, KB.list.status, '全部状态')}
      </select></label>
      <button type="button" class="primary" data-kb-action="search">查询</button>
      <button type="button" data-kb-action="reset-filters">重置</button>
      <span class="filter-actions"><span class="hint">共 ${kbFmtNum(KB.list.total)} 个知识库</span></span>
    </div>
    ${KB.listLoading
      ? '<p class="hint kb-placeholder">正在加载知识库…</p>'
      : KB.listError
        ? `<div class="kb-empty"><strong>加载失败</strong><p>${esc(KB.listError)}</p><button type="button" data-kb-action="retry">重试</button></div>`
        : KB.list.items.length
          ? `<div class="kb-card-grid">${cards}</div>`
          : '<div class="kb-empty"><strong>还没有可见的知识库</strong><p>新建一个知识库开始上传制度、手册与业务文档。</p></div>'}
    ${pages > 1 ? renderKbPager(pages) : ''}
  </div>`;
}

function renderKbCard(series) {
  const display = series.displayStatus || series.status;
  const admin = kbRank() >= KB_ROLE_RANK.ADMIN;
  const owner = kbRank() >= KB_ROLE_RANK.OWNER;
  return `
  <article class="kb-card">
    <div class="kb-card-head">
      <div class="kb-card-title">
        <button type="button" class="kb-title-link" data-kb-action="open" data-id="${series.id}">${esc(series.name)}</button>
        ${kbSeriesPill(series)}
      </div>
      ${kbPill('role', KB_ROLE_LABEL[series.role] || '只读')}
    </div>
    <p class="kb-card-desc">${esc(series.description) || '暂无简介'}</p>
    <div class="kb-card-meta">
      <span>${esc(series.deptName || '未分配部门')}</span>
      <span>${esc(series.authorName || '—')}</span>
      <span>${esc(KB_INDEX_LABEL[series.indexStrategy] || series.indexStrategy)}</span>
    </div>
    <div class="kb-card-stats">
      <span><strong>${kbFmtNum(series.docCount)}</strong>文档</span>
      <span><strong>${kbFmtNum(series.readyDocCount)}</strong>就绪</span>
      <span><strong>${kbFmtNum(series.failedDocCount)}</strong>失败</span>
      <span><strong>${kbFmtNum(series.chunkCount)}</strong>切片</span>
    </div>
    <div class="kb-card-foot">
      <span class="hint">更新于 ${kbFmtTime(series.updatedAt)}</span>
      <span class="kb-card-actions">
        <button type="button" class="link" data-kb-action="open" data-id="${series.id}">打开</button>
        ${admin && display !== 'DISABLED' ? `<button type="button" class="link" data-kb-action="series-disable" data-id="${series.id}">停用</button>` : ''}
        ${admin && display === 'DISABLED' ? `<button type="button" class="link" data-kb-action="series-enable" data-id="${series.id}">启用</button>` : ''}
        ${owner ? `<button type="button" class="link danger-text" data-kb-action="series-delete" data-id="${series.id}">删除</button>` : ''}
      </span>
    </div>
  </article>`;
}

function renderKbPager(pages) {
  const current = KB.list.page;
  const start = Math.max(1, Math.min(current - 2, pages - 4));
  const end = Math.min(pages, start + 4);
  let buttons = '';
  for (let page = start; page <= end; page += 1) {
    buttons += `<button type="button" class="kb-page${page === current ? ' active' : ''}" data-kb-action="page" data-page="${page}">${page}</button>`;
  }
  return `
  <div class="kb-pager">
    <button type="button" data-kb-action="page" data-page="${Math.max(1, current - 1)}" ${current <= 1 ? 'disabled' : ''}>上一页</button>
    ${buttons}
    <button type="button" data-kb-action="page" data-page="${Math.min(pages, current + 1)}" ${current >= pages ? 'disabled' : ''}>下一页</button>
    <span class="hint">第 ${current} / ${pages} 页</span>
  </div>`;
}

function kbApplyListFilters() {
  KB.list.keyword = ($('#kb-filter-keyword') || {}).value || '';
  KB.list.scope = ($('#kb-filter-scope') || {}).value || '';
  KB.list.deptId = ($('#kb-filter-dept') || {}).value || '';
  KB.list.status = ($('#kb-filter-status') || {}).value || '';
  KB.list.page = 1;
  loadKbList();
}

// ---------------------------------------------------------------- 四步向导

function kbStartWizard() {
  if (!can('kb:create')) {
    toast('当前账号没有新建知识库的权限', 'error');
    return;
  }
  const departments = (KB.departments || []).filter((dept) => dept.active !== false);
  const preferred = state.me && state.me.department_id
    ? departments.find((dept) => dept.id === state.me.department_id)
    : null;
  const config = kbDefaultConfig();
  KB.wizard = {
    step: 0,
    submitting: false,
    form: {
      name: '',
      deptId: preferred ? preferred.id : departments.length ? departments[0].id : '',
      description: '',
      indexStrategy: 'HYBRID',
      parse: config.parse,
      chunk: config.chunk,
      retrieval: config.retrieval,
    },
  };
  KB.view = 'wizard';
  paintKb();
  const root = $('#kb-app');
  if (root) root.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderKbWizard() {
  const wizard = KB.wizard;
  const steps = KB_WIZARD_STEPS.map((label, index) => {
    const cls = index === wizard.step ? 'active' : index < wizard.step ? 'done' : '';
    return `<div class="kb-step ${cls}"><span class="kb-step-no">${index + 1}</span><span class="kb-step-label">${esc(label)}</span></div>`;
  }).join('<span class="kb-step-line" aria-hidden="true"></span>');
  return `
  <div class="kb-view kb-wizard">
    <div class="kb-head">
      <div>
        <div class="kb-eyebrow">NEW KNOWLEDGE BASE</div>
        <h1 class="kb-title">新建知识库</h1>
        <p class="kb-sub">四步完成基础信息、解析引擎、分块与检索配置；创建后可在详情页继续调整。</p>
      </div>
      <div class="kb-head-actions">
        <button type="button" data-kb-action="wizard-cancel">取消</button>
      </div>
    </div>
    <div class="kb-steps">${steps}</div>
    <div class="kb-wizard-body">${renderKbWizardStep(wizard.step)}</div>
    <div class="kb-wizard-foot">
      <span class="hint">步骤 ${wizard.step + 1} / 4</span>
      <span class="kb-wizard-actions">
        ${wizard.step > 0 ? '<button type="button" data-kb-action="wizard-prev">上一步</button>' : ''}
        ${wizard.step < 3 ? '<button type="button" class="primary" data-kb-action="wizard-next">下一步</button>' : ''}
        ${wizard.step === 3 ? `<button type="button" class="primary" data-kb-action="wizard-submit" ${wizard.submitting ? 'disabled' : ''}>${wizard.submitting ? '创建中…' : '创建知识库'}</button>` : ''}
      </span>
    </div>
  </div>`;
}

function renderKbWizardStep(step) {
  if (step === 0) return renderKbWizardBasics();
  if (step === 1) return renderKbParseFields(KB.wizard.form, 'wfield', false);
  if (step === 2) return renderKbChunkFields(KB.wizard.form, 'wfield', false);
  return renderKbRetrievalFields(KB.wizard.form, 'wfield', false);
}

function renderKbWizardBasics() {
  const form = KB.wizard.form;
  const departments = (KB.departments || []).filter((dept) => dept.active !== false);
  const deptOptions = departments.map((dept) => {
    const active = String(dept.id) === String(form.deptId) ? ' selected' : '';
    return `<option value="${dept.id}"${active}>${esc(dept.name)}</option>`;
  }).join('');
  const choices = [
    ['HYBRID', '混合检索', '向量语义 + BM25 关键词融合，兼顾自然语言提问与精确匹配。'],
    ['VECTOR', '向量检索（RAG）', '基于文本向量的语义召回，适合描述型问题。'],
  ].map(([value, title, desc]) => `
    <label class="kb-choice${form.indexStrategy === value ? ' selected' : ''}">
      <input type="radio" name="kb-index-strategy" value="${value}" data-wfield="indexStrategy" data-wrepaint="1" ${form.indexStrategy === value ? 'checked' : ''}>
      <span class="kb-choice-title">${esc(title)}</span>
      <span class="kb-choice-desc">${esc(desc)}</span>
    </label>`).join('');
  return `
  <div class="kb-form-grid">
    <label class="span-2">知识库主题<input data-wfield="name" maxlength="50" value="${esc(form.name)}" placeholder="例如：薪酬绩效制度知识库" required></label>
    <label>所属部门<select data-wfield="deptId" required>${deptOptions || '<option value="">请选择部门</option>'}</select></label>
    <label>知识库简介（可选）<input data-wfield="description" maxlength="200" value="${esc(form.description)}" placeholder="简要说明涵盖范围或使用场景"></label>
  </div>
  <div class="kb-field-block">
    <span class="kb-field-label">索引策略</span>
    <div class="kb-choice-row">${choices}</div>
  </div>
  <p class="hint">索引策略在创建后仍可在「设置」中修改；修改配置会生成新版本，可选择重建文档索引。</p>`;
}

function renderKbParseFields(form, attr, compact) {
  const parse = form.parse;
  const types = [
    ['PDF', 'PDF', '制度、报告、扫描件'],
    ['DOCX', 'Word', '段落、标题与表格'],
    ['XLSX', 'Excel', '工作表与单元格文本'],
    ['MD', 'Markdown', '标题、列表与代码块'],
  ];
  const typeCards = types.map(([key, label, hint]) => {
    const checked = (parse.docTypes || []).includes(key);
    return `
      <label class="kb-choice kb-choice-sm${checked ? ' selected' : ''}">
        <input type="checkbox" value="${key}" data-${attr}="parse.docTypes" data-wlist="parse.docTypes" data-wrepaint="1" ${checked ? 'checked' : ''}>
        <span class="kb-choice-title">${esc(label)}</span>
        <span class="kb-choice-desc">${esc(hint)}</span>
      </label>`;
  }).join('');
  const perTypeRows = (parse.docTypes || []).map((key) => {
    const config = (parse.perType && parse.perType[key]) || { enabled: true, strategy: 'NATIVE' };
    const strategies = key === 'PDF'
      ? `<option value="AUTO"${config.strategy === 'AUTO' ? ' selected' : ''}>自动判断（优先文本，失败提示扫描件）</option>
         <option value="NATIVE"${config.strategy === 'NATIVE' ? ' selected' : ''}>仅文字提取</option>
         <option value="OCR" disabled>OCR 识别（未接入）</option>`
      : `<option value="NATIVE"${config.strategy === 'NATIVE' ? ' selected' : ''}>原生提取</option>
         <option value="OCR" disabled>OCR 识别（未接入）</option>`;
    return `<tr><td>${esc(key)}</td><td><select data-${attr}="parse.perType.${key}.strategy">${strategies}</select></td></tr>`;
  }).join('');
  const langZh = (parse.ocr.languages || []).includes('zh');
  const langEn = (parse.ocr.languages || []).includes('en');
  return `
  <div class="kb-field-block">
    <span class="kb-field-label">文档类型</span>
    <div class="kb-choice-row">${typeCards}</div>
  </div>
  ${perTypeRows ? `<div class="kb-field-block">
    <span class="kb-field-label">按类型解析策略</span>
    <div class="table-wrap"><table class="kb-mini-table"><thead><tr><th>文档类型</th><th>解析方式</th></tr></thead><tbody>${perTypeRows}</tbody></table></div>
  </div>` : ''}
  <div class="kb-form-grid">
    <div class="kb-field-block span-2">
      <span class="kb-field-label">解析选项</span>
      <div class="kb-switch-list">
        <label><input type="checkbox" data-${attr}="parse.options.tableStructure" ${parse.options.tableStructure ? 'checked' : ''}> 保留表格结构</label>
        <label><input type="checkbox" data-${attr}="parse.options.filterHeaderFooter" ${parse.options.filterHeaderFooter ? 'checked' : ''}> 过滤页眉页脚</label>
        <label><input type="checkbox" data-${attr}="parse.options.ocrEmbeddedImages" ${parse.options.ocrEmbeddedImages ? 'checked' : ''}> 识别内嵌图片文字（演示环境不启用）</label>
      </div>
    </div>
    <div class="kb-field-block span-2">
      <span class="kb-field-label">OCR（扫描件提示）</span>
      <div class="kb-form-grid">
        <label class="kb-inline-check"><input type="checkbox" value="zh" data-${attr}="parse.ocr.languages" data-wlist="parse.ocr.languages" ${langZh ? 'checked' : ''}> 中文</label>
        <label class="kb-inline-check"><input type="checkbox" value="en" data-${attr}="parse.ocr.languages" data-wlist="parse.ocr.languages" ${langEn ? 'checked' : ''}> 英文</label>
        <label>最低置信度<input type="number" min="0" max="1" step="0.05" data-${attr}="parse.ocr.minConfidence" value="${esc(parse.ocr.minConfidence)}"></label>
        <label class="kb-inline-check"><input type="checkbox" data-${attr}="parse.ocr.keepLayout" ${parse.ocr.keepLayout ? 'checked' : ''}> 保留版面布局</label>
      </div>
      ${compact ? '' : '<p class="hint">OCR 仅影响扫描件提示，演示环境不做真实识别。</p>'}
    </div>
  </div>`;
}

function renderKbChunkFields(form, attr, compact) {
  const chunk = form.chunk;
  const strategies = [
    ['DEFAULT', '默认切分', '按段落与句子边界装箱，保留重叠。'],
    ['BY_LINE', '按行切分', '以行为单位，可合并短行。'],
    ['PARENT_CHILD', '父子切分', '父块提供上下文，子块参与召回。'],
    ['TITLE_ENHANCED', '标题增强', '按标题层级组织，并拼接标题路径。'],
    ['CUSTOM', '自定义切分', '用正则分隔符按业务规则切分。'],
  ].map(([value, title, desc]) => `
    <label class="kb-choice kb-choice-sm${chunk.strategy === value ? ' selected' : ''}">
      <input type="radio" name="kb-chunk-strategy" value="${value}" data-${attr}="chunk.strategy" data-wrepaint="1" ${chunk.strategy === value ? 'checked' : ''}>
      <span class="kb-choice-title">${esc(title)}</span>
      <span class="kb-choice-desc">${esc(desc)}</span>
    </label>`).join('');
  let extra = '';
  if (chunk.strategy === 'BY_LINE') {
    extra = `<div class="kb-form-grid">
      <label>每行切片上限（tokens）<input type="number" min="64" max="4096" step="64" data-${attr}="chunk.byLine.maxTokens" value="${esc(chunk.byLine.maxTokens)}"></label>
      <div class="kb-field-block">
        <span class="kb-field-label">行处理</span>
        <div class="kb-switch-list">
          <label><input type="checkbox" data-${attr}="chunk.byLine.dropEmpty" ${chunk.byLine.dropEmpty ? 'checked' : ''}> 丢弃空行</label>
          <label><input type="checkbox" data-${attr}="chunk.byLine.mergeShortLines" ${chunk.byLine.mergeShortLines ? 'checked' : ''}> 合并过短的行</label>
        </div>
      </div>
    </div>`;
  } else if (chunk.strategy === 'PARENT_CHILD') {
    extra = `<div class="kb-form-grid">
      <label>父切片长度（tokens）<input type="number" min="256" max="4096" step="128" data-${attr}="chunk.parentChild.parentTokens" value="${esc(chunk.parentChild.parentTokens)}"></label>
      <label>子切片长度（tokens）<input type="number" min="64" max="2048" step="64" data-${attr}="chunk.parentChild.childTokens" value="${esc(chunk.parentChild.childTokens)}"></label>
      <label>子切片重叠（tokens）<input type="number" min="0" max="256" step="16" data-${attr}="chunk.parentChild.childOverlap" value="${esc(chunk.parentChild.childOverlap)}"></label>
      <label>每个父切片的子切片上限<input type="number" min="1" max="32" data-${attr}="chunk.parentChild.maxChildrenPerParent" value="${esc(chunk.parentChild.maxChildrenPerParent)}"></label>
    </div>`;
  } else if (chunk.strategy === 'TITLE_ENHANCED') {
    extra = `<div class="kb-form-grid">
      <label class="span-2">标题模板<input data-${attr}="chunk.titleEnhanced.template" value="${esc(chunk.titleEnhanced.template)}" placeholder="{doc} > {h1} > {h2}"></label>
      <label class="kb-inline-check"><input type="checkbox" data-${attr}="chunk.titleEnhanced.keepTitlePath" ${chunk.titleEnhanced.keepTitlePath ? 'checked' : ''}> 保留标题路径</label>
    </div>`;
  } else if (chunk.strategy === 'CUSTOM') {
    extra = `<div class="kb-form-grid">
      <label class="span-2">自定义分隔符（正则）<input data-${attr}="chunk.custom.separator" value="${esc(chunk.custom.separator)}" placeholder="\\n\\n"></label>
      <label class="kb-inline-check"><input type="checkbox" data-${attr}="chunk.custom.keepSeparator" ${chunk.custom.keepSeparator ? 'checked' : ''}> 保留分隔符</label>
    </div>`;
  }
  return `
  <div class="kb-field-block">
    <span class="kb-field-label">切片策略</span>
    <div class="kb-choice-row">${strategies}</div>
  </div>
  <div class="kb-form-grid">
    <label>最大切片长度（tokens）<input type="number" min="64" max="4096" step="64" data-${attr}="chunk.common.maxTokens" value="${esc(chunk.common.maxTokens)}"></label>
    <label>相邻切片重叠（tokens）<input type="number" min="0" max="2048" step="16" data-${attr}="chunk.common.overlapTokens" value="${esc(chunk.common.overlapTokens)}"></label>
    <label>最小切片长度（tokens）<input type="number" min="0" max="4096" step="10" data-${attr}="chunk.common.minTokens" value="${esc(chunk.common.minTokens)}"></label>
    <label class="kb-inline-check"><input type="checkbox" data-${attr}="chunk.common.keepTableWhole" ${chunk.common.keepTableWhole ? 'checked' : ''}> 保持表格完整</label>
  </div>
  ${extra}
  ${compact ? '' : '<p class="hint">默认参数适配大多数制度文档；长文档建议使用父子切分，表格建议按行切分。</p>'}`;
}

function renderKbRetrievalFields(form, attr, compact) {
  const retrieval = form.retrieval;
  return `
  <div class="kb-form-grid">
    <label class="kb-inline-check"><input type="checkbox" data-${attr}="retrieval.hybridEnabled" ${retrieval.hybridEnabled ? 'checked' : ''}> 混合检索（向量 + BM25）</label>
    <label>向量权重 <span class="hint" data-wout="retrieval.vectorWeight">${Math.round(Number(retrieval.vectorWeight) * 100)}%</span>
      <input type="range" min="0" max="1" step="0.05" data-${attr}="retrieval.vectorWeight" data-wformat="percent" value="${esc(retrieval.vectorWeight)}"></label>
    <label>TopK 召回数量<input type="number" min="1" max="100" data-${attr}="retrieval.recallTopK" value="${esc(retrieval.recallTopK)}"></label>
    <label class="kb-inline-check"><input type="checkbox" data-${attr}="retrieval.rerankEnabled" ${retrieval.rerankEnabled ? 'checked' : ''}> 启用本地精排</label>
    <label>精排返回数量<input type="number" min="1" max="20" data-${attr}="retrieval.rerankTopN" value="${esc(retrieval.rerankTopN)}"></label>
    <label>相似度阈值 <span class="hint" data-wout="retrieval.similarityThreshold">${Number(retrieval.similarityThreshold).toFixed(2)}</span>
      <input type="range" min="0" max="1" step="0.01" data-${attr}="retrieval.similarityThreshold" value="${esc(retrieval.similarityThreshold)}"></label>
    <label>引用粒度<select data-${attr}="retrieval.citation.granularity">
      ${kbOptions({ PARAGRAPH: '段落级', SENTENCE: '句子级', CHUNK: '切片级' }, retrieval.citation.granularity)}
    </select></label>
    <label class="kb-inline-check"><input type="checkbox" data-${attr}="retrieval.citation.enabled" ${retrieval.citation.enabled ? 'checked' : ''}> 生成引用来源</label>
    <label>无答案策略<select data-${attr}="retrieval.noAnswerStrategy">
      ${kbOptions({ EMPTY: '返回空结果', SUGGEST: '提示未找到' }, retrieval.noAnswerStrategy)}
    </select></label>
  </div>
  ${compact ? '' : '<p class="hint">检索仅读取已就绪文档的 ACTIVE 切片；精排数量不能大于召回数量。</p>'}`;
}

function kbWizardValidate(step) {
  const form = KB.wizard.form;
  if (step === 0) {
    if (!form.name || form.name.trim().length < 2 || form.name.trim().length > 50) {
      toast('知识库名称需为 2-50 个字符', 'error');
      return false;
    }
    if (!form.deptId) {
      toast('请选择所属部门', 'error');
      return false;
    }
    return true;
  }
  if (step === 1) {
    if (!form.parse.docTypes || !form.parse.docTypes.length) {
      toast('请至少选择一种文档类型', 'error');
      return false;
    }
    return true;
  }
  if (step === 2) {
    const common = form.chunk.common;
    if (common.maxTokens < 64 || common.maxTokens > 4096) {
      toast('最大切片长度需在 64-4096 tokens 之间', 'error');
      return false;
    }
    if (common.overlapTokens > Math.floor(common.maxTokens / 2)) {
      toast('重叠长度不能超过最大长度的一半', 'error');
      return false;
    }
    if (common.minTokens > common.maxTokens) {
      toast('最小长度不能大于最大长度', 'error');
      return false;
    }
    if (form.chunk.strategy === 'PARENT_CHILD') {
      const pc = form.chunk.parentChild;
      if (pc.childTokens >= pc.parentTokens) {
        toast('子切片长度必须小于父切片长度', 'error');
        return false;
      }
      if (pc.childOverlap >= Math.floor(pc.childTokens / 2)) {
        toast('子切片重叠需小于子切片长度的一半', 'error');
        return false;
      }
    }
    if (form.chunk.strategy === 'CUSTOM') {
      try {
        new RegExp(form.chunk.custom.separator);
      } catch (error) {
        toast('自定义分隔符不是合法的正则表达式', 'error');
        return false;
      }
    }
    return true;
  }
  if (form.retrieval.rerankTopN > form.retrieval.recallTopK) {
    toast('精排数量不能大于召回数量', 'error');
    return false;
  }
  return true;
}

async function kbWizardSubmit() {
  if (!kbWizardValidate(0) || !kbWizardValidate(2) || !kbWizardValidate(3)) return;
  const form = KB.wizard.form;
  KB.wizard.submitting = true;
  paintKb();
  try {
    const created = await api('/api/kb/series', {
      method: 'POST',
      body: {
        name: form.name.trim(),
        deptId: Number(form.deptId),
        description: form.description || '',
        indexStrategy: form.indexStrategy,
        draft: false,
        config: { parse: form.parse, chunk: form.chunk, retrieval: form.retrieval },
      },
    });
    KB.wizard = null;
    toast('知识库创建成功');
    await openKbSeries(created.id);
  } catch (error) {
    showError(error);
    if (KB.wizard) {
      KB.wizard.submitting = false;
      paintKb();
    }
  }
}

// ---------------------------------------------------------------- 详情

async function openKbSeries(seriesId) {
  stopKbPolling();
  try {
    const series = await api('/api/kb/series/' + seriesId);
    KB.series = series;
    KB.view = 'detail';
    KB.tab = 'overview';
    KB.data = kbEmptyData();
    KB.docFilters = { directoryId: '', status: '', keyword: '' };
    KB.selectedDocs.clear();
    KB.taskStatus = '';
    KB.retrievalForm = { query: '', topK: 5, threshold: 0.3, withAnswer: true };
    KB.expandedParents.clear();
    KB.configDraft = null;
    paintKb();
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

async function refreshKbSeries() {
  if (!KB.series) return null;
  const series = await api('/api/kb/series/' + KB.series.id);
  KB.series = series;
  return series;
}

async function switchKbTab(tab) {
  if (!KB.series || KB.tab === tab) return;
  stopKbPolling();
  KB.tab = tab;
  KB.tabLoading = false;
  paintKb();
  await loadKbTabData();
}

async function loadKbTabData() {
  if (!KB.series || KB.view !== 'detail') return;
  const sid = KB.series.id;
  const tab = KB.tab;
  const base = `/api/kb/series/${sid}`;
  KB.tabLoading = true;
  paintKb();
  try {
    if (tab === 'overview') {
      const [stats, docs, tasks] = await Promise.all([
        api(base + '/stats'),
        api(base + '/documents'),
        api(base + '/tasks?limit=8'),
      ]);
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.stats = stats;
      KB.data.docs = docs || [];
      KB.data.tasks = tasks || [];
      const current = KB.data.config;
      if (!current) {
        api(base + '/config').then((config) => {
          if (!kbSeriesChanged(sid, tab)) return;
          KB.data.config = config;
          if (!KB.configDraft) KB.configDraft = kbDraftFromVersion(config);
          paintKb();
        }).catch(() => {});
      }
    } else if (tab === 'documents') {
      const [summary, docs, dirs, tags] = await Promise.all([
        api(base + '/documents/status-summary'),
        api(base + '/documents' + kbDocQuery()),
        api(base + '/directories'),
        api(base + '/tags'),
      ]);
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.summary = summary;
      KB.data.docs = docs || [];
      KB.data.dirs = dirs || [];
      KB.data.tags = tags || [];
      const ids = new Set((docs || []).map((doc) => doc.id));
      Array.from(KB.selectedDocs).forEach((id) => {
        if (!ids.has(id)) KB.selectedDocs.delete(id);
      });
    } else if (tab === 'directories') {
      const [dirs, docs] = await Promise.all([
        api(base + '/directories'),
        api(base + '/documents'),
      ]);
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.dirs = dirs || [];
      KB.data.docs = docs || [];
    } else if (tab === 'tags') {
      const tags = await api(base + '/tags');
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.tags = tags || [];
    } else if (tab === 'tasks') {
      const query = KB.taskStatus ? '?status=' + encodeURIComponent(KB.taskStatus) + '&limit=50' : '?limit=50';
      const [tasks, docs] = await Promise.all([
        api(base + '/tasks' + query),
        api(base + '/documents'),
      ]);
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.tasks = tasks || [];
      KB.data.docs = docs || [];
    } else if (tab === 'retrieval') {
      const config = await api(base + '/config');
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.config = config;
      const retrieval = (config && config.retrievalConfig) || {};
      if (!KB.retrievalForm.topK) KB.retrievalForm.topK = retrieval.rerankTopN || 5;
      if (KB.retrievalForm.threshold === 0.3 && retrieval.similarityThreshold !== undefined) {
        KB.retrievalForm.threshold = retrieval.similarityThreshold;
      }
    } else if (tab === 'members') {
      const members = await api(base + '/members');
      let candidates = [];
      if (kbCan('ADMIN')) {
        candidates = await api(base + '/members/candidates');
      }
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.members = members || [];
      KB.data.candidates = candidates || [];
    } else if (tab === 'settings') {
      const [config, versions] = await Promise.all([
        api(base + '/config'),
        api(base + '/config-versions'),
      ]);
      if (!kbSeriesChanged(sid, tab)) return;
      KB.data.config = config;
      KB.data.versions = versions || [];
      if (!KB.configDraft) KB.configDraft = kbDraftFromVersion(config);
      ensureKbDepartments().catch(() => {});
    }
  } catch (error) {
    if (kbSeriesChanged(sid, tab)) showError(error);
  } finally {
    if (kbSeriesChanged(sid, tab)) {
      KB.tabLoading = false;
      paintKb();
      scheduleKbPolling(sid, tab);
    }
  }
}

function kbDocQuery() {
  const params = new URLSearchParams();
  if (KB.docFilters.directoryId) params.set('directory_id', KB.docFilters.directoryId);
  if (KB.docFilters.status) params.set('status', KB.docFilters.status);
  if (KB.docFilters.keyword) params.set('keyword', KB.docFilters.keyword);
  const query = params.toString();
  return query ? '?' + query : '';
}

function kbDocProcessing(doc) {
  return ['PENDING', 'PARSING', 'CHUNKING', 'INDEXING', 'DELETING'].includes(doc.status);
}

function kbTaskActive(task) {
  return ['QUEUED', 'RUNNING', 'CANCELING'].includes(task.status);
}

function stopKbPolling() {
  if (kbPollTimer) {
    window.clearInterval(kbPollTimer);
    kbPollTimer = null;
  }
}

function scheduleKbPolling(sid, tab) {
  const active = tab === 'documents'
    ? (KB.data.docs || []).some(kbDocProcessing)
    : tab === 'tasks'
      ? (KB.data.tasks || []).some(kbTaskActive)
      : false;
  if (!active) {
    stopKbPolling();
    return;
  }
  if (kbPollTimer) return;
  kbPollTimer = window.setInterval(async () => {
    if (!kbSeriesChanged(sid, tab)) {
      stopKbPolling();
      return;
    }
    try {
      const base = `/api/kb/series/${sid}`;
      if (tab === 'documents') {
        const [summary, docs] = await Promise.all([
          api(base + '/documents/status-summary'),
          api(base + '/documents' + kbDocQuery()),
        ]);
        if (!kbSeriesChanged(sid, tab)) {
          stopKbPolling();
          return;
        }
        KB.data.summary = summary;
        KB.data.docs = docs || [];
      } else {
        const tasks = await api(base + '/tasks' + (KB.taskStatus ? '?status=' + encodeURIComponent(KB.taskStatus) + '&limit=50' : '?limit=50'));
        if (!kbSeriesChanged(sid, tab)) {
          stopKbPolling();
          return;
        }
        KB.data.tasks = tasks || [];
      }
      const still = tab === 'documents'
        ? (KB.data.docs || []).some(kbDocProcessing)
        : (KB.data.tasks || []).some(kbTaskActive);
      if (!still) {
        stopKbPolling();
        await refreshKbSeries().catch(() => {});
      }
      const focused = document.activeElement;
      const editing = focused && focused.closest && focused.closest('#kb-app')
        && ['INPUT', 'SELECT', 'TEXTAREA'].includes(focused.tagName);
      if (!editing) paintKb();
    } catch (error) {
      stopKbPolling();
    }
  }, 3000);
}

function kbSeriesChanged(sid, tab) {
  return KB.view === 'detail' && KB.series && KB.series.id === sid && KB.tab === tab;
}

function kbActiveVersionLabel(series) {
  const config = KB.data && KB.data.config;
  if (config && config.versionId
      && String(config.versionId) === String(series.activeConfigVersionId)) {
    return `配置版本 v${config.versionNo}`;
  }
  return series.activeConfigVersionId ? `配置版本 #${series.activeConfigVersionId}` : '配置版本 —';
}

function renderKbDetail() {
  const series = KB.series;
  const tabs = KB_TABS.filter(([key]) => key !== 'members' || kbCan('ADMIN'))
    .map(([key, label]) => {
      const count = key === 'documents' ? ` <span class="kb-tab-count">${kbFmtNum(series.docCount)}</span>` : '';
      return `<button type="button" class="kb-tab${KB.tab === key ? ' active' : ''}" data-kb-action="tab" data-tab="${key}">${label}${count}</button>`;
    }).join('');
  return `
  <div class="kb-view kb-detail">
    <div class="kb-head kb-detail-head">
      <div class="kb-head-main">
        <button type="button" class="link kb-back" data-kb-action="back">← 返回知识库列表</button>
        <div class="kb-title-row">
          <h1 class="kb-title">${esc(series.name)}</h1>
          ${kbSeriesPill(series)}
          ${kbPill('role', KB_ROLE_LABEL[series.role] || '只读')}
        </div>
        <p class="kb-sub">${esc(series.description) || '暂无简介'}</p>
        <div class="kb-meta-line">
          <span>${esc(series.deptName || '未分配部门')}</span>
          <span>创建人 ${esc(series.authorName || '—')}</span>
          <span>${esc(KB_INDEX_LABEL[series.indexStrategy] || series.indexStrategy)}</span>
          <span>${kbActiveVersionLabel(series)}</span>
          <span>更新于 ${kbFmtTime(series.updatedAt)}</span>
        </div>
      </div>
      <div class="kb-head-actions">
        ${kbCan('ADMIN') ? '<button type="button" data-kb-action="edit-series">编辑信息</button>' : ''}
        ${kbCan('ADMIN') && series.displayStatus !== 'DISABLED' ? '<button type="button" data-kb-action="series-disable">停用</button>' : ''}
        ${kbCan('ADMIN') && series.displayStatus === 'DISABLED' ? '<button type="button" data-kb-action="series-enable">启用</button>' : ''}
        ${kbCan('OWNER') ? '<button type="button" class="danger" data-kb-action="series-delete">删除知识库</button>' : ''}
      </div>
    </div>
    <div class="kb-tabbar">${tabs}</div>
    <div class="kb-tab-body">
      ${KB.tabLoading ? '<p class="hint kb-placeholder">正在加载…</p>' : renderKbTabBody()}
    </div>
  </div>`;
}

function renderKbTabBody() {
  if (KB.tab === 'overview') return renderKbOverview();
  if (KB.tab === 'documents') return renderKbDocuments();
  if (KB.tab === 'directories') return renderKbDirectories();
  if (KB.tab === 'tags') return renderKbTags();
  if (KB.tab === 'tasks') return renderKbTasks();
  if (KB.tab === 'retrieval') return renderKbRetrieval();
  if (KB.tab === 'members') return renderKbMembers();
  if (KB.tab === 'settings') return renderKbSettings();
  return '';
}

function kbStatCard(label, value, unit, icon) {
  return `
  <div class="stat-card">
    <span class="stat-icon kb-stat-icon" aria-hidden="true">${kbIcon(icon)}</span>
    <div class="stat-body">
      <div class="stat-label">${esc(label)}</div>
      <div class="stat-value">${kbFmtNum(value)}${unit ? `<small>${esc(unit)}</small>` : ''}</div>
    </div>
  </div>`;
}

function renderKbOverview() {
  const stats = KB.data.stats || {};
  const docs = KB.data.docs || [];
  const tasks = KB.data.tasks || [];
  const config = KB.data.config;
  const recentDocs = docs.slice(0, 6);
  const docRows = recentDocs.length
    ? recentDocs.map((doc) => `
      <tr>
        <td>${esc(doc.name)}</td>
        <td>${esc(doc.directoryPath || '/')}</td>
        <td>${kbPill(kbDocTone(doc.status), KB_DOC_LABEL[doc.status] || doc.status)}</td>
        <td class="num">${kbFmtNum(doc.chunkCount)}</td>
        <td>${kbFmtTime(doc.updatedAt)}</td>
      </tr>`).join('')
    : '<tr><td colspan="5" class="hint">暂无文档，前往「文档」页签上传。</td></tr>';
  const taskRows = tasks.length
    ? tasks.slice(0, 6).map((task) => `
      <tr>
        <td>#${task.id}</td>
        <td>${esc(KB_TASK_TYPE_LABEL[task.type] || task.type)}</td>
        <td>${esc(task.documentName || '—')}</td>
        <td>${kbPill(kbTaskTone(task.status), KB_TASK_LABEL[task.status] || task.status)}</td>
        <td>${kbFmtTime(task.createdAt)}</td>
      </tr>`).join('')
    : '<tr><td colspan="5" class="hint">暂无入库任务。</td></tr>';
  const configLine = config
    ? `${KB_INDEX_LABEL[config.indexStrategy] || config.indexStrategy} · 配置 v${config.versionNo} · 分块 ${config.chunkConfig ? config.chunkConfig.strategy : '—'} · 混合检索 ${config.retrievalConfig && config.retrievalConfig.hybridEnabled ? '开启' : '关闭'}`
    : '配置加载中…';
  return `
  <div class="stat-grid">
    ${kbStatCard('文档总数', stats.documentCount || 0, '份', 'ant:file-text')}
    ${kbStatCard('已就绪', stats.readyCount || 0, '份', 'ant:safety-certificate')}
    ${kbStatCard('解析失败', stats.failedCount || 0, '份', 'ant:bug')}
    ${kbStatCard('切片总数', stats.chunkCount || 0, '段', 'ant:database')}
  </div>
  <div class="kb-overview-grid">
    <div class="card">
      <div class="panel-head"><h2>近期文档</h2><span class="panel-extra">${configLine}</span></div>
      <div class="table-wrap">
        <table><thead><tr><th>文档</th><th>目录</th><th>状态</th><th class="num">切片</th><th>更新时间</th></tr></thead>
        <tbody>${docRows}</tbody></table>
      </div>
    </div>
    <div class="card">
      <div class="panel-head"><h2>近期任务</h2><button type="button" class="link" data-kb-action="tab" data-tab="tasks">查看全部</button></div>
      <div class="table-wrap">
        <table><thead><tr><th>任务</th><th>类型</th><th>文档</th><th>状态</th><th>创建时间</th></tr></thead>
        <tbody>${taskRows}</tbody></table>
      </div>
    </div>
  </div>
  <div class="kb-quick-actions">
    ${kbCan('EDITOR') ? '<button type="button" data-kb-action="tab" data-tab="documents">上传文档</button>' : ''}
    <button type="button" data-kb-action="tab" data-tab="retrieval">检索测试</button>
    <button type="button" data-kb-action="tab" data-tab="directories">目录管理</button>
    ${kbCan('ADMIN') ? '<button type="button" data-kb-action="tab" data-tab="settings">配置与设置</button>' : ''}
  </div>`;
}

function kbFlattenDirectories(nodes, depth, out) {
  const list = out || [];
  (nodes || []).forEach((node) => {
    list.push({ ...node, depth: depth || 0 });
    if (node.children && node.children.length) kbFlattenDirectories(node.children, (depth || 0) + 1, list);
  });
  return list;
}

function kbDirectoryOptions(selected, includeAll) {
  const flat = kbFlattenDirectories(KB.data.dirs || []);
  const head = includeAll ? '<option value="">全部目录</option>' : '';
  return head + flat.map((dir) => {
    const label = dir.root ? dir.name + '（根目录）' : '　'.repeat(dir.depth) + dir.name;
    const active = String(dir.id) === String(selected) ? ' selected' : '';
    return `<option value="${dir.id}"${active}>${esc(label)}</option>`;
  }).join('');
}

function renderKbDocuments() {
  const docs = KB.data.docs || [];
  const summary = KB.data.summary || {};
  const filters = KB.docFilters;
  const editor = kbCan('EDITOR');
  const selected = KB.selectedDocs.size;
  const statusChips = [
    ['', '全部', summary.total || 0],
    ['READY', '已就绪', summary.ready || 0],
    ['PENDING', '排队中', summary.pending || 0],
    ['PARSING', '解析中', summary.parsing || 0],
    ['CHUNKING', '分块中', summary.chunking || 0],
    ['INDEXING', '索引中', summary.indexing || 0],
    ['FAILED', '失败', summary.failed || 0],
    ['DISABLED', '已停用', summary.disabled || 0],
  ].map(([value, label, count]) => {
    const active = String(filters.status) === value ? ' active' : '';
    return `<button type="button" class="kb-chip${active}" data-kb-action="doc-status" data-status="${esc(value)}">${esc(label)} <strong>${kbFmtNum(count)}</strong></button>`;
  }).join('');
  const rows = docs.length
    ? docs.map((doc) => {
      const checked = KB.selectedDocs.has(doc.id) ? ' checked' : '';
      const tags = (doc.tags || []).map((tag) => `<span class="kb-tag" style="--kb-tag-color:${esc(tag.color || '#2f6fed')}">${esc(tag.name)}</span>`).join('');
      return `
      <tr>
        <td><input type="checkbox" data-kb-doc-check value="${doc.id}"${checked}></td>
        <td>
          <button type="button" class="link kb-doc-name" data-kb-action="doc-chunks" data-id="${doc.id}">${esc(doc.name)}</button>
          <div class="hint">${esc(doc.ext || '')} · ${kbFmtBytes(doc.sizeBytes)}${doc.pageCount ? ' · ' + doc.pageCount + ' 页' : ''}</div>
          ${doc.errorMessage ? `<div class="kb-error-text">${esc(doc.errorMessage)}</div>` : ''}
          ${tags ? `<div class="kb-tag-row">${tags}</div>` : ''}
        </td>
        <td>${esc(doc.directoryPath || '/')}</td>
        <td>${kbPill(kbDocTone(doc.status), KB_DOC_LABEL[doc.status] || doc.status)}</td>
        <td class="num">${kbFmtNum(doc.chunkCount)}</td>
        <td>${esc(doc.uploadedByName || '—')}</td>
        <td>${kbFmtTime(doc.updatedAt)}</td>
        <td class="actions-cell">
          <a class="link" href="/api/kb/series/${KB.series.id}/documents/${doc.id}/download">下载</a>
          ${editor ? `<button type="button" class="link" data-kb-action="doc-edit" data-id="${doc.id}">编辑</button>` : ''}
          ${editor ? `<button type="button" class="link" data-kb-action="doc-tags" data-id="${doc.id}">标签</button>` : ''}
          ${editor ? `<button type="button" class="link" data-kb-action="doc-reparse" data-id="${doc.id}">重解析</button>` : ''}
          ${editor ? `<button type="button" class="link" data-kb-action="${doc.status === 'DISABLED' ? 'doc-enable' : 'doc-disable'}" data-id="${doc.id}">${doc.status === 'DISABLED' ? '启用' : '停用'}</button>` : ''}
          ${editor ? `<button type="button" class="link danger-text" data-kb-action="doc-delete" data-id="${doc.id}">删除</button>` : ''}
        </td>
      </tr>`;
    }).join('')
    : '<tr><td colspan="8" class="hint">暂无文档。上传 PDF、Word、Excel 或 Markdown 开始入库。</td></tr>';
  const allChecked = docs.length && docs.every((doc) => KB.selectedDocs.has(doc.id));
  return `
  <div class="toolbar filter-bar kb-doc-filters">
    <label class="filter-field"><span>目录</span><select id="kb-doc-dir">${kbDirectoryOptions(filters.directoryId, true)}</select></label>
    <label class="filter-field"><span>状态</span><select id="kb-doc-status">${kbOptions({ READY: '已就绪', FAILED: '失败', DISABLED: '已停用', PENDING: '排队中' }, filters.status, '全部状态')}</select></label>
    <label class="filter-field"><span>关键词</span><input id="kb-doc-keyword" type="search" value="${esc(filters.keyword)}" placeholder="文档名称"></label>
    <button type="button" class="primary" data-kb-action="doc-search">查询</button>
    <button type="button" data-kb-action="doc-search-reset">重置</button>
    <span class="filter-actions">
      ${editor ? `<label class="button file-button">${KB.uploading ? '上传中…' : '上传文档'}
        <input type="file" id="kb-upload-input" multiple hidden accept=".pdf,.docx,.xlsx,.xls,.md,.markdown" ${KB.uploading ? 'disabled' : ''}></label>
        <select id="kb-upload-dedupe" title="同名文件处理方式"><option value="SKIP">同名跳过</option><option value="OVERWRITE">同名覆盖</option></select>` : ''}
      ${kbCan('ADMIN') ? '<button type="button" data-kb-action="task-rebuild">重建索引</button>' : ''}
    </span>
  </div>
  <div class="kb-chips">${statusChips}</div>
  <div class="kb-batch-bar${selected ? '' : ' hidden'}" id="kb-batch-bar">
    <span>已选 <strong id="kb-batch-count">${selected}</strong> 项</span>
    <button type="button" data-kb-action="batch-reparse">重新解析</button>
    <button type="button" data-kb-action="batch-enable">启用</button>
    <button type="button" data-kb-action="batch-disable">停用</button>
    <button type="button" data-kb-action="batch-move">移动目录</button>
    <button type="button" data-kb-action="batch-tags">设置标签</button>
    <button type="button" class="danger" data-kb-action="batch-delete">删除</button>
    <button type="button" class="link" data-kb-action="batch-clear">取消选择</button>
  </div>
  <div class="card table-card">
    <div class="panel-head"><h2>文档列表</h2><span class="panel-extra">共 ${kbFmtNum(docs.length)} 条 · 仅当前筛选结果</span></div>
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th class="kb-check"><input type="checkbox" id="kb-doc-check-all" ${allChecked ? 'checked' : ''}></th>
          <th>文档</th><th>目录</th><th>状态</th><th class="num">切片</th><th>上传人</th><th>更新时间</th><th>操作</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
  </div>`;
}

function renderKbDirectories() {
  const flat = kbFlattenDirectories(KB.data.dirs || []);
  const editor = kbCan('EDITOR');
  const admin = kbCan('ADMIN');
  const countByDir = new Map((KB.data.docs || []).map((doc) => [doc.directoryId, 0]));
  (KB.data.docs || []).forEach((doc) => countByDir.set(doc.directoryId, (countByDir.get(doc.directoryId) || 0) + 1));
  const rows = flat.length
    ? flat.map((dir) => `
      <div class="kb-tree-row" style="padding-left:${dir.depth * 20 + 12}px">
        <span class="kb-tree-name"><span class="kb-tree-glyph" aria-hidden="true"></span>${esc(dir.name)}${dir.root ? ' <span class="hint">根目录</span>' : ''}</span>
        <span class="hint">${esc(dir.path)}</span>
        <span class="kb-tree-count">${kbFmtNum(countByDir.get(dir.id) ?? dir.documentCount)} 份文档</span>
        <span class="kb-tree-actions">
          <button type="button" class="link" data-kb-action="dir-docs" data-id="${dir.id}">查看文档</button>
          ${editor ? `<button type="button" class="link" data-kb-action="dir-create" data-id="${dir.id}">新建子目录</button>` : ''}
          ${editor && !dir.root ? `<button type="button" class="link" data-kb-action="dir-rename" data-id="${dir.id}">重命名</button>` : ''}
          ${editor && !dir.root ? `<button type="button" class="link" data-kb-action="dir-move" data-id="${dir.id}">移动</button>` : ''}
          ${admin && !dir.root ? `<button type="button" class="link danger-text" data-kb-action="dir-delete" data-id="${dir.id}">删除</button>` : ''}
        </span>
      </div>`).join('')
    : '<p class="hint">暂无目录，请先创建根目录。</p>';
  return `
  <div class="kb-section-head">
    <div><h3>目录树</h3><p class="hint">目录用于组织文档与限定检索范围；删除目录前需清空其下文档与子目录。</p></div>
    ${editor ? '<button type="button" data-kb-action="dir-create">新建根目录</button>' : ''}
  </div>
  <div class="card kb-tree">${rows}</div>`;
}

function renderKbTags() {
  const tags = KB.data.tags || [];
  const editor = kbCan('EDITOR');
  const rows = tags.length
    ? tags.map((tag) => `
      <div class="kb-tag-row-item">
        <span class="kb-tag-dot" style="background:${esc(tag.color || '#2f6fed')}"></span>
        <strong>${esc(tag.name)}</strong>
        <span class="hint">${kbFmtNum(tag.documentCount)} 份文档</span>
        ${editor ? `<span class="kb-row-actions">
          <button type="button" class="link" data-kb-action="tag-edit" data-id="${tag.id}">编辑</button>
          <button type="button" class="link danger-text" data-kb-action="tag-delete" data-id="${tag.id}">删除</button>
        </span>` : ''}
      </div>`).join('')
    : '<div class="kb-empty"><strong>还没有标签</strong><p>标签便于跨目录筛选文档，可在上传后为文档批量设置。</p></div>';
  return `
  <div class="kb-section-head">
    <div><h3>标签</h3><p class="hint">标签在知识库内共享，可提示制度类型、密级或业务线。</p></div>
    ${editor ? '<button type="button" data-kb-action="tag-create">新建标签</button>' : ''}
  </div>
  <div class="card kb-tag-list">${rows}</div>`;
}

function renderKbTasks() {
  const tasks = KB.data.tasks || [];
  const chips = [['', '全部'], ['RUNNING', '执行中'], ['QUEUED', '排队中'], ['FAILED', '失败'], ['SUCCEEDED', '成功'], ['CANCELED', '已取消']]
    .map(([value, label]) => `<button type="button" class="kb-chip${KB.taskStatus === value ? ' active' : ''}" data-kb-action="task-status" data-status="${esc(value)}">${esc(label)}</button>`).join('');
  const rows = tasks.length
    ? tasks.map((task) => {
      const active = task.status === 'QUEUED' || task.status === 'RUNNING';
      return `
      <tr>
        <td>#${task.id}</td>
        <td>${esc(KB_TASK_TYPE_LABEL[task.type] || task.type)}</td>
        <td>${esc(task.documentName || '—')}</td>
        <td>${kbPill(kbTaskTone(task.status), KB_TASK_LABEL[task.status] || task.status)}</td>
        <td class="kb-progress-cell"><span class="kb-progress"><span style="width:${Math.max(0, Math.min(100, task.progress || 0))}%"></span></span><span class="hint">${task.progress || 0}%</span></td>
        <td>${esc(task.stage || '—')}</td>
        <td>${esc(task.operatorName || '—')}</td>
        <td>${kbFmtTime(task.createdAt)}</td>
        <td class="actions-cell">
          <button type="button" class="link" data-kb-action="task-detail" data-id="${task.id}">详情</button>
          ${kbCan('EDITOR') && active ? `<button type="button" class="link danger-text" data-kb-action="task-cancel" data-id="${task.id}">取消</button>` : ''}
          ${kbCan('EDITOR') && (task.status === 'FAILED' || task.status === 'CANCELED') ? `<button type="button" class="link" data-kb-action="task-retry" data-id="${task.id}">重试</button>` : ''}
        </td>
      </tr>`;
    }).join('')
    : '<tr><td colspan="9" class="hint">暂无任务。上传文档后会自动创建入库任务。</td></tr>';
  return `
  <div class="kb-section-head">
    <div><h3>入库任务</h3><p class="hint">任务展示解析、分块与索引进度；失败任务可重试，重建索引会重新处理选中范围的文档。</p></div>
    <span class="kb-row-actions">
      <button type="button" data-kb-action="task-refresh">刷新</button>
      ${kbCan('ADMIN') ? '<button type="button" data-kb-action="task-rebuild">重建索引</button>' : ''}
    </span>
  </div>
  <div class="kb-chips">${chips}</div>
  <div class="card table-card">
    <div class="table-wrap">
      <table><thead><tr>
        <th>任务</th><th>类型</th><th>文档</th><th>状态</th><th>进度</th><th>阶段</th><th>操作人</th><th>创建时间</th><th>操作</th>
      </tr></thead><tbody>${rows}</tbody></table>
    </div>
  </div>`;
}

function renderKbRetrieval() {
  const form = KB.retrievalForm;
  const result = KB.data.retrieval;
  const samples = ['绩效工资 A 档发多少？', '年假如何计算？', '报销流程需要哪些材料？'];
  const sampleChips = samples.map((text) => `<button type="button" class="kb-chip" data-kb-action="retrieval-sample" data-query="${esc(text)}">${esc(text)}</button>`).join('');
  let resultHtml = '<div class="kb-empty"><strong>输入问题开始检索</strong><p>检索只读取已就绪文档的 ACTIVE 切片；Elasticsearch 或 Ollama 不可用时自动回退本地索引。</p></div>';
  if (result) {
    const diagnostics = result.diagnostics || {};
    const engine = diagnostics.engine === 'ELASTICSEARCH' ? 'Elasticsearch' : '本地 H2';
    const hits = result.hits || [];
    const hitHtml = hits.length
      ? hits.map((hit, index) => {
        const expanded = KB.expandedParents.has(hit.chunkId);
        return `
        <article class="kb-hit">
          <div class="kb-hit-head">
            <span class="kb-hit-rank">${index + 1}</span>
            <span class="kb-hit-title"><strong>${esc(hit.documentName || '未知文档')}</strong>
              <span class="hint">${esc(hit.titlePath || '切片')}${hit.pageNo ? ' · 第 ' + hit.pageNo + ' 页' : ''}</span></span>
            <span class="kb-hit-score">${Number(hit.score || 0).toFixed(3)}</span>
          </div>
          <p class="kb-hit-content">${esc(hit.content)}</p>
          <div class="kb-hit-foot">
            <span class="hint">向量 ${Number(hit.vectorScore || 0).toFixed(3)} · 关键词 ${Number(hit.keywordScore || 0).toFixed(3)}</span>
            ${hit.parentContent ? `<button type="button" class="link" data-kb-action="parent-toggle" data-target="${hit.chunkId}">${expanded ? '收起父级上下文' : '查看父级上下文'}</button>` : ''}
          </div>
          ${expanded && hit.parentContent ? `<pre class="kb-parent">${esc(hit.parentContent)}</pre>` : ''}
        </article>`;
      }).join('')
      : '<p class="hint">没有命中的切片，可尝试降低阈值或切换检索策略。</p>';
    const answer = result.answer
      ? `<div class="kb-answer">
          <div class="kb-answer-head"><strong>生成式回答</strong>
            <span class="hint">${esc(diagnostics.answerModel || '')}${diagnostics.answerTookMs ? ' · ' + diagnostics.answerTookMs + ' ms' : ''}</span>
          </div>
          <p>${esc(result.answer)}</p>
        </div>`
      : diagnostics.answerError
        ? `<div class="kb-answer kb-answer-warn"><strong>生成式回答不可用</strong><p>${esc(diagnostics.answerError)}</p></div>`
        : '';
    resultHtml = `
    <div class="kb-result-summary">
      <span>候选切片 <strong>${kbFmtNum(diagnostics.recallTopK || 0)}</strong></span>
      <span>返回结果 <strong>${hits.length}</strong></span>
      <span>耗时 <strong>${kbFmtNum(result.tookMs || 0)} ms</strong></span>
      <span>策略 <strong>${esc(result.strategy || '—')}</strong></span>
      <span>引擎 <strong>${esc(engine)}</strong></span>
      <span>模式 <strong>${diagnostics.hybrid ? '混合' : '仅向量'} · ${diagnostics.rerank ? '精排' : '不精排'}</strong></span>
      <span>嵌入 <strong>${esc(diagnostics.embeddingSource || '—')}</strong></span>
    </div>
    ${answer}
    ${hitHtml}`;
  }
  return `
  <div class="kb-search-panel">
    <div class="kb-section-head">
      <div><h3>检索测试</h3><p class="hint">验证召回与生成式回答效果；结果附带引用片段，便于核对依据。</p></div>
      <span class="hint">Top ${kbFmtNum(form.topK)} · 阈值 ${Number(form.threshold).toFixed(2)}</span>
    </div>
    <div class="kb-query-row">
      <textarea id="kb-retrieval-query" rows="2" maxlength="200" placeholder="例如：绩效工资 A 档发多少？">${esc(form.query)}</textarea>
      <button type="button" class="primary" data-kb-action="retrieval-run">${KB.tabLoading ? '检索中…' : '检索'}</button>
    </div>
    <div class="kb-query-options">
      <label>TopK<input type="number" id="kb-retrieval-topk" min="1" max="20" value="${esc(form.topK)}"></label>
      <label>阈值<input type="number" id="kb-retrieval-threshold" min="0" max="1" step="0.01" value="${esc(form.threshold)}"></label>
      <label class="kb-inline-check"><input type="checkbox" id="kb-retrieval-answer" ${form.withAnswer ? 'checked' : ''}> 生成回答</label>
    </div>
    <div class="kb-chips"><span class="hint">示例</span>${sampleChips}</div>
  </div>
  <div class="kb-result-panel">${resultHtml}</div>`;
}

function renderKbMembers() {
  const members = KB.data.members || [];
  const rows = members.length
    ? members.map((member) => {
      const owner = member.role === 'OWNER';
      const editable = kbCan('ADMIN') && !owner;
      const roleSelect = editable
        ? `<select data-kb-member-role data-id="${member.id}">
            ${kbOptions({ READER: '只读', EDITOR: '编辑', ADMIN: '管理员' }, member.role)}
          </select>`
        : kbPill('role', KB_ROLE_LABEL[member.role] || member.role);
      return `
      <tr>
        <td>${esc(member.subjectType === 'DEPT' ? '部门' : '用户')}</td>
        <td><strong>${esc(member.subjectName)}</strong><div class="hint">${esc(member.subjectExtra || '')}</div></td>
        <td>${roleSelect}</td>
        <td>${esc(member.grantedByName || '—')}</td>
        <td>${kbFmtTime(member.createdAt)}</td>
        <td class="actions-cell">
          ${editable ? `<button type="button" class="link danger-text" data-kb-action="member-remove" data-id="${member.id}">移除</button>` : '<span class="hint">—</span>'}
        </td>
      </tr>`;
    }).join('')
    : '<tr><td colspan="6" class="hint">暂无成员；平台管理员默认拥有全部权限。</td></tr>';
  return `
  <div class="kb-section-head">
    <div><h3>成员与权限</h3><p class="hint">只读可查看与检索；编辑可维护文档、目录与标签；管理员可修改配置与成员。</p></div>
    ${kbCan('ADMIN') ? '<button type="button" data-kb-action="member-grant">添加成员</button>' : ''}
  </div>
  <div class="card table-card">
    <div class="table-wrap">
      <table><thead><tr><th>类型</th><th>成员</th><th>角色</th><th>授予人</th><th>加入时间</th><th>操作</th></tr></thead>
      <tbody>${rows}</tbody></table>
    </div>
  </div>`;
}

function renderKbSettings() {
  const series = KB.series;
  const admin = kbCan('ADMIN');
  const departments = (KB.departments || []).filter((dept) => dept.active !== false);
  const deptOptions = departments.map((dept) => {
    const active = String(dept.id) === String(series.deptId) ? ' selected' : '';
    return `<option value="${dept.id}"${active}>${esc(dept.name)}</option>`;
  }).join('');
  const config = KB.data.config;
  const versions = KB.data.versions || [];
  const versionRows = versions.length
    ? versions.map((version) => `
      <tr>
        <td>v${version.versionNo}${version.active ? ' ' + kbPill('ok', '生效中') : ''}</td>
        <td>${esc(KB_INDEX_LABEL[version.indexStrategy] || version.indexStrategy)}</td>
        <td>${esc(version.changeNote || '—')}</td>
        <td>${esc(version.createdByName || '—')}</td>
        <td>${kbFmtTime(version.createdAt)}</td>
        <td>${admin ? `<button type="button" class="link" data-kb-action="config-version-load" data-id="${version.versionId}">载入编辑器</button>` : ''}</td>
      </tr>`).join('')
    : '<tr><td colspan="6" class="hint">暂无配置版本。</td></tr>';
  return `
  <div class="kb-settings">
    <div class="card">
      <div class="panel-head"><h2>基本信息</h2><span class="panel-extra">${admin ? '名称、简介与所属部门会同步到列表' : '仅管理员可修改'}</span></div>
      <div class="kb-form-grid">
        <label class="span-2">知识库主题<input id="kb-settings-name" maxlength="50" value="${esc(series.name)}" ${admin ? '' : 'disabled'}></label>
        <label>所属部门<select id="kb-settings-dept" ${admin ? '' : 'disabled'}>${deptOptions}</select></label>
        <label class="span-2">知识库简介<input id="kb-settings-desc" maxlength="200" value="${esc(series.description || '')}" ${admin ? '' : 'disabled'}></label>
      </div>
      ${admin ? '<div class="actions"><button type="button" class="primary" data-kb-action="settings-save">保存基本信息</button></div>' : ''}
    </div>
    ${config ? `<div class="card">
      <div class="panel-head"><h2>当前生效配置</h2><span class="panel-extra">v${config.versionNo} · ${esc(config.createdByName || '')} · ${kbFmtTime(config.createdAt)}</span></div>
      <div class="kb-config-summary">
        <span>索引策略<strong>${esc(KB_INDEX_LABEL[config.indexStrategy] || config.indexStrategy)}</strong></span>
        <span>分块策略<strong>${esc(config.chunkConfig ? config.chunkConfig.strategy : '—')}</strong></span>
        <span>最大切片<strong>${esc(config.chunkConfig && config.chunkConfig.common ? config.chunkConfig.common.maxTokens : '—')} tokens</strong></span>
        <span>混合检索<strong>${config.retrievalConfig && config.retrievalConfig.hybridEnabled ? '开启' : '关闭'}</strong></span>
        <span>召回 / 精排<strong>${esc(config.retrievalConfig ? config.retrievalConfig.recallTopK + ' / ' + config.retrievalConfig.rerankTopN : '—')}</strong></span>
        <span>相似度阈值<strong>${esc(config.retrievalConfig ? Number(config.retrievalConfig.similarityThreshold).toFixed(2) : '—')}</strong></span>
      </div>
    </div>` : ''}
    ${admin && KB.configDraft ? renderKbConfigEditor() : ''}
    <div class="card table-card">
      <div class="panel-head"><h2>配置版本</h2><span class="panel-extra">保存配置会生成新版本；可选择重建全部或失败文档</span></div>
      <div class="table-wrap">
        <table><thead><tr><th>版本</th><th>索引策略</th><th>变更说明</th><th>操作人</th><th>创建时间</th><th>操作</th></tr></thead>
        <tbody>${versionRows}</tbody></table>
      </div>
    </div>
    ${kbCan('OWNER') ? `<div class="card kb-danger-zone">
      <div class="panel-head"><h2>危险操作</h2><span class="panel-extra">删除后文档、切片、成员与配置将一并清理</span></div>
      <button type="button" class="danger" data-kb-action="series-delete">删除知识库</button>
    </div>` : ''}
  </div>`;
}

function renderKbConfigEditor() {
  const draft = KB.configDraft;
  return `
  <div class="card kb-config-editor">
    <div class="panel-head"><h2>编辑配置</h2><span class="panel-extra">保存后生成新版本，可按需触发重建</span></div>
    <div class="kb-form-grid">
      <label>索引策略<select data-cfield="indexStrategy">
        ${kbOptions({ HYBRID: '混合检索（向量 + BM25）', VECTOR: '向量检索（RAG）' }, draft.indexStrategy)}
      </select></label>
      <label>变更说明<input data-cfield="changeNote" maxlength="200" value="${esc(draft.changeNote || '')}" placeholder="例如：调整切片长度"></label>
      <label>保存后重建<select data-cfield="rebuildMode">
        ${kbOptions({ NONE: '不重建', FAILED: '仅重建失败文档', FULL: '重建全部文档' }, draft.rebuildMode)}
      </select></label>
    </div>
    <h3 class="kb-subhead">解析配置</h3>
    ${renderKbParseFields(draft, 'cfield', true)}
    <h3 class="kb-subhead">分块配置</h3>
    ${renderKbChunkFields(draft, 'cfield', true)}
    <h3 class="kb-subhead">检索与引用</h3>
    ${renderKbRetrievalFields(draft, 'cfield', true)}
    <div class="actions">
      <button type="button" class="primary" data-kb-action="config-save">保存配置</button>
      <span class="hint">重建任务在后台执行，可在「任务」页签查看进度。</span>
    </div>
  </div>`;
}

// ---------------------------------------------------------------- 字段绑定

function kbFieldRoot(element) {
  if (element.dataset.wfield) return KB.wizard ? KB.wizard.form : null;
  if (element.dataset.cfield) return KB.configDraft;
  return null;
}

function kbReadFieldValue(element) {
  if (element.dataset.wlist) {
    const name = element.dataset.wlist;
    const scope = element.closest('.kb-tab-body') || document;
    return Array.from(scope.querySelectorAll(`[data-wlist="${name}"]`))
      .filter((item) => item.checked)
      .map((item) => item.value);
  }
  if (element.type === 'checkbox') return element.checked;
  if (element.type === 'number' || element.type === 'range') {
    return element.value === '' ? null : Number(element.value);
  }
  return element.value;
}

function kbApplyField(element) {
  const path = element.dataset.wfield || element.dataset.cfield;
  const root = kbFieldRoot(element);
  if (!path || !root) return;
  const value = kbReadFieldValue(element);
  kbSet(root, path, value);
  if (path === 'parse.docTypes' && root.parse && root.parse.perType) {
    Object.keys(root.parse.perType).forEach((key) => {
      root.parse.perType[key].enabled = (root.parse.docTypes || []).includes(key);
    });
  }
  if (element.type === 'range') {
    const output = document.querySelector(`[data-wout="${path}"]`);
    if (output) {
      output.textContent = element.dataset.wformat === 'percent'
        ? Math.round(Number(value) * 100) + '%'
        : Number(value).toFixed(2);
    }
  }
}

function kbOnFieldInput(event) {
  const element = event.target.closest('[data-wfield], [data-cfield]');
  if (!element) return;
  kbApplyField(element);
}

function kbOnFieldChange(event) {
  const element = event.target.closest('[data-wfield], [data-cfield]');
  if (element) {
    kbApplyField(element);
    if (element.dataset.wrepaint === '1') paintKb();
    return;
  }
  const check = event.target.closest('[data-kb-doc-check]');
  if (check) {
    const id = Number(check.value);
    if (check.checked) KB.selectedDocs.add(id);
    else KB.selectedDocs.delete(id);
    kbSyncBatchBar();
    return;
  }
  if (event.target.id === 'kb-doc-check-all') {
    const ids = (KB.data.docs || []).map((doc) => doc.id);
    KB.selectedDocs.clear();
    if (event.target.checked) ids.forEach((id) => KB.selectedDocs.add(id));
    $$('[data-kb-doc-check]').forEach((item) => {
      item.checked = event.target.checked;
    });
    kbSyncBatchBar();
    return;
  }
  if (event.target.id === 'kb-upload-input') {
    const files = event.target.files;
    if (files && files.length) kbUploadFiles(files);
    event.target.value = '';
    return;
  }
  const roleSelect = event.target.closest('[data-kb-member-role]');
  if (roleSelect) {
    kbUpdateMemberRole(Number(roleSelect.dataset.id), roleSelect.value).catch(showError);
  }
}

function kbSyncBatchBar() {
  const bar = $('#kb-batch-bar');
  if (!bar) return;
  const count = KB.selectedDocs.size;
  bar.classList.toggle('hidden', count === 0);
  const label = $('#kb-batch-count');
  if (label) label.textContent = String(count);
  const all = $('#kb-doc-check-all');
  const docs = KB.data.docs || [];
  if (all) all.checked = docs.length > 0 && docs.every((doc) => KB.selectedDocs.has(doc.id));
}

// ---------------------------------------------------------------- 事件分发

function kbOnClick(event) {
  const trigger = event.target.closest('[data-kb-action]');
  if (!trigger) return;
  event.preventDefault();
  const action = trigger.dataset.kbAction;
  const id = trigger.dataset.id ? Number(trigger.dataset.id) : null;
  Promise.resolve(kbDispatch(action, id, trigger)).catch(showError);
}

async function kbDispatch(action, id, trigger) {
  if (action === 'create') return kbStartWizard();
  if (action === 'search') return kbApplyListFilters();
  if (action === 'retry') return loadKbList();
  if (action === 'reset-filters') {
    KB.list = { ...KB.list, keyword: '', scope: '', deptId: '', status: '', page: 1 };
    return loadKbList();
  }
  if (action === 'page') {
    KB.list.page = Number(trigger.dataset.page) || 1;
    return loadKbList();
  }
  if (action === 'open') return openKbSeries(id);
  if (action === 'back') {
    KB.view = 'list';
    KB.series = null;
    KB.data = kbEmptyData();
    KB.configDraft = null;
    return loadKbList();
  }
  if (action === 'wizard-next') {
    if (!kbWizardValidate(KB.wizard.step)) return;
    KB.wizard.step = Math.min(3, KB.wizard.step + 1);
    paintKb();
    return;
  }
  if (action === 'wizard-prev') {
    KB.wizard.step = Math.max(0, KB.wizard.step - 1);
    paintKb();
    return;
  }
  if (action === 'wizard-cancel') {
    KB.wizard = null;
    KB.view = 'list';
    return loadKbList();
  }
  if (action === 'wizard-submit') return kbWizardSubmit();
  if (action === 'tab') return switchKbTab(trigger.dataset.tab);
  if (action === 'series-disable') return kbSetSeriesStatus(false);
  if (action === 'series-enable') return kbSetSeriesStatus(true);
  if (action === 'series-delete') return kbDeleteSeries();
  if (action === 'edit-series') return kbShowSeriesDialog();
  if (action === 'doc-search') {
    KB.docFilters.directoryId = ($('#kb-doc-dir') || {}).value || '';
    KB.docFilters.status = ($('#kb-doc-status') || {}).value || '';
    KB.docFilters.keyword = ($('#kb-doc-keyword') || {}).value || '';
    return loadKbTabData();
  }
  if (action === 'doc-search-reset') {
    KB.docFilters = { directoryId: '', status: '', keyword: '' };
    return loadKbTabData();
  }
  if (action === 'doc-status') {
    KB.docFilters.status = trigger.dataset.status || '';
    return loadKbTabData();
  }
  if (action === 'doc-edit') return kbShowDocumentDialog(id);
  if (action === 'doc-tags') return kbShowDocumentTagsDialog(id);
  if (action === 'doc-chunks') return kbShowChunks(id);
  if (action === 'doc-reparse') return kbReparseDocument(id);
  if (action === 'doc-disable' || action === 'doc-enable') {
    return kbToggleDocument(id, action === 'doc-enable');
  }
  if (action === 'doc-delete') return kbDeleteDocument(id);
  if (action === 'batch-clear') {
    KB.selectedDocs.clear();
    paintKb();
    return;
  }
  if (action === 'batch-reparse' || action === 'batch-disable' || action === 'batch-enable' || action === 'batch-delete') {
    const map = { 'batch-reparse': 'REPARSE', 'batch-disable': 'DISABLE', 'batch-enable': 'ENABLE', 'batch-delete': 'DELETE' };
    return kbBatchDocuments(map[action]);
  }
  if (action === 'batch-move') return kbShowBatchMoveDialog();
  if (action === 'batch-tags') return kbShowBatchTagsDialog();
  if (action === 'dir-create') return kbShowDirectoryDialog('create', id);
  if (action === 'dir-rename') return kbShowDirectoryDialog('rename', id);
  if (action === 'dir-move') return kbShowDirectoryDialog('move', id);
  if (action === 'dir-delete') return kbShowDirectoryDeleteDialog(id);
  if (action === 'dir-docs') {
    KB.docFilters.directoryId = id ? String(id) : '';
    return switchKbTab('documents');
  }
  if (action === 'tag-create') return kbShowTagDialog(null);
  if (action === 'tag-edit') return kbShowTagDialog(id);
  if (action === 'tag-delete') return kbDeleteTag(id);
  if (action === 'task-refresh') return loadKbTabData();
  if (action === 'task-status') {
    KB.taskStatus = trigger.dataset.status || '';
    return loadKbTabData();
  }
  if (action === 'task-detail') return kbShowTaskDetail(id);
  if (action === 'task-cancel') return kbCancelTask(id);
  if (action === 'task-retry') return kbRetryTask(id);
  if (action === 'task-rebuild') return kbShowRebuildDialog();
  if (action === 'retrieval-run') return kbRunRetrieval();
  if (action === 'retrieval-sample') {
    KB.retrievalForm.query = trigger.dataset.query || '';
    paintKb();
    return;
  }
  if (action === 'parent-toggle') {
    const target = Number(trigger.dataset.target);
    if (KB.expandedParents.has(target)) KB.expandedParents.delete(target);
    else KB.expandedParents.add(target);
    paintKb();
    return;
  }
  if (action === 'member-grant') return kbShowMemberGrantDialog();
  if (action === 'member-remove') return kbRemoveMember(id);
  if (action === 'settings-save') return kbSaveSeriesBasic();
  if (action === 'config-save') return kbSaveConfig();
  if (action === 'config-version-load') return kbLoadConfigVersion(id);
}

// ---------------------------------------------------------------- 系列操作

async function kbSetSeriesStatus(enabled) {
  if (!KB.series) return;
  const series = KB.series;
  if (!confirm(`${enabled ? '启用' : '停用'}知识库「${series.name}」？${enabled ? '' : '停用后成员无法检索该库。'}`)) return;
  try {
    const updated = await api(`/api/kb/series/${series.id}/${enabled ? 'enable' : 'disable'}`, { method: 'POST' });
    if (KB.series && KB.series.id === series.id) {
      KB.series = updated;
      toast(enabled ? '知识库已启用' : '知识库已停用');
      paintKb();
    }
  } catch (error) {
    showError(error);
  }
}

async function kbDeleteSeries() {
  if (!KB.series) return;
  const series = KB.series;
  if (!confirm(`确认删除知识库「${series.name}」？\n文档、切片、成员与配置将一并清理，且不可恢复。`)) return;
  try {
    await api('/api/kb/series/' + series.id, { method: 'DELETE' });
    toast('知识库已删除');
    KB.view = 'list';
    KB.series = null;
    KB.data = kbEmptyData();
    KB.configDraft = null;
    await loadKbList();
  } catch (error) {
    showError(error);
  }
}

function kbShowSeriesDialog() {
  const series = KB.series;
  const departments = (KB.departments || []).filter((dept) => dept.active !== false);
  const options = departments.map((dept) => {
    const active = String(dept.id) === String(series.deptId) ? ' selected' : '';
    return `<option value="${dept.id}"${active}>${esc(dept.name)}</option>`;
  }).join('');
  kbOpenDialog('编辑知识库信息', `
    <div class="kb-dialog-fields">
      <label>知识库主题<input name="name" maxlength="50" value="${esc(series.name)}" required></label>
      <label>所属部门<select name="deptId">${options}</select></label>
      <label>知识库简介<input name="description" maxlength="200" value="${esc(series.description || '')}"></label>
    </div>`, async (data) => {
    const updated = await api('/api/kb/series/' + series.id, {
      method: 'PATCH',
      body: {
        name: String(data.get('name') || '').trim(),
        description: String(data.get('description') || ''),
        deptId: Number(data.get('deptId')),
        rowVersion: series.rowVersion,
      },
    });
    KB.series = updated;
    toast('知识库信息已更新');
    paintKb();
    loadKbTabData();
  });
}

async function kbSaveSeriesBasic() {
  if (!KB.series) return;
  const name = ($('#kb-settings-name') || {}).value || '';
  const description = ($('#kb-settings-desc') || {}).value || '';
  const deptId = Number(($('#kb-settings-dept') || {}).value || 0);
  if (!name.trim() || name.trim().length < 2) {
    toast('知识库名称需为 2-50 个字符', 'error');
    return;
  }
  if (!deptId) {
    toast('请选择所属部门', 'error');
    return;
  }
  try {
    const updated = await api('/api/kb/series/' + KB.series.id, {
      method: 'PATCH',
      body: { name: name.trim(), description, deptId, rowVersion: KB.series.rowVersion },
    });
    KB.series = updated;
    toast('基本信息已保存');
    paintKb();
  } catch (error) {
    showError(error);
  }
}

// ---------------------------------------------------------------- 文档

async function kbUploadFiles(files) {
  if (!KB.series || !kbCan('EDITOR')) return;
  const directoryId = ($('#kb-doc-dir') || {}).value || KB.docFilters.directoryId || '';
  const dedupe = ($('#kb-upload-dedupe') || {}).value || 'SKIP';
  const form = new FormData();
  Array.from(files).forEach((file) => form.append('files', file));
  if (directoryId) form.append('directory_id', directoryId);
  form.append('dedupe_action', dedupe);
  KB.uploading = true;
  paintKb();
  try {
    const response = await fetch(`/api/kb/series/${KB.series.id}/documents`, { method: 'POST', body: form });
    const text = await response.text();
    let payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch (error) {
        throw new Error(text);
      }
    }
    if (response.status === 401) {
      window.location.href = '/login.html';
      throw new Error('未登录或会话已过期');
    }
    if (!response.ok || (payload && payload.status === 'ERROR')) {
      throw new Error((payload && payload.message) || `上传失败：HTTP ${response.status}`);
    }
    const result = payload ? payload.data : null;
    if (result) {
      const parts = [`上传 ${result.uploaded || 0} 个`];
      if (result.skipped) parts.push(`跳过 ${result.skipped} 个`);
      if (result.failed) parts.push(`失败 ${result.failed} 个`);
      toast(parts.join(' · '), result.failed ? 'error' : '');
      if (result.messages && result.messages.length) console.info('[知识库上传]', result.messages);
    }
    KB.data.docs = [];
    await refreshKbSeries();
  } catch (error) {
    showError(error);
  } finally {
    KB.uploading = false;
    await loadKbTabData();
  }
}

function kbFindDocument(id) {
  return (KB.data.docs || []).find((doc) => doc.id === id) || null;
}

function kbShowDocumentDialog(id) {
  const doc = kbFindDocument(id);
  if (!doc) return;
  kbOpenDialog('编辑文档', `
    <div class="kb-dialog-fields">
      <label>文档名称<input name="name" maxlength="120" value="${esc(doc.name)}" required></label>
      <label>所属目录<select name="directoryId">${kbDirectoryOptions(doc.directoryId, false)}</select></label>
    </div>`, async (data) => {
    await api(`/api/kb/series/${KB.series.id}/documents/${id}`, {
      method: 'PATCH',
      body: {
        name: String(data.get('name') || '').trim(),
        directoryId: Number(data.get('directoryId')),
      },
    });
    toast('文档已更新');
    await loadKbTabData();
  });
}

function kbShowDocumentTagsDialog(id) {
  const doc = kbFindDocument(id);
  if (!doc) return;
  const tags = KB.data.tags || [];
  if (!tags.length) {
    toast('请先在「标签」页签创建标签', 'error');
    return;
  }
  const current = new Set((doc.tags || []).map((tag) => tag.id));
  const checks = tags.map((tag) => `
    <label class="kb-inline-check"><input type="checkbox" name="tagIds" value="${tag.id}" ${current.has(tag.id) ? 'checked' : ''}> ${esc(tag.name)}</label>`).join('');
  kbOpenDialog(`设置标签 · ${doc.name}`, `<div class="kb-dialog-fields"><div class="kb-switch-list">${checks}</div></div>`, async (data) => {
    const tagIds = data.getAll('tagIds').map((value) => Number(value));
    await api(`/api/kb/series/${KB.series.id}/documents/${id}/tags`, { method: 'PUT', body: { tagIds } });
    toast('标签已更新');
    await loadKbTabData();
  });
}

async function kbShowChunks(id) {
  const doc = kbFindDocument(id);
  if (!doc) return;
  try {
    const page = await api(`/api/kb/series/${KB.series.id}/documents/${id}/chunks?page=1&size=30`);
    const items = (page && page.items) || [];
    const body = items.length
      ? `<div class="kb-chunk-list">${items.map((chunk) => `
          <div class="kb-chunk">
            <div class="kb-chunk-head">
              <strong>#${chunk.seq} · ${esc(chunk.level)}</strong>
              <span class="hint">${esc(chunk.tokenCount || 0)} tokens${chunk.pageNo ? ' · 第 ' + chunk.pageNo + ' 页' : ''}${chunk.parentChunkId ? ' · 父切片 #' + chunk.parentChunkId : ''}</span>
            </div>
            ${chunk.titlePath ? `<div class="hint">${esc(chunk.titlePath)}</div>` : ''}
            <pre>${esc(chunk.content)}</pre>
          </div>`).join('')}</div>
        <p class="hint">共 ${page.total || items.length} 个切片，当前展示前 ${items.length} 个。</p>`
      : '<p class="hint">该文档还没有切片，等待入库任务完成或检查解析结果。</p>';
    kbOpenDialog(`切片 · ${doc.name}`, body, null, '关闭');
  } catch (error) {
    showError(error);
  }
}

async function kbReparseDocument(id) {
  const doc = kbFindDocument(id);
  if (!doc) return;
  if (!confirm(`重新解析「${doc.name}」？将清空原有切片并重新入库。`)) return;
  try {
    await api(`/api/kb/series/${KB.series.id}/documents/${id}/reparse`, { method: 'POST' });
    toast('已创建重新解析任务');
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

async function kbToggleDocument(id, enable) {
  const doc = kbFindDocument(id);
  if (!doc) return;
  if (!confirm(`${enable ? '启用' : '停用'}文档「${doc.name}」？${enable ? '启用后会重建索引。' : '停用后不再参与检索。'}`)) return;
  try {
    await api(`/api/kb/series/${KB.series.id}/documents/${id}/${enable ? 'enable' : 'disable'}`, { method: 'POST' });
    toast(enable ? '文档已启用' : '文档已停用');
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

async function kbDeleteDocument(id) {
  const doc = kbFindDocument(id);
  if (!doc) return;
  if (!confirm(`删除文档「${doc.name}」？其切片与索引将一并清理。`)) return;
  try {
    await api(`/api/kb/series/${KB.series.id}/documents/${id}`, { method: 'DELETE' });
    KB.selectedDocs.delete(id);
    toast('文档已删除');
    await refreshKbSeries();
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

function kbSelectedDocIds() {
  return Array.from(KB.selectedDocs);
}

async function kbBatchDocuments(action) {
  const documentIds = kbSelectedDocIds();
  if (!documentIds.length) {
    toast('请先选择文档', 'error');
    return;
  }
  if (action === 'DELETE' && !confirm(`确认删除选中的 ${documentIds.length} 份文档？`)) return;
  if (action !== 'DELETE' && !confirm(`确认对选中的 ${documentIds.length} 份文档执行该操作？`)) return;
  await kbRunBatch({ action, documentIds });
}

async function kbRunBatch(body) {
  try {
    const result = await api(`/api/kb/series/${KB.series.id}/documents/batch`, { method: 'POST', body });
    const parts = [`成功 ${result.succeeded || 0}`];
    if (result.failed) parts.push(`失败 ${result.failed}`);
    toast(parts.join(' · '), result.failed ? 'error' : '');
    if (result.messages && result.messages.length) console.info('[知识库批量操作]', result.messages);
    KB.selectedDocs.clear();
    await refreshKbSeries();
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

function kbShowBatchMoveDialog() {
  const documentIds = kbSelectedDocIds();
  if (!documentIds.length) {
    toast('请先选择文档', 'error');
    return;
  }
  kbOpenDialog('移动文档', `
    <div class="kb-dialog-fields">
      <label>目标目录<select name="directoryId">${kbDirectoryOptions('', false)}</select></label>
      <p class="hint">将 ${documentIds.length} 份文档移动到目标目录。</p>
    </div>`, async (data) => {
    await kbRunBatch({ action: 'MOVE', documentIds, directoryId: Number(data.get('directoryId')) });
  });
}

function kbShowBatchTagsDialog() {
  const documentIds = kbSelectedDocIds();
  if (!documentIds.length) {
    toast('请先选择文档', 'error');
    return;
  }
  const tags = KB.data.tags || [];
  if (!tags.length) {
    toast('请先在「标签」页签创建标签', 'error');
    return;
  }
  const checks = tags.map((tag) => `<label class="kb-inline-check"><input type="checkbox" name="tagIds" value="${tag.id}"> ${esc(tag.name)}</label>`).join('');
  kbOpenDialog('批量设置标签', `
    <div class="kb-dialog-fields">
      <p class="hint">为 ${documentIds.length} 份文档设置标签（会覆盖原有标签）。</p>
      <div class="kb-switch-list">${checks}</div>
    </div>`, async (data) => {
    const tagIds = data.getAll('tagIds').map((value) => Number(value));
    await kbRunBatch({ action: 'SET_TAGS', documentIds, tagIds });
  });
}

// ---------------------------------------------------------------- 目录

function kbFindDirectory(id) {
  return kbFlattenDirectories(KB.data.dirs || []).find((dir) => dir.id === id) || null;
}

function kbShowDirectoryDialog(mode, id) {
  const flat = kbFlattenDirectories(KB.data.dirs || []);
  const root = flat.find((dir) => dir.root);
  const target = id ? kbFindDirectory(id) : null;
  if (mode !== 'create' && !target) return;
  if (!flat.length && mode === 'create') {
    toast('请先创建根目录', 'error');
    return;
  }
  const exclude = new Set();
  if (target && mode === 'move') {
    exclude.add(target.id);
    const collect = (node) => {
      (node.children || []).forEach((child) => {
        exclude.add(child.id);
        collect(child);
      });
    };
    collect(target);
  }
  const preselected = mode === 'move'
    ? (target && target.parentId ? target.parentId : root ? root.id : '')
    : (target ? target.id : root ? root.id : '');
  const parentOptions = flat
    .filter((dir) => !exclude.has(dir.id))
    .map((dir) => {
      const label = dir.root ? dir.name + '（根目录）' : '　'.repeat(dir.depth) + dir.name;
      const active = String(dir.id) === String(preselected) ? ' selected' : '';
      return `<option value="${dir.id}"${active}>${esc(label)}</option>`;
    }).join('');
  const title = { create: '新建目录', rename: '重命名目录', move: '移动目录' }[mode];
  const fields = [];
  if (mode === 'create' || mode === 'rename') {
    fields.push(`<label>目录名称<input name="name" maxlength="50" value="${esc(target ? target.name : '')}" required></label>`);
  }
  if (mode === 'create') {
    fields.push(`<label>上级目录<select name="parentId">${parentOptions}</select></label>`);
  }
  if (mode === 'move') {
    fields.push(`<label>目标上级<select name="parentId">${parentOptions}</select></label>`);
  }
  fields.push(`<label>排序值<input name="sortOrder" type="number" value="${target ? target.sortOrder || 0 : 0}"></label>`);
  kbOpenDialog(title, `<div class="kb-dialog-fields">${fields.join('')}</div>`, async (data) => {
    const sortOrder = Number(data.get('sortOrder') || 0);
    if (mode === 'create') {
      const parentId = Number(data.get('parentId') || (root ? root.id : 0));
      await api(`/api/kb/series/${KB.series.id}/directories`, {
        method: 'POST',
        body: { name: String(data.get('name') || '').trim(), parentId, sortOrder },
      });
    } else if (mode === 'rename') {
      await api(`/api/kb/series/${KB.series.id}/directories/${target.id}`, {
        method: 'PATCH',
        body: { name: String(data.get('name') || '').trim(), sortOrder },
      });
    } else {
      await api(`/api/kb/series/${KB.series.id}/directories/${target.id}/move`, {
        method: 'POST',
        body: { parentId: Number(data.get('parentId')), sortOrder },
      });
    }
    toast('目录已更新');
    await loadKbTabData();
  });
}

function kbShowDirectoryDeleteDialog(id) {
  const target = kbFindDirectory(id);
  if (!target) return;
  kbOpenDialog('删除目录', `
    <div class="kb-dialog-fields">
      <p>删除目录「<strong>${esc(target.name)}</strong>」前需确保其下没有文档与子目录。</p>
      <label>输入目录名称确认<input name="confirmName" placeholder="${esc(target.name)}" required></label>
    </div>`, async (data) => {
    await api(`/api/kb/series/${KB.series.id}/directories/${id}`, {
      method: 'DELETE',
      body: { confirmName: String(data.get('confirmName') || '').trim() },
    });
    toast('目录已删除');
    await loadKbTabData();
  }, '删除');
}

// ---------------------------------------------------------------- 标签

function kbFindTag(id) {
  return (KB.data.tags || []).find((tag) => tag.id === id) || null;
}

function kbShowTagDialog(id) {
  const tag = id ? kbFindTag(id) : null;
  kbOpenDialog(tag ? '编辑标签' : '新建标签', `
    <div class="kb-dialog-fields">
      <label>标签名称<input name="name" maxlength="30" value="${esc(tag ? tag.name : '')}" required></label>
      <label>标签颜色<input name="color" type="color" value="${esc(tag ? tag.color || '#2f6fed' : '#2f6fed')}"></label>
    </div>`, async (data) => {
    const body = { name: String(data.get('name') || '').trim(), color: String(data.get('color') || '#2f6fed') };
    if (tag) {
      await api(`/api/kb/series/${KB.series.id}/tags/${tag.id}`, { method: 'PATCH', body });
    } else {
      await api(`/api/kb/series/${KB.series.id}/tags`, { method: 'POST', body });
    }
    toast('标签已保存');
    await loadKbTabData();
  });
}

async function kbDeleteTag(id) {
  const tag = kbFindTag(id);
  if (!tag) return;
  if (!confirm(`删除标签「${tag.name}」？文档与标签的关联会被清除。`)) return;
  try {
    await api(`/api/kb/series/${KB.series.id}/tags/${id}`, { method: 'DELETE' });
    toast('标签已删除');
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

// ---------------------------------------------------------------- 任务

function kbFindTask(id) {
  return (KB.data.tasks || []).find((task) => task.id === id) || null;
}

async function kbShowTaskDetail(id) {
  try {
    const detail = await api(`/api/kb/series/${KB.series.id}/tasks/${id}`);
    const task = detail.task || {};
    const logs = detail.logs || [];
    const logHtml = logs.length
      ? logs.map((log) => `
        <div class="kb-log">
          <span class="hint">${kbFmtTime(log.createdAt)}</span>
          <strong>${esc(log.stage || '—')}</strong>
          <span class="kb-pill ${log.level === 'ERROR' ? 'bad' : log.level === 'WARN' ? 'warn' : 'muted'}">${esc(log.level || 'INFO')}</span>
          <span>${esc(log.message || '')}</span>
          ${log.costMs ? `<span class="hint">${log.costMs} ms</span>` : ''}
        </div>`).join('')
      : '<p class="hint">暂无任务日志。</p>';
    kbOpenDialog(`任务 #${task.id} · ${KB_TASK_TYPE_LABEL[task.type] || task.type}`, `
      <div class="kb-task-summary">
        <div><span>状态</span>${kbPill(kbTaskTone(task.status), KB_TASK_LABEL[task.status] || task.status)}</div>
        <div><span>文档</span><strong>${esc(task.documentName || '—')}</strong></div>
        <div><span>进度</span><strong>${task.progress || 0}%</strong></div>
        <div><span>阶段</span><strong>${esc(task.stage || '—')}</strong></div>
        <div><span>操作人</span><strong>${esc(task.operatorName || '—')}</strong></div>
        <div><span>重试次数</span><strong>${task.retryCount || 0}</strong></div>
        <div><span>开始</span><strong>${kbFmtTime(task.startedAt)}</strong></div>
        <div><span>结束</span><strong>${kbFmtTime(task.finishedAt)}</strong></div>
      </div>
      ${task.errorMessage ? `<p class="kb-error-text">${esc(task.errorCode ? '[' + task.errorCode + '] ' : '')}${esc(task.errorMessage)}</p>` : ''}
      <div class="kb-log-list">${logHtml}</div>`, null, '关闭');
  } catch (error) {
    showError(error);
  }
}

async function kbCancelTask(id) {
  if (!confirm('取消该任务？正在执行的任务会在当前步骤结束后停止。')) return;
  try {
    await api(`/api/kb/series/${KB.series.id}/tasks/${id}/cancel`, { method: 'POST' });
    toast('已请求取消任务');
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

async function kbRetryTask(id) {
  try {
    await api(`/api/kb/series/${KB.series.id}/tasks/${id}/retry`, { method: 'POST' });
    toast('已创建重试任务');
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

function kbShowRebuildDialog() {
  const failed = (KB.data.summary && KB.data.summary.failed) || KB.series.failedDocCount || 0;
  kbOpenDialog('重建索引', `
    <div class="kb-dialog-fields">
      <label>重建范围<select name="mode">
        <option value="FAILED">仅重建失败文档（${kbFmtNum(failed)} 份）</option>
        <option value="FULL">重建全部文档</option>
      </select></label>
      <p class="hint">重建会按当前生效配置重新解析、分块并写入索引，执行期间检索结果可能不完整。</p>
    </div>`, async (data) => {
    const tasks = await api(`/api/kb/series/${KB.series.id}/tasks/rebuild`, {
      method: 'POST',
      body: { mode: String(data.get('mode') || 'FAILED') },
    });
    toast(`已创建 ${(tasks || []).length} 个重建任务`);
    await refreshKbSeries();
    await loadKbTabData();
  });
}

// ---------------------------------------------------------------- 检索

async function kbRunRetrieval() {
  const query = (($('#kb-retrieval-query') || {}).value || '').trim();
  if (!query) {
    toast('请输入检索问题', 'error');
    return;
  }
  KB.retrievalForm = {
    query,
    topK: Number(($('#kb-retrieval-topk') || {}).value || 5),
    threshold: Number(($('#kb-retrieval-threshold') || {}).value || 0),
    withAnswer: Boolean(($('#kb-retrieval-answer') || {}).checked),
  };
  KB.tabLoading = true;
  KB.expandedParents.clear();
  paintKb();
  try {
    const result = await api(`/api/kb/series/${KB.series.id}/retrieval/test`, {
      method: 'POST',
      body: KB.retrievalForm,
    });
    if (!KB.series) return;
    KB.data.retrieval = result;
  } catch (error) {
    showError(error);
  } finally {
    KB.tabLoading = false;
    if (KB.tab === 'retrieval') paintKb();
  }
}

// ---------------------------------------------------------------- 成员

async function kbUpdateMemberRole(memberId, role) {
  try {
    await api(`/api/kb/series/${KB.series.id}/members/${memberId}`, {
      method: 'PATCH',
      body: { role },
    });
    toast('成员角色已更新');
    await loadKbTabData();
  } catch (error) {
    showError(error);
    await loadKbTabData();
  }
}

function kbShowMemberGrantDialog() {
  const candidates = KB.data.candidates || [];
  if (!candidates.length) {
    toast('没有可添加的用户或部门', 'error');
    return;
  }
  const users = candidates.filter((item) => item.subjectType === 'USER');
  const depts = candidates.filter((item) => item.subjectType === 'DEPT');
  const option = (item) => `<option value="${item.subjectType}:${item.subjectId}">${esc(item.name)}${item.extra ? ' · ' + esc(item.extra) : ''}</option>`;
  kbOpenDialog('添加成员', `
    <div class="kb-dialog-fields">
      <label>用户或部门<select name="subjects" multiple size="8" required>
        <optgroup label="用户">${users.map(option).join('')}</optgroup>
        <optgroup label="部门">${depts.map(option).join('')}</optgroup>
      </select></label>
      <label>角色<select name="role">
        <option value="READER">只读</option>
        <option value="EDITOR">编辑</option>
        <option value="ADMIN">管理员</option>
      </select></label>
      <p class="hint">按住 Ctrl / Cmd 可多选；同一次添加的用户与部门类型需一致。</p>
    </div>`, async (data) => {
    const values = data.getAll('subjects').map(String).filter(Boolean);
    if (!values.length) throw new Error('请选择要添加的用户或部门');
    const types = new Set(values.map((value) => value.split(':')[0]));
    if (types.size > 1) throw new Error('请分开添加用户与部门');
    const type = values[0].split(':')[0];
    await api(`/api/kb/series/${KB.series.id}/members`, {
      method: 'POST',
      body: {
        subjectType: type,
        subjectIds: values.map((value) => Number(value.split(':')[1])),
        role: String(data.get('role') || 'READER'),
      },
    });
    toast('成员已添加');
    await loadKbTabData();
  });
}

async function kbRemoveMember(memberId) {
  const member = (KB.data.members || []).find((item) => item.id === memberId);
  if (!member) return;
  if (!confirm(`移除成员「${member.subjectName}」？`)) return;
  try {
    await api(`/api/kb/series/${KB.series.id}/members/${memberId}`, { method: 'DELETE' });
    toast('成员已移除');
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

// ---------------------------------------------------------------- 配置

async function kbLoadConfigVersion(versionId) {
  try {
    const view = await api(`/api/kb/series/${KB.series.id}/config-versions/${versionId}`);
    KB.configDraft = kbDraftFromVersion(view);
    toast(`已载入配置 v${view.versionNo}，保存后生成新版本`);
    paintKb();
  } catch (error) {
    showError(error);
  }
}

async function kbSaveConfig() {
  if (!KB.series || !KB.configDraft || !kbCan('ADMIN')) return;
  const draft = KB.configDraft;
  try {
    const result = await api(`/api/kb/series/${KB.series.id}/config-versions`, {
      method: 'POST',
      body: {
        indexStrategy: draft.indexStrategy,
        parseConfig: draft.parse,
        chunkConfig: draft.chunk,
        retrievalConfig: draft.retrieval,
        changeNote: draft.changeNote || '',
        rebuildMode: draft.rebuildMode || 'NONE',
      },
    });
    const tasks = (result && result.rebuildTasks) || [];
    toast(tasks.length ? `配置已保存，已创建 ${tasks.length} 个重建任务` : '配置已保存');
    if (result && result.rebuildError) console.warn('[知识库配置] 重建失败', result.rebuildError);
    if (result && result.config) KB.data.config = result.config;
    KB.configDraft = result && result.config
      ? kbDraftFromVersion(result.config)
      : draft;
    KB.configDraft.changeNote = '';
    KB.configDraft.rebuildMode = 'NONE';
    await refreshKbSeries();
    await loadKbTabData();
  } catch (error) {
    showError(error);
  }
}

// ---------------------------------------------------------------- 通用对话框

function kbOpenDialog(title, bodyHtml, onSubmit, okText) {
  const dialog = $('#kb-dialog');
  if (!dialog) return;
  KB.dialogSubmitHandler = onSubmit || null;
  const form = $('#kb-dialog-form');
  form.reset();
  $('#kb-dialog-title').textContent = title;
  $('#kb-dialog-body').innerHTML = bodyHtml;
  const ok = $('#kb-dialog-ok');
  ok.textContent = okText || '确定';
  ok.disabled = false;
  const cancel = $('#kb-dialog-cancel');
  if (cancel) cancel.textContent = onSubmit ? '取消' : '关闭';
  dialog.showModal();
  const first = $('#kb-dialog-body input, #kb-dialog-body select, #kb-dialog-body textarea');
  if (first) setTimeout(() => first.focus(), 30);
}

async function kbOnDialogSubmit(event) {
  event.preventDefault();
  const handler = KB.dialogSubmitHandler;
  const dialog = $('#kb-dialog');
  if (!handler) {
    if (dialog) dialog.close();
    return;
  }
  const form = $('#kb-dialog-form');
  const ok = $('#kb-dialog-ok');
  ok.disabled = true;
  try {
    await handler(new FormData(form));
    if (dialog) dialog.close();
  } catch (error) {
    showError(error);
  } finally {
    ok.disabled = false;
  }
}

function kbInitDialog() {
  const dialog = $('#kb-dialog');
  if (!dialog) return;
  const form = $('#kb-dialog-form');
  if (form) form.addEventListener('submit', kbOnDialogSubmit);
  const cancel = $('#kb-dialog-cancel');
  if (cancel) cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    KB.dialogSubmitHandler = null;
    $('#kb-dialog-body').innerHTML = '';
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', kbInitDialog);
} else {
  kbInitDialog();
}

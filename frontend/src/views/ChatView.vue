<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { api } from '../api/http';
import { resumeTurn, streamChat } from '../api/sse';
import { clearActiveTurn, loadActiveTurn, saveActiveTurn } from '../stores/activeTurn';
import { createTabSync } from '../stores/tabSync';
import { auth } from '../stores/auth';
import { toast } from '../stores/toast';

const sessions = ref([]);
const activeId = ref(null);
const messages = ref([]);
const feedbackMap = reactive({});
const draft = ref('');
const busy = ref(false);
const streaming = ref(null);
const detail = ref(null);
const routePanel = ref(null);
const routeBusy = ref(false);
const editingId = ref(null);
const editingTitle = ref('');
const messageBox = ref(null);
const gapNotice = ref('');
const kbBound = ref([]);
const kbMode = ref('ALL');
const kbMax = ref(3);
const kbPanel = ref(false);
const kbLoading = ref(false);
const kbSaving = ref(false);
const kbOptions = ref([]);
const kbSelection = ref([]);
const sourceListModal = ref(null);
const citationModal = ref(null);

let controller = null;
let turnState = { turnId: null, lastSeq: 0, startedAt: 0 };
let scrollQueued = false;
let resuming = false;
let tabSync = null;
let mirroring = false;
let mirrorFinishing = false;
let persistTimer = null;
let unmounted = false;
const RESUME_BACKOFF_MS = [500, 1000, 2000, 4000, 8000];
// 打印机式输出：文本先入队再按固定节奏吐出，续流重放时也能逐字可见。
// 待输出越多吐字越快，避免长回答在 done 之后还要等十几秒。
const TYPE_INTERVAL_MS = 16;
const TYPE_BASE_CHARS = 2;
const TYPE_BURST_CHARS = 6;
const TYPE_BURST_THRESHOLD = 300;
const TYPE_FLUSH_TIMEOUT_MS = 15000;
let typeTimer = null;

const activeSession = computed(() => sessions.value.find((item) => item.id === activeId.value) || null);
const canSend = computed(() => !busy.value && draft.value.trim().length > 0);

/** 流式阶段只统计已完成的 skill_read 次数，用于提示本轮读了多少个技能文件。 */
const streamingFileCount = computed(() => {
  const tools = streaming.value?.tools || [];
  return tools.filter((tool) => tool.name === 'skill_read' && tool.state !== 'RUNNING').length;
});

const questionRail = computed(() =>
  messages.value
    .filter((item) => item.role === 'USER' && item.id)
    .map((item, index) => ({ id: String(item.id), no: index + 1, text: item.content || '' }))
);
const activeQuestion = ref(null);

const suggestions = [
  '帮我把 2026-10 的工资算一下',
  '研发部上个月的社保基数是多少？',
  '北京社保政策的缴费比例是多少？',
];

function pathLabel(routing) {
  if (!routing) return '';
  return (
    {
      PIPELINE: '流水线',
      AGENT: '单智能体',
      FLOW: '流程',
      GRAPH: '图编排',
      TEAM_ACTIVE: '动态团队',
      ACTION: '操作执行',
    }[routing.path] || routing.path
  );
}

function pathClass(routing) {
  if (!routing) return '';
  const kind = routing.from_path ? 'fallback' : String(routing.path || '').toLowerCase();
  return `route-${kind}`;
}

function kbSourceLabel(source) {
  return { router: '模型', force: '规则强制', semantic: '语义', fallback: '路由失败' }[source] || '—';
}

function kbKindLabel(kind) {
  return { read: '只读检索', admin: '知识库管理', none: '不检索' }[kind] || kind || '—';
}

function semanticLabel(label) {
  return { kb_read: '制度问答', kb_admin: '知识库管理', other: '无关任务', unavailable: '不可用' }[label] || label || '—';
}

function formatTime(value) {
  if (!value) return '';
  const text = String(value).replace('T', ' ').slice(0, 16);
  return text.length > 10 ? text.slice(5) : text;
}

function formatBytes(value) {
  if (!Number.isFinite(value) || value <= 0) return '';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(2)} MB`;
}

function roleLabel(role) {
  return role === 'USER' ? '我' : role === 'ERROR' ? '系统' : '协同智能体';
}

function showTime(index) {
  const current = messages.value[index];
  const previous = messages.value[index - 1];
  if (!current || !current.created_at) return index === 0;
  if (!previous || !previous.created_at) return true;
  return new Date(current.created_at).getTime() - new Date(previous.created_at).getTime() > 5 * 60_000;
}

function fullTime(value) {
  if (!value) return '';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = (part) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 把正文里的 [n] 拆成可点击角标；只认 citations 中存在的序号，其余保持原文。 */
function contentParts(content, citations) {
  const text = content || '';
  const list = citations || [];
  if (!text || !list.length) return [{ text }];
  const byIndex = new Map(list.map((cite) => [Number(cite.index), cite]));
  const parts = [];
  const pattern = /\[(\d{1,2})\]/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(text))) {
    const index = Number(match[1]);
    if (!byIndex.has(index)) continue;
    if (match.index > last) parts.push({ text: text.slice(last, match.index) });
    parts.push({ index, cite: byIndex.get(index) });
    last = match.index + match[0].length;
  }
  if (!parts.length) return [{ text }];
  if (last < text.length) parts.push({ text: text.slice(last) });
  return parts;
}

function hasInlineCitations(content, citations) {
  return contentParts(content, citations).some((part) => part.index);
}

function citationGroups(citations) {
  const groups = [];
  const bySeries = new Map();
  for (const cite of citations || []) {
    const key = String(cite.series_id ?? cite.series_name ?? '');
    let group = bySeries.get(key);
    if (!group) {
      group = {
        series_id: cite.series_id ?? key,
        series_name: cite.series_name || '未知知识库',
        items: [],
      };
      bySeries.set(key, group);
      groups.push(group);
    }
    group.items.push(cite);
  }
  return groups;
}

function citationMeta(cite) {
  const parts = [];
  if (cite.page_no) parts.push(`第 ${cite.page_no} 页`);
  if (cite.title_path) parts.push(cite.title_path);
  if (cite.chunk_seq) parts.push(`分块 #${cite.chunk_seq}`);
  return parts.join(' · ') || '位置未标注';
}

function citationHeadline(cite) {
  const parts = [cite.series_name || '未知知识库', cite.document_name || '未知文档'];
  if (cite.page_no) parts.push(`第 ${cite.page_no} 页`);
  if (cite.title_path) parts.push(cite.title_path);
  if (cite.chunk_seq) parts.push(`分块 #${cite.chunk_seq}`);
  return parts.join(' › ');
}

function citationText(cite) {
  const headline = citationHeadline(cite);
  const snapshot = cite.content_snapshot || cite.snippet || '';
  return snapshot ? `${headline}\n${snapshot}` : headline;
}

function isLowConfidence(citations) {
  return Boolean(citations?.length) && citations.every((cite) => cite.low_confidence);
}

/** 点击正文角标直接打开对应来源详情，不再滚动定位。 */
function focusCitation(citations, messageId, index) {
  const cite = (citations || []).find((item) => Number(item.index) === Number(index));
  if (cite) openCitation(cite, messageId ?? null);
}

/** 来源区默认折叠为一个入口，点击后在弹窗中按库分组展示。 */
function openSourceList(citations, messageId, content) {
  if (!citations?.length) return;
  sourceListModal.value = {
    messageId: messageId ?? null,
    citations,
    low: isLowConfidence(citations),
    hint: !hasInlineCitations(content || '', citations),
  };
}

function openCitationFromList(cite) {
  const messageId = sourceListModal.value?.messageId ?? null;
  sourceListModal.value = null;
  openCitation(cite, messageId);
}

async function copyAllCitations() {
  const list = sourceListModal.value?.citations || [];
  if (!list.length) return;
  try {
    await navigator.clipboard.writeText(list.map((cite) => citationText(cite)).join('\n\n'));
    toast('来源已复制');
  } catch (error) {
    toast('复制失败，请手动选择文本', 'error');
  }
}

/** 打开来源详情时校验当前文档状态；校验失败只降级提示，不影响快照展示。 */
async function openCitation(cite, messageId) {
  if (!cite) return;
  const base = { cite, messageId, loading: true, state: '', statusText: '' };
  citationModal.value = base;
  if (!cite.series_id || !cite.document_id) {
    citationModal.value = {
      ...base,
      loading: false,
      state: 'unknown',
      statusText: '缺少来源定位信息，只能查看回答时的片段快照。',
    };
    return;
  }
  try {
    const document = await api(`/api/kb/series/${cite.series_id}/documents/${cite.document_id}`);
    if (citationModal.value?.cite !== cite) return;
    // 文档接口按 lowerCamel 输出，历史引用按 snake_case 落库，两种命名都兼容。
    const updatedAt = document?.updated_at || document?.updatedAt;
    const updated = updatedAt && cite.retrieved_at
      && new Date(updatedAt).getTime() > new Date(cite.retrieved_at).getTime() + 1000;
    citationModal.value = {
      ...base,
      loading: false,
      state: updated ? 'updated' : 'ok',
      statusText: updated
        ? `来源文档已更新（${fullTime(updatedAt)}），以下为回答时的快照。`
        : `来源文档状态：${document?.status || '未知'}，以下为回答时快照。`,
    };
  } catch (error) {
    if (citationModal.value?.cite !== cite) return;
    const state = error.status === 401 || error.status === 403
      ? 'forbidden'
      : error.status === 404
        ? 'missing'
        : 'unknown';
    citationModal.value = {
      ...base,
      loading: false,
      state,
      statusText: state === 'forbidden'
        ? '当前账号无权打开该来源原文，以下是回答时的片段快照。'
        : state === 'missing'
          ? '来源文档已删除或已不可见，以下是回答时的片段快照。'
          : '暂时无法校验来源状态，以下是回答时的片段快照。',
    };
  }
}

async function copyCitation(cite) {
  if (!cite) return;
  try {
    await navigator.clipboard.writeText(citationText(cite));
    toast('引用已复制');
  } catch (error) {
    toast('复制失败，请手动选择文本', 'error');
  }
}

function excerpt(text, limit = 80) {
  const value = String(text || '').replace(/\s+/g, ' ').trim();
  if (!value) return '未命名问题';
  return value.length > limit ? `${value.slice(0, limit)}…` : value;
}

/* 右侧问题导航：滚动时高亮当前问题，点击平滑定位。 */
function onMessagesScroll() {
  const box = messageBox.value;
  if (!box) return;
  const top = box.getBoundingClientRect().top;
  let current = null;
  box.querySelectorAll('[data-question-id]').forEach((node) => {
    if (node.getBoundingClientRect().top - top <= 96) current = node.dataset.questionId;
  });
  activeQuestion.value = current;
}

function jumpToQuestion(id) {
  const box = messageBox.value;
  if (!box) return;
  const node = box.querySelector(`[data-question-id="${id}"]`);
  if (!node) return;
  const target = Math.max(
    node.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop - 12,
    0
  );
  const before = box.scrollTop;
  box.scrollTo({ top: target, behavior: 'smooth' });
  // 无头浏览器或禁用动画的环境会忽略平滑滚动，此时回退为直接定位。
  window.setTimeout(() => {
    if (box.scrollTop === before && before !== target) box.scrollTop = target;
  }, 320);
  activeQuestion.value = String(id);
}

/* 会话导出：纯前端拼 Markdown，包含角色、时间、正文与助手链路信息。 */
function exportMarkdown() {
  if (!activeId.value || busy.value || !messages.value.length) return;
  const title = activeSession.value?.title || '未命名会话';
  const lines = [
    `# ${title}`,
    '',
    `> 会话 #${activeId.value} · 导出时间 ${fullTime(new Date())} · 共 ${messages.value.length} 条消息`,
    '',
  ];
  messages.value.forEach((message) => {
    const who = message.role === 'USER' ? '我' : message.role === 'ERROR' ? '系统' : '协同智能体';
    lines.push(`## ${who} · ${fullTime(message.created_at) || '—'}`);
    if (message.role === 'ASSISTANT' && (message.routing || message.model)) {
      const meta = [];
      if (message.routing) meta.push(`链路 ${pathLabel(message.routing)}（${message.routing.path}）`);
      if (message.model) meta.push(`模型 ${message.model}`);
      if (message.routing?.agent_ids?.length) meta.push(`智能体 ${message.routing.agent_ids.join('、')}`);
      if (message.routing?.kb) {
        const kb = message.routing.kb;
        const parts = [`知识库${kb.need ? '检索' : '不检索'}`, kbKindLabel(kb.kind), `来源 ${kbSourceLabel(kb.source)}`];
        if (kb.reroute) parts.push('已改道检索智能体');
        meta.push(parts.join(' · '));
      }
      if (meta.length) lines.push('', `> ${meta.join(' · ')}`);
    }
    if (message.tools?.length) lines.push('', `> 工具调用 ${message.tools.length} 次`);
    lines.push('', message.content || '（空）', '');
    if (message.role === 'ASSISTANT' && message.citations?.length) {
      lines.push('### 参考来源');
      message.citations.forEach((cite) => {
        lines.push(`${cite.index}. ${citationHeadline(cite)}`);
        const recall = [];
        if (cite.routes?.length) recall.push(cite.routes.join(' / '));
        if (Number.isFinite(Number(cite.score))) recall.push(Number(cite.score).toFixed(3));
        if (recall.length) lines.push(`   - 召回 ${recall.join(' · ')}`);
        if (cite.snippet) lines.push(`   > ${cite.snippet}`);
      });
      lines.push('');
    }
  });

  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  const safe = title.replace(/[\\/:*?"<>|\s]+/g, '-').replace(/^-+|-+$/g, '') || 'conversation';
  const now = new Date();
  const pad = (part) => String(part).padStart(2, '0');
  link.href = url;
  link.download = `${safe}-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}.md`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function pretty(value) {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch (error) {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
}

function scrollBottom(force = false) {
  if (scrollQueued) return;
  scrollQueued = true;
  requestAnimationFrame(() => {
    scrollQueued = false;
    const box = messageBox.value;
    if (!box) return;
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 220;
    if (force || nearBottom) box.scrollTop = box.scrollHeight;
  });
}

async function loadSessions() {
  try {
    sessions.value = (await api('/api/chat/sessions')) || [];
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function loadMessages(silent = false) {
  if (!activeId.value) return;
  try {
    const list = (await api(`/api/chat/sessions/${activeId.value}/messages`)) || [];
    messages.value = list;
    loadFeedback(list);
    if (!silent) scrollBottom(true);
    await nextTick();
    onMessagesScroll();
  } catch (error) {
    if (!silent) toast(error.message, 'error');
  }
}

async function loadKnowledge(sessionId) {
  if (!sessionId) {
    kbBound.value = [];
    kbMode.value = 'ALL';
    return;
  }
  try {
    const view = await api(`/api/chat/sessions/${sessionId}/knowledge`);
    kbBound.value = Array.isArray(view?.series) ? view.series : [];
    kbMode.value = view?.mode === 'SELECTED' ? 'SELECTED' : 'ALL';
    if (view?.max_series) kbMax.value = view.max_series;
  } catch (error) {
    kbBound.value = [];
    kbMode.value = 'ALL';
  }
}

async function openKbPanel() {
  if (!activeId.value) {
    toast('请先新建会话', 'warning');
    return;
  }
  kbPanel.value = true;
  kbLoading.value = true;
  kbSelection.value = kbBound.value.map((item) => item.id);
  try {
    const page = await api('/api/kb/series?size=100');
    kbOptions.value = (page?.items || []).map((item) => ({
      id: item.id,
      name: item.name,
      role: item.role,
      docCount: item.docCount || 0,
      searchable: item.status === 'ACTIVE',
    }));
  } catch (error) {
    kbOptions.value = [];
    toast(error.message, 'error');
  } finally {
    kbLoading.value = false;
  }
}

function toggleKbOption(item) {
  if (!item.searchable) return;
  const current = kbSelection.value;
  if (current.includes(item.id)) {
    kbSelection.value = current.filter((id) => id !== item.id);
    return;
  }
  if (current.length >= kbMax.value) {
    toast(`单个会话最多绑定 ${kbMax.value} 个知识库`, 'warning');
    return;
  }
  kbSelection.value = [...current, item.id];
}

async function saveKbBinding() {
  if (!activeId.value || kbSaving.value) return;
  kbSaving.value = true;
  try {
    const view = await api(`/api/chat/sessions/${activeId.value}/knowledge`, {
      method: 'PUT',
      body: { series_ids: kbSelection.value },
    });
    kbBound.value = Array.isArray(view?.series) ? view.series : [];
    kbMode.value = view?.mode === 'SELECTED' ? 'SELECTED' : 'ALL';
    if (view?.max_series) kbMax.value = view.max_series;
    kbPanel.value = false;
    toast(
      kbMode.value === 'ALL'
        ? '已切换为全部可读，从下一轮生效'
        : '检索范围已收窄，从下一轮生效',
      'success'
    );
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    kbSaving.value = false;
  }
}

function kbRoleLabel(role) {
  return { READER: '只读', EDITOR: '编辑', ADMIN: '管理', OWNER: '拥有者' }[role] || role || '';
}

async function loadFeedback(list) {
  const sessionId = activeId.value;
  const assistants = list.filter((item) => item.role === 'ASSISTANT' && item.id).slice(-30);
  await Promise.allSettled(
    assistants.map(async (item) => {
      try {
        const saved = await api(
          `/api/chat/sessions/${sessionId}/messages/${item.id}/feedback`
        );
        if (saved && saved.rating) feedbackMap[item.id] = saved.rating;
      } catch (error) {
        // 反馈是辅助信息，读取失败不打扰用户
      }
    })
  );
}

async function selectSession(id) {
  if (busy.value) {
    toast('本轮回答尚未结束，请先停止或等待完成', 'warning');
    return;
  }
  if (activeId.value === id) return;
  activeId.value = id;
  detail.value = null;
  routePanel.value = null;
  stopTyping();
  streaming.value = null;
  gapNotice.value = '';
  await loadKnowledge(id);
  await loadMessages();
  // 刷新或切回会话时接上仍在执行的轮次
  await resumeActiveTurn(id, { rebuild: true });
}

async function createSession() {
  if (busy.value) return;
  try {
    const created = await api('/api/chat/sessions', { method: 'POST', body: {} });
    sessions.value = [created, ...sessions.value];
    activeId.value = created.id;
    messages.value = [];
    detail.value = null;
    draft.value = '';
    gapNotice.value = '';
    kbBound.value = [];
    kbMode.value = 'ALL';
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function renameSession(session) {
  editingId.value = session.id;
  editingTitle.value = session.title || '';
  await nextTick();
  const input = document.querySelector('.session-rename');
  if (input) input.focus();
}

async function commitRename(session) {
  if (editingId.value !== session.id) return;
  const title = editingTitle.value.trim();
  editingId.value = null;
  if (!title || title === session.title) return;
  try {
    const updated = await api(`/api/chat/sessions/${session.id}`, {
      method: 'PUT',
      body: { title },
    });
    Object.assign(session, updated);
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function deleteSession(session) {
  if (busy.value) return;
  if (!window.confirm(`删除会话「${session.title}」？消息将一并删除。`)) return;
  try {
    await api(`/api/chat/sessions/${session.id}`, { method: 'DELETE' });
    sessions.value = sessions.value.filter((item) => item.id !== session.id);
    if (activeId.value === session.id) {
      const next = sessions.value.length ? sessions.value[0].id : null;
      activeId.value = null;
      messages.value = [];
      kbBound.value = [];
      kbMode.value = 'ALL';
      if (next) await selectSession(next);
    }
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function resetSession() {
  if (!activeId.value || busy.value) return;
  if (!window.confirm('重置后将清空本会话的上下文与全部消息，确定继续？')) return;
  try {
    await api(`/api/chat/sessions/${activeId.value}/reset`, { method: 'POST' });
    detail.value = null;
    stopTyping();
    streaming.value = null;
    gapNotice.value = '';
    clearTurnMarker(activeId.value);
    await loadMessages();
    toast('会话已重置', 'success');
  } catch (error) {
    toast(error.message, 'error');
  }
}

function pushEvent(tag, cls, text) {
  streaming.value.events.push({ tag, cls, text });
}

function persistTurn(force = false) {
  if (!activeId.value || !turnState.turnId) return;
  if (!force) {
    if (persistTimer) return;
    persistTimer = window.setTimeout(() => {
      persistTimer = null;
      persistTurn(true);
    }, 800);
    return;
  }
  if (persistTimer) {
    window.clearTimeout(persistTimer);
    persistTimer = null;
  }
  saveActiveTurn(activeId.value, turnState);
}

/** 清理本地续传标记；同时取消尚未落盘的节流写入，避免完成后又被写回。 */
function clearTurnMarker(sessionId, turnId) {
  if (persistTimer && sessionId === activeId.value) {
    window.clearTimeout(persistTimer);
    persistTimer = null;
  }
  clearActiveTurn(sessionId, turnId);
}

function touchSeq(data) {
  if (data && Number.isFinite(data.seq)) {
    turnState.lastSeq = Math.max(turnState.lastSeq, data.seq);
    persistTurn();
  }
}

function newStreamingState() {
  return {
    text: '',
    pendingText: '',
    thinking: '',
    phase: '',
    events: [],
    tools: [],
    agents: [],
    proposals: [],
    citations: [],
    routing: null,
    model: '',
    messageId: null,
    cache: null,
    error: '',
    finished: false,
    cancelled: false,
    recovering: false,
    resumable: false,
    gap: false,
    gapMessage: '',
  };
}

function delay(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/** 定时从待输出队列取字，模拟打印机逐字吐字。 */
function pumpTyping() {
  const state = streaming.value;
  if (!state) {
    stopTyping();
    return;
  }
  const pending = state.pendingText || '';
  if (!pending) {
    stopTyping();
    return;
  }
  const take = Math.min(
    pending.length > TYPE_BURST_THRESHOLD ? TYPE_BURST_CHARS : TYPE_BASE_CHARS,
    pending.length
  );
  state.text += pending.slice(0, take);
  state.pendingText = pending.slice(take);
  scrollBottom();
  if (!state.pendingText) stopTyping();
}

function startTyping() {
  if (typeTimer) return;
  typeTimer = window.setInterval(pumpTyping, TYPE_INTERVAL_MS);
}

function stopTyping() {
  if (!typeTimer) return;
  window.clearInterval(typeTimer);
  typeTimer = null;
}

/** SSE 文本只入队，由定时器按节奏吐到气泡里；续流重放同样逐字呈现。 */
function enqueueText(chunk) {
  const state = streaming.value;
  if (!state || !chunk) return;
  state.pendingText += chunk;
  startTyping();
}

/** 立即吐出剩余文本：停止、切换会话或卸载时使用。 */
function flushTyping() {
  const state = streaming.value;
  if (state && state.pendingText) {
    state.text += state.pendingText;
    state.pendingText = '';
  }
  stopTyping();
}

/** 收尾前等待队列吐完；超时则一次性补齐，避免 UI 被长队列卡住。 */
function waitTyping(timeoutMs = TYPE_FLUSH_TIMEOUT_MS) {
  const state = streaming.value;
  if (!state || !state.pendingText) return Promise.resolve();
  return new Promise((resolve) => {
    const startedAt = Date.now();
    const tick = window.setInterval(() => {
      const current = streaming.value;
      if (!current || current !== state || !current.pendingText) {
        window.clearInterval(tick);
        resolve();
        return;
      }
      if (Date.now() - startedAt >= timeoutMs) {
        window.clearInterval(tick);
        flushTyping();
        resolve();
      }
    }, 40);
  });
}

function handleEvent(event, data) {
  const state = streaming.value;
  if (!state) return;
  touchSeq(data);
  switch (event) {
    case 'turn':
      // 续流重放时同一个 turn 不应把游标回退到 0
      if (data.turn_id && turnState.turnId !== data.turn_id) {
        turnState = { turnId: data.turn_id, lastSeq: 0, startedAt: Date.now() };
        persistTurn(true);
      }
      break;
    case 'snapshot':
      // 冷恢复直接替换为已合并状态，不再逐字重放历史增量。
      stopTyping();
      state.pendingText = '';
      state.text = data.text || '';
      if (Array.isArray(data.tools)) {
        state.tools = data.tools.map((name) => ({
          id: name,
          name,
          args: '',
          state: 'DONE',
          duration: null,
        }));
      }
      if (Array.isArray(data.agents)) {
        state.agents = data.agents.map((id) => {
          const known = state.agents.find((item) => item.id === id);
          return known || { id, label: id, text: '' };
        });
      }
      if (data.phase) state.phase = data.phase;
      if (Array.isArray(data.citations)) state.citations = data.citations;
      if (state.gap) {
        state.gap = false;
        state.gapMessage = '';
        gapNotice.value = '';
      }
      break;
    case 'start':
      state.model = data.model || state.model;
      break;
    case 'agent_start':
      state.agents.push({ id: data.agent, label: data.label || data.agent, text: '' });
      pushEvent('智能体', 'agent', `${data.label || data.agent} 开始工作`);
      break;
    case 'agent_text': {
      if (state.gap) break;
      const agent = state.agents.find((item) => item.id === data.agent);
      if (agent) agent.text += data.text || '';
      break;
    }
    case 'agent_end': {
      const agent = state.agents.find((item) => item.id === data.agent);
      pushEvent('智能体', 'agent', `${agent ? agent.label : data.agent} 完成`);
      break;
    }
    case 'thinking':
      if (state.gap) break;
      state.thinking += data.text || '';
      break;
    case 'phase':
      state.phase = data.label || '';
      pushEvent('阶段', 'phase', data.label || '进入下一阶段');
      break;
    case 'context':
      pushEvent('上下文', 'context', `压缩 ${data.compressed} 条历史，保留 ${data.kept} 条`);
      break;
    case 'tool_start':
      state.tools.push({
        id: data.tool_call_id,
        name: data.name,
        args: '',
        state: 'RUNNING',
        duration: null,
      });
      pushEvent('工具', 'tool', `调用 ${data.name}`);
      break;
    case 'tool_args': {
      if (state.gap) break;
      const tool = state.tools.find((item) => item.id === data.tool_call_id);
      if (tool) tool.args += data.text || '';
      break;
    }
    case 'tool_result': {
      const tool = state.tools.find((item) => item.id === data.tool_call_id);
      if (tool) {
        tool.state = data.state || 'DONE';
        tool.duration = data.duration_ms;
        tool.amounts = data.amounts;
        tool.employees = data.employees;
        tool.period = data.period;
        tool.department = data.department;
        tool.truncated = data.truncated;
      }
      pushEvent('工具', 'tool', `${data.name} ${data.state === 'ERROR' ? '失败' : '完成'}`);
      break;
    }
    case 'gap':
      // 断点已过期：丢弃残缺前缀并停止拼接，完成后以落库消息呈现完整回答。
      state.gap = true;
      state.gapMessage = data.message || '部分回答过程已过期，完成后将以最终消息展示';
      state.text = '';
      state.pendingText = '';
      state.thinking = '';
      state.agents.forEach((agent) => {
        agent.text = '';
      });
      stopTyping();
      gapNotice.value = state.gapMessage;
      toast(state.gapMessage, 'warning');
      break;
    case 'text':
      if (state.gap) break;
      enqueueText(data.text || '');
      break;
    case 'action_proposal':
      if (data.proposal) state.proposals.push(data.proposal);
      break;
    case 'citation':
      if (Array.isArray(data.citations)) state.citations = data.citations;
      break;
    case 'done':
      state.finished = true;
      state.routing = data.routing || null;
      state.model = data.model || state.model;
      state.messageId = data.message_id || null;
      state.cache = data.cache || null;
      if (Array.isArray(data.citations)) state.citations = data.citations;
      if (Array.isArray(data.proposals)) state.proposals = data.proposals;
      // 模型没有流式文本时才用 done 的整段回复兜底，避免与队列重复；断点过期时交给落库消息。
      if (!state.gap && !state.text && !state.pendingText && data.reply) enqueueText(data.reply);
      clearTurnMarker(activeId.value, turnState.turnId);
      break;
    case 'error':
      if (data.cancelled) {
        state.cancelled = true;
      } else {
        state.error = data.message || '回答中断';
        toast(state.error, 'error');
      }
      clearTurnMarker(activeId.value, turnState.turnId);
      break;
    default:
      break;
  }
}

/* 多标签页：一个标签页拉流，其余标签页镜像事件，避免重复连接与重复写入。 */
function streamSnapshot() {
  const state = streaming.value;
  if (!state) return null;
  return {
    turnId: turnState.turnId || null,
    lastSeq: turnState.lastSeq,
    state: {
      text: state.text + state.pendingText,
      thinking: state.thinking,
      phase: state.phase,
      events: state.events,
      tools: state.tools,
      agents: state.agents,
      proposals: state.proposals,
      citations: state.citations,
      routing: state.routing,
      model: state.model,
      messageId: state.messageId,
      cache: state.cache,
      error: state.error,
      finished: state.finished,
      cancelled: state.cancelled,
      gap: state.gap,
      gapMessage: state.gapMessage,
    },
  };
}

function beginMirror(sessionId) {
  mirroring = true;
  busy.value = true;
  if (!streaming.value) streaming.value = newStreamingState();
  if (sessionId === activeId.value) scrollBottom();
}

function applyMirrorState(sessionId, turnId, snapshot) {
  if (sessionId !== activeId.value) return;
  beginMirror(sessionId);
  if (snapshot && snapshot.state) {
    stopTyping();
    Object.assign(streaming.value, snapshot.state, {
      pendingText: '',
      recovering: false,
      resumable: false,
    });
    turnState.lastSeq = Math.max(turnState.lastSeq, Number(snapshot.lastSeq) || 0);
  }
  if (turnId) {
    turnState = { turnId, lastSeq: turnState.lastSeq, startedAt: turnState.startedAt || Date.now() };
    persistTurn(true);
  }
}

function applyMirrorEvent(sessionId, turnId, event, data) {
  if (sessionId !== activeId.value) return;
  if (!streaming.value) beginMirror(sessionId);
  if (turnId && !turnState.turnId) {
    turnState = { turnId, lastSeq: 0, startedAt: Date.now() };
  }
  handleEvent(event, data);
}

async function finishMirror(sessionId, turnId, flags) {
  if (sessionId !== activeId.value) return;
  if (flags && flags.handoff) {
    mirroring = false;
    await takeoverMirror(sessionId, turnId);
    return;
  }
  if (mirrorFinishing || !streaming.value) return;
  mirrorFinishing = true;
  try {
    const state = streaming.value;
    state.finished = !!flags.finished;
    state.cancelled = !!flags.cancelled;
    state.error = flags.error || '';
    state.resumable = !!flags.resumable;
    mirroring = false;
    await finalizeStream();
  } finally {
    mirrorFinishing = false;
  }
}

/** 流主人关闭页面后由镜像页接手，避免轮次无人读取。 */
async function takeoverMirror(sessionId, turnId) {
  if (sessionId !== activeId.value || !streaming.value) return;
  tabSync?.claim(sessionId, turnId);
  busy.value = true;
  controller = new AbortController();
  try {
    await resumeLoop(sessionId, turnState.lastSeq);
  } finally {
    controller = null;
    await finalizeStream();
  }
}

function streamHandler(sessionId) {
  return (event, data) => {
    handleEvent(event, data);
    tabSync?.event(sessionId, turnState.turnId, event, data);
  };
}

function streamFailed(message, resumable = false) {
  const state = streaming.value;
  if (!state || state.finished || state.cancelled) return;
  state.recovering = false;
  state.error = message || '连接中断，请重试';
  state.resumable = resumable;
  toast(state.error, 'error');
}

function terminalStatus(status) {
  return ['FAILED', 'CANCELED', 'EXPIRED'].includes(status);
}

function streamInterrupted() {
  const state = streaming.value;
  return unmounted || !state || state.finished || state.cancelled;
}

/** 指数退避续传：每轮先查终态，避免对已完成的轮次重复拉流。 */
async function resumeLoop(sessionId, fromSeq) {
  const { turnId } = turnState;
  if (streamInterrupted()) return;
  if (!turnId) {
    streamFailed('连接中断，请重试');
    return;
  }
  streaming.value.recovering = true;
  streaming.value.error = '';
  streaming.value.resumable = false;
  toast('连接中断，正在尝试续流…', 'warning');
  let cursor = Math.max(0, Number(fromSeq) || 0);
  for (let attempt = 0; attempt < RESUME_BACKOFF_MS.length; attempt += 1) {
    await delay(RESUME_BACKOFF_MS[attempt]);
    if (streamInterrupted()) return;
    try {
      const turn = await api(`/api/chat/sessions/${sessionId}/turns/${turnId}`);
      if (turn && turn.status === 'SUCCEEDED') {
        clearTurnMarker(sessionId, turnId);
        // 已完成轮次直接以落库消息呈现，清掉未吐完的队列避免重复显示。
        stopTyping();
        if (streaming.value) {
          streaming.value.pendingText = '';
          streaming.value.finished = true;
          streaming.value.recovering = false;
        }
        await loadMessages(true);
        return;
      }
      if (turn && terminalStatus(turn.status)) {
        clearTurnMarker(sessionId, turnId);
        streamFailed(turn.error_message || '本轮回答已中断');
        return;
      }
      if (turn && Number.isFinite(turn.last_seq)) {
        cursor = Math.max(cursor, turn.last_seq);
      }
      controller = new AbortController();
      await resumeTurn(sessionId, turnId, cursor, streamHandler(sessionId), controller.signal);
      if (streamInterrupted()) return;
      if (streaming.value.error) return;
      // 服务端正常收流但本轮未结束（例如刚好在 done 前断开）：继续下一轮续传。
      cursor = Math.max(cursor, turnState.lastSeq);
      streaming.value.recovering = true;
    } catch (error) {
      if (error.name === 'AbortError' || streamInterrupted()) return;
      cursor = Math.max(cursor, turnState.lastSeq);
      if (streaming.value) streaming.value.recovering = true;
    }
  }
  streamFailed('网络不稳定，续传失败，请重试', true);
}

/** 页面刷新后找回进行中的轮次：优先问服务端，退化为本地游标。 */
async function findActiveTurn(sessionId) {
  try {
    const turn = await api(`/api/chat/sessions/${sessionId}/turns/active`);
    if (turn && turn.turn_id) {
      return {
        turnId: turn.turn_id,
        lastSeq: Math.max(0, Number(turn.last_seq) || 0),
        startedAt: turn.started_at ? new Date(turn.started_at).getTime() : 0,
      };
    }
    // 服务端明确没有运行中的轮次时，本地标记已经过期，顺手清掉。
    clearTurnMarker(sessionId);
    return null;
  } catch (error) {
    // 服务端不可达时仍可凭本地游标尝试，事件日志在服务端可重放
  }
  const stored = loadActiveTurn(sessionId);
  if (!stored) return null;
  try {
    const turn = await api(`/api/chat/sessions/${sessionId}/turns/${stored.turnId}`);
    if (turn && turn.status === 'RUNNING') {
      return { ...stored, lastSeq: Math.max(stored.lastSeq, Number(turn.last_seq) || 0) };
    }
    if (turn && turn.status === 'SUCCEEDED') return { ...stored, done: true };
    clearTurnMarker(sessionId, stored.turnId);
    return null;
  } catch (error) {
    return stored;
  }
}

/** 冷启动恢复：重建回答气泡并从 seq=0 重放，或沿用当前气泡接着上次游标续传。 */
async function resumeActiveTurn(sessionId, { rebuild = false } = {}) {
  if (!sessionId || resuming) return;
  resuming = true;
  let mirrored = false;
  try {
    const found = await findActiveTurn(sessionId);
    if (!found) {
      clearTurnMarker(sessionId);
      return;
    }
    if (found.done) {
      clearTurnMarker(sessionId, found.turnId);
      await loadMessages(true);
      return;
    }
    if (activeId.value !== sessionId || busy.value || unmounted) return;
    turnState = {
      turnId: found.turnId,
      lastSeq: rebuild ? 0 : found.lastSeq,
      startedAt: found.startedAt || Date.now(),
    };
    if (rebuild || !streaming.value) {
      stopTyping();
      streaming.value = newStreamingState();
    }
    streaming.value.recovering = true;
    streaming.value.resumable = false;
    streaming.value.error = '';
    busy.value = true;
    if (tabSync && !mirroring) {
      mirrored = await tabSync.follow(sessionId);
      if (mirrored) {
        beginMirror(sessionId);
        return;
      }
    }
    tabSync?.claim(sessionId, turnState.turnId);
    controller = new AbortController();
    try {
      await resumeTurn(
        sessionId,
        turnState.turnId,
        turnState.lastSeq,
        streamHandler(sessionId),
        controller.signal
      );
      const state = streaming.value;
      if (state && !state.finished && !state.cancelled && !state.error) {
        await resumeLoop(sessionId, turnState.lastSeq);
      }
    } catch (error) {
      if (error.name !== 'AbortError') await resumeLoop(sessionId, turnState.lastSeq);
    } finally {
      controller = null;
      if (!mirrored && activeId.value === sessionId) await finalizeStream();
    }
  } finally {
    resuming = false;
  }
}

/** 收尾：成功的轮次以数据库消息呈现，可续传的中断保留气泡与重试入口。 */
async function finalizeStream() {
  const state = streaming.value;
  if (!state) return;
  if (tabSync) {
    tabSync.release(activeId.value, turnState.turnId, {
      finished: state.finished,
      cancelled: state.cancelled,
      error: state.error,
      resumable: state.resumable,
    });
  }
  state.recovering = false;
  // 先让打印机把队列吐完，再切换到落库消息，避免最后一段被瞬间截断。
  await waitTyping();
  busy.value = false;
  if (state.resumable && !state.finished && !state.cancelled) {
    detail.value = null;
    scrollBottom(true);
    return;
  }
  if (state.finished || state.cancelled || state.error) await loadMessages(true);
  streaming.value = null;
  detail.value = null;
  scrollBottom(true);
}

async function retryResume() {
  const state = streaming.value;
  if (!state || state.finished || state.cancelled || !turnState.turnId || busy.value) return;
  state.error = '';
  state.resumable = false;
  busy.value = true;
  let mirrored = false;
  try {
    if (tabSync && !mirroring) {
      mirrored = await tabSync.follow(activeId.value);
      if (mirrored) {
        beginMirror(activeId.value);
        return;
      }
    }
    tabSync?.claim(activeId.value, turnState.turnId);
    await resumeLoop(activeId.value, turnState.lastSeq);
  } finally {
    if (!mirrored) await finalizeStream();
  }
}

/** 网络恢复或窗口重新可见时，自动接上未完成的轮次。 */
function retryOnNetwork() {
  const state = streaming.value;
  if (!state || state.finished || state.cancelled || !state.resumable || busy.value) return;
  retryResume();
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') retryOnNetwork();
}

async function send() {
  const text = draft.value.trim();
  if (!text || busy.value) return;
  const pending = streaming.value;
  if (pending && pending.resumable && !pending.finished && !pending.cancelled) {
    toast('上一轮回答仍未完成，请先重试续传或放弃本轮', 'warning');
    return;
  }
  if (!activeId.value) {
    await createSession();
    if (!activeId.value) return;
  }
  const sessionId = activeId.value;
  if (tabSync && !mirroring) {
    const mirrored = await tabSync.follow(sessionId);
    if (mirrored) {
      beginMirror(sessionId);
      toast('该会话正在其他标签页中生成回答，已同步显示', 'warning');
      return;
    }
  }
  tabSync?.claim(sessionId, null);
  busy.value = true;
  draft.value = '';
  turnState = { turnId: null, lastSeq: 0, startedAt: 0 };
  stopTyping();
  streaming.value = newStreamingState();
  gapNotice.value = '';
  messages.value.push({
    id: `local-${Date.now()}`,
    role: 'USER',
    content: text,
    tools: [],
    created_at: new Date().toISOString(),
  });
  scrollBottom(true);

  controller = new AbortController();
  try {
    await streamChat(sessionId, text, streamHandler(sessionId), controller.signal);
    const state = streaming.value;
    // 服务端正常收流但轮次仍未结束（如网关超时）：继续续传而不是当作完成
    if (state && !state.finished && !state.cancelled && !state.error && turnState.turnId) {
      await resumeLoop(sessionId, turnState.lastSeq);
    }
  } catch (error) {
    const state = streaming.value;
    if (error.name !== 'AbortError' && state && !state.cancelled && !unmounted) {
      if (turnState.turnId) {
        await resumeLoop(sessionId, turnState.lastSeq);
      } else {
        streamFailed('网络异常，请求未送达，请重试');
      }
    }
  } finally {
    controller = null;
    await finalizeStream();
  }
}

async function stop() {
  if (!busy.value) return;
  const turnId = turnState.turnId;
  if (controller) {
    controller.abort();
    controller = null;
  }
  const state = streaming.value;
  if (state && state.finished) {
    // 回答已经完成，只是打印机还在吐字：立即收尾，不再向服务端发取消。
    stopTyping();
    state.pendingText = '';
    return;
  }
  if (state) {
    state.cancelled = true;
    state.resumable = false;
    state.recovering = false;
    state.pendingText = '';
  }
  stopTyping();
  clearTurnMarker(activeId.value, turnId);
  if (!turnId) {
    busy.value = false;
    return;
  }
  try {
    await api(`/api/chat/sessions/${activeId.value}/turns/${turnId}/cancel`, {
      method: 'POST',
    });
    toast('已请求取消本轮回答', 'warning');
  } catch (error) {
    toast(error.message, 'error');
  }
}

/** 放弃无法续传的轮次：清掉本地标记，并尽力通知服务端取消。 */
async function discardStream() {
  const turnId = turnState.turnId;
  clearTurnMarker(activeId.value, turnId);
  stopTyping();
  streaming.value = null;
  if (!turnId) return;
  try {
    await api(`/api/chat/sessions/${activeId.value}/turns/${turnId}/cancel`, {
      method: 'POST',
    });
    toast('已取消本轮回答', 'warning');
  } catch (error) {
    toast('已放弃本轮回答，服务端会自动回收', 'warning');
  }
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    send();
  }
}

function useSuggestion(text) {
  draft.value = text;
  send();
}

async function previewRoute() {
  const question = draft.value.trim() || lastUserQuestion();
  if (!question) {
    toast('请先输入问题或发送一条消息', 'warning');
    return;
  }
  routeBusy.value = true;
  try {
    routePanel.value = await api('/api/agents/route-preview', {
      method: 'POST',
      body: { message: question, session_id: activeId.value },
    });
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    routeBusy.value = false;
  }
}

function lastUserQuestion() {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index].role === 'USER') return messages.value[index].content;
  }
  return '';
}

function hasSkillFiles(message) {
  return Boolean(message && Array.isArray(message.tools) && message.tools.includes('skill_read'));
}

/** skill_read 的追溯结果即模型读到的原文，这里只提取展示所需字段。 */
function parseSkillFile(trace) {
  if (!trace || trace.result_state === 'ERROR') return null;
  let args = {};
  let result = null;
  try {
    args = JSON.parse(trace.args_json || '{}');
  } catch {
    args = {};
  }
  try {
    result = JSON.parse(trace.result_json || 'null');
  } catch {
    result = null;
  }
  const path = (result && result.path) || args.path;
  if (!path) return null;
  return {
    key: `${trace.id}-${path}`,
    skillId: (result && result.skill_id) || args.skill_id || '',
    path,
    size: result && Number.isFinite(result.size) ? result.size : null,
    truncated: Boolean(result && result.truncated),
    content: result && typeof result.content === 'string' ? result.content : '',
  };
}

async function openDetail(message, kind) {
  if (!message.id || String(message.id).startsWith('local-')) return;
  if (detail.value && detail.value.messageId === message.id && detail.value.kind === kind) {
    detail.value = null;
    return;
  }
  if (kind === 'citations') {
    detail.value = {
      messageId: message.id,
      kind,
      loading: false,
      data: message.citations || [],
    };
    return;
  }
  if (kind === 'files') {
    detail.value = { messageId: message.id, kind, loading: true, data: null };
    try {
      const traces = await api(
        `/api/chat/sessions/${activeId.value}/messages/${message.id}/traces`
      );
      const files = new Map();
      for (const trace of traces || []) {
        const file = parseSkillFile(trace);
        if (file && !files.has(`${file.skillId}|${file.path}`)) {
          files.set(`${file.skillId}|${file.path}`, file);
        }
      }
      if (
        detail.value &&
        detail.value.messageId === message.id &&
        detail.value.kind === 'files'
      ) {
        detail.value = { messageId: message.id, kind, loading: false, data: [...files.values()] };
      }
    } catch (error) {
      detail.value = null;
      toast(error.message, 'error');
    }
    return;
  }
  detail.value = { messageId: message.id, kind, loading: true, data: null };
  try {
    const data = await api(
      `/api/chat/sessions/${activeId.value}/messages/${message.id}/${kind}`
    );
    if (detail.value && detail.value.messageId === message.id) {
      detail.value = { messageId: message.id, kind, loading: false, data };
    }
  } catch (error) {
    detail.value = null;
    toast(error.message, 'error');
  }
}

async function submitFeedback(message, rating) {
  try {
    await api(`/api/chat/sessions/${activeId.value}/messages/${message.id}/feedback`, {
      method: 'POST',
      body: { rating },
    });
    feedbackMap[message.id] = rating;
    toast('感谢反馈', 'success');
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function decide(proposal, decision) {
  if (decision === 'CONFIRM' && !window.confirm('确认执行该写操作？')) return;
  try {
    const idempotencyKey =
      proposal.idempotency_key ||
      (window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID() : `${proposal.proposal_id}-${Date.now()}`);
    const updated = await api(`/api/chat/actions/${proposal.proposal_id}/confirm`, {
      method: 'POST',
      body: { decision, idempotency_key: proposal.idempotency_key || idempotencyKey },
    });
    toast(decision === 'CONFIRM' ? '操作已执行' : '提案已取消', 'success');
    if (updated) Object.assign(proposal, updated);
    await loadMessages(true);
  } catch (error) {
    toast(error.message, 'error');
  }
}

function proposalRisk(proposal) {
  if (proposal.risk_level === 'IRREVERSIBLE') return { text: '不可逆', cls: 'red' };
  if (proposal.risk_level === 'AMOUNT') return { text: '涉及金额', cls: 'orange' };
  return { text: '普通', cls: 'green' };
}

function statusText(status) {
  return (
    {
      PENDING: '待确认',
      CONFIRMED: '已确认',
      EXECUTED: '已执行',
      FAILED: '失败',
      CANCELED: '已取消',
      EXPIRED: '已过期',
    }[status] || status
  );
}

watch(
  () => sessions.value.length,
  () => {
    if (!activeId.value && sessions.value.length) selectSession(sessions.value[0].id);
  }
);

onMounted(async () => {
  tabSync = createTabSync({
    snapshot: streamSnapshot,
    onEvent: applyMirrorEvent,
    onState: applyMirrorState,
    onEnd: finishMirror,
  });
  window.addEventListener('online', retryOnNetwork);
  document.addEventListener('visibilitychange', onVisibilityChange);
  await loadSessions();
  if (sessions.value.length) {
    await selectSession(sessions.value[0].id);
  } else {
    await createSession();
  }
});

onBeforeUnmount(() => {
  unmounted = true;
  window.removeEventListener('online', retryOnNetwork);
  document.removeEventListener('visibilitychange', onVisibilityChange);
  if (persistTimer) window.clearTimeout(persistTimer);
  stopTyping();
  if (controller) controller.abort();
  if (tabSync) {
    tabSync.release(activeId.value, turnState.turnId, { handoff: true });
    tabSync.close();
    tabSync = null;
  }
});
</script>

<template>
  <div class="chat-view">
    <aside class="session-panel">
      <div class="session-head">
        <div class="row">
          <h3>对话</h3>
          <button class="primary sm" :disabled="busy" @click="createSession">新建</button>
        </div>
        <small>{{ sessions.length }} 个会话 · 仅自己可见</small>
      </div>
      <div class="session-list">
        <div
          v-for="session in sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: session.id === activeId }"
          @click="selectSession(session.id)"
        >
          <input
            v-if="editingId === session.id"
            v-model="editingTitle"
            class="session-rename"
            @click.stop
            @keydown.enter.prevent="commitRename(session)"
            @keydown.esc="editingId = null"
            @blur="commitRename(session)"
          />
          <template v-else>
            <div class="title">{{ session.title || '未命名会话' }}</div>
            <div class="preview">{{ session.last_message || '还没有消息' }}</div>
            <div class="meta">
              <span>{{ session.message_count || 0 }} 条</span>
              <span>{{ formatTime(session.updated_at) }}</span>
            </div>
          </template>
          <div v-if="editingId !== session.id" class="session-actions" @click.stop>
            <button title="重命名" @click="renameSession(session)">✎</button>
            <button title="删除" @click="deleteSession(session)">✕</button>
          </div>
        </div>
        <div v-if="!sessions.length" class="empty">还没有会话，点击「新建」开始。</div>
      </div>
    </aside>

    <section class="chat-main">
      <header class="chat-head">
        <div class="title">
          <strong>{{ activeSession ? activeSession.title : '新会话' }}</strong>
          <span v-if="streaming && streaming.phase" class="badge blue">{{ streaming.phase }}</span>
        </div>
        <div class="chat-actions">
          <button
            class="ghost sm"
            :disabled="busy || !messages.length"
            title="导出当前会话为 Markdown"
            @click="exportMarkdown"
          >导出</button>
          <button class="ghost sm" :disabled="routeBusy" @click="previewRoute">
            {{ routeBusy ? '路由中…' : '路由预览' }}
          </button>
          <button class="ghost sm" :disabled="busy || !activeId" @click="resetSession">重置</button>
          <button v-if="busy" class="danger sm" @click="stop">停止</button>
        </div>
      </header>

      <div ref="messageBox" class="messages" @scroll.passive="onMessagesScroll">
        <div v-if="gapNotice" class="stream-gap">
          <span>{{ gapNotice }}</span>
          <button class="ghost sm" @click="gapNotice = ''">知道了</button>
        </div>
        <div v-if="!messages.length && !streaming" class="empty" style="margin: auto">
          <p>问我任何薪酬核算、政策或员工数据问题。</p>
          <div class="toolbar" style="justify-content: center; margin-top: 12px">
            <button
              v-for="item in suggestions"
              :key="item"
              class="chip"
              @click="useSuggestion(item)"
            >
              {{ item }}
            </button>
          </div>
        </div>

        <template v-for="(message, index) in messages" :key="message.id">
          <div v-if="showTime(index)" class="msg-meta" style="justify-content: center">
            {{ formatTime(message.created_at) }}
          </div>
          <div
            class="msg"
            :class="message.role === 'USER' ? 'user' : 'assistant'"
            :data-question-id="message.role === 'USER' ? message.id : null"
          >
            <div class="bubble-avatar">
              {{ message.role === 'USER' ? (auth.user?.display_name || '我').slice(0, 1) : '协' }}
            </div>
            <div class="msg-body">
              <div class="msg-meta">
                <span>{{ roleLabel(message.role) }}</span>
                <span>{{ formatTime(message.created_at) }}</span>
              </div>
              <div class="bubble" :class="{ error: message.role === 'ERROR' }">
                <template
                  v-for="(part, partIndex) in contentParts(message.content, message.citations)"
                  :key="partIndex"
                >
                  <span v-if="part.text">{{ part.text }}</span>
                  <button
                    v-else
                    type="button"
                    class="cite-ref"
                    :title="`查看来源 ${part.index}`"
                    @click="focusCitation(message.citations, message.id, part.index)"
                  >[{{ part.index }}]</button>
                </template>
              </div>

              <button
                v-if="message.role === 'ASSISTANT' && message.citations?.length"
                type="button"
                class="citation-summary"
                :class="{ low: isLowConfidence(message.citations) }"
                @click="openSourceList(message.citations, message.id, message.content)"
              >
                <span class="citation-summary-label">参考来源（{{ message.citations.length }}）</span>
                <span class="citation-summary-libs">
                  {{ citationGroups(message.citations).map((group) => group.series_name).join(' · ') }}
                </span>
                <span v-if="isLowConfidence(message.citations)" class="citation-summary-warn">
                  相关性低
                </span>
                <span class="citation-summary-chev">›</span>
              </button>

              <div v-if="message.role === 'ASSISTANT'" class="msg-tools">
                <button
                  v-if="message.routing"
                  class="chip route-chip plain"
                  :class="pathClass(message.routing)"
                >
                  <span class="dot"></span>
                  {{ pathLabel(message.routing) }}
                </button>
                <button v-if="message.model" class="chip plain">{{ message.model }}</button>
                <button class="chip" @click="openDetail(message, 'traces')">
                  计算依据{{ message.tools?.length ? `（${message.tools.length}）` : '' }}
                </button>
                <button
                  v-if="message.citations?.length"
                  class="chip"
                  @click="openDetail(message, 'citations')"
                >
                  引用（{{ message.citations.length }}）
                </button>
                <button
                  v-if="hasSkillFiles(message)"
                  class="chip"
                  @click="openDetail(message, 'files')"
                >
                  引用文件
                </button>
                <button class="chip" @click="openDetail(message, 'team')">团队运行</button>
                <button
                  v-if="message.routing"
                  class="chip"
                  @click="openDetail(message, 'routing')"
                >
                  路由详情
                </button>
                <span class="feedback">
                  <button
                    :class="{ on: feedbackMap[message.id] === 'UP' }"
                    title="有帮助"
                    aria-label="有帮助"
                    @click="submitFeedback(message, 'UP')"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M7 21V10.6l4.6-8.1a1.8 1.8 0 0 1 3.1 1.8L13.5 10H19a2 2 0 0 1 2 2.3l-1.1 6A2 2 0 0 1 18 20H7z" />
                      <path d="M7 10H4a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3" />
                    </svg>
                  </button>
                  <button
                    class="down"
                    :class="{ on: feedbackMap[message.id] === 'DOWN' }"
                    title="没帮助"
                    aria-label="没帮助"
                    @click="submitFeedback(message, 'DOWN')"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M7 21V10.6l4.6-8.1a1.8 1.8 0 0 1 3.1 1.8L13.5 10H19a2 2 0 0 1 2 2.3l-1.1 6A2 2 0 0 1 18 20H7z" />
                      <path d="M7 10H4a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3" />
                    </svg>
                  </button>
                </span>
              </div>

              <div
                v-for="proposal in message.proposals || []"
                :key="proposal.proposal_id"
                class="proposal"
                :class="`risk-${(proposal.risk_level || '').toLowerCase()}`"
              >
                <div class="proposal-head">
                  <strong>{{ proposal.summary || proposal.tool_name }}</strong>
                  <span class="badge" :class="proposalRisk(proposal).cls">
                    {{ proposalRisk(proposal).text }}
                  </span>
                  <span class="badge">{{ statusText(proposal.status) }}</span>
                </div>
                <div class="proposal-summary">
                  工具 <span class="mono">{{ proposal.tool_name }}</span>
                </div>
                <pre v-if="proposal.params_json" class="proposal-params">{{ pretty(proposal.params_json) }}</pre>
                <div v-if="proposal.error_message" class="error-text">{{ proposal.error_message }}</div>
                <div v-if="proposal.status === 'PENDING'" class="proposal-actions">
                  <button class="primary sm" @click="decide(proposal, 'CONFIRM')">确认执行</button>
                  <button class="ghost sm" @click="decide(proposal, 'CANCEL')">取消</button>
                </div>
              </div>

              <div v-if="detail && detail.messageId === message.id" class="panel">
                <div class="panel-head">
                  <span>
                    {{
                      detail.kind === 'traces'
                        ? '计算依据'
                        : detail.kind === 'team'
                          ? '团队运行'
                          : detail.kind === 'citations'
                            ? '知识库引用'
                            : detail.kind === 'files'
                              ? '引用文件'
                              : '路由详情'
                    }}
                  </span>
                  <button class="subtle sm" @click="detail = null">收起</button>
                </div>
                <div class="panel-body">
                  <div v-if="detail.loading" class="muted">加载中…</div>

                  <template v-else-if="detail.kind === 'traces'">
                    <div v-if="!detail.data || !detail.data.length" class="muted">
                      本轮没有工具调用记录。
                    </div>
                    <div v-for="trace in detail.data || []" :key="trace.id" class="trace-item">
                      <div class="trace-head">
                        <strong>{{ trace.tool_name }}</strong>
                        <span class="badge" :class="trace.result_state === 'ERROR' ? 'red' : 'green'">
                          {{ trace.result_state || 'OK' }} · {{ trace.duration_ms || 0 }}ms
                        </span>
                      </div>
                      <div class="panel-row">
                        <span class="k">智能体</span>
                        <span class="v">{{ trace.agent || '—' }}</span>
                      </div>
                      <div v-if="trace.employees?.length" class="panel-row">
                        <span class="k">员工</span>
                        <span class="v">{{ trace.employees.join('、') }}</span>
                      </div>
                      <div v-if="trace.amounts && Object.keys(trace.amounts).length" class="panel-row">
                        <span class="k">金额</span>
                        <span class="v mono">{{ pretty(trace.amounts) }}</span>
                      </div>
                      <details>
                        <summary class="muted" style="cursor: pointer; font-size: 12px">参数与结果</summary>
                        <pre v-if="trace.args_json" class="trace-pre">{{ pretty(trace.args_json) }}</pre>
                        <pre v-if="trace.result_json" class="trace-pre">{{ pretty(trace.result_json) }}</pre>
                      </details>
                    </div>
                  </template>

                  <template v-else-if="detail.kind === 'citations'">
                    <div v-if="!detail.data || !detail.data.length" class="muted">
                      本轮没有知识库引用。
                    </div>
                    <div
                      v-for="cite in detail.data || []"
                      :key="`${cite.series_id}-${cite.chunk_id}`"
                      class="trace-item"
                    >
                      <div class="trace-head">
                        <span class="badge">{{ cite.index }}</span>
                        <strong>{{ cite.series_name || '未知知识库' }}</strong>
                        <span class="muted">{{ cite.document_name || '未知文档' }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">位置</span>
                        <span class="v">
                          {{ cite.title_path || '—' }}
                          <template v-if="cite.page_no"> · 第 {{ cite.page_no }} 页</template>
                          <template v-if="cite.chunk_seq"> · 分块 #{{ cite.chunk_seq }}</template>
                        </span>
                      </div>
                      <div v-if="cite.query" class="panel-row">
                        <span class="k">查询词</span>
                        <span class="v">{{ cite.query }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">召回</span>
                        <span class="v mono">
                          {{ (cite.routes || []).join(' / ') || 'keyword' }} ·
                          {{ Number(cite.score || 0).toFixed(3) }}
                          <template v-if="cite.retrieved_at"> · {{ fullTime(cite.retrieved_at) }}</template>
                        </span>
                      </div>
                      <div v-if="cite.low_confidence" class="citation-warn">
                        未找到与问题直接相关的字面依据，请谨慎参考。
                      </div>
                      <div class="muted" style="white-space: pre-wrap">{{ cite.snippet }}</div>
                      <details v-if="cite.content_snapshot && cite.content_snapshot !== cite.snippet">
                        <summary class="muted" style="cursor: pointer; font-size: 12px">完整快照</summary>
                        <div class="snapshot-text">{{ cite.content_snapshot }}</div>
                      </details>
                      <div class="trace-actions">
                        <button class="ghost sm" @click="openCitation(cite, detail.messageId)">来源详情</button>
                        <button class="ghost sm" @click="copyCitation(cite)">复制引用</button>
                      </div>
                    </div>
                  </template>

                  <template v-else-if="detail.kind === 'team'">
                    <div v-if="!detail.data || !detail.data.runs || !detail.data.runs.length" class="muted">
                      本轮没有团队运行记录。
                    </div>
                    <div v-for="run in detail.data?.runs || []" :key="run.id" class="trace-item">
                      <div class="trace-head">
                        <strong>运行 #{{ run.id }} · {{ run.mode }}</strong>
                        <span
                          class="badge"
                          :class="run.status === 'SUCCESS' ? 'green' : run.status === 'FAILED' ? 'red' : 'blue'"
                        >
                          {{ run.status }}
                        </span>
                      </div>
                      <div class="panel-row">
                        <span class="k">任务</span>
                        <span class="v">{{ run.task_type || '—' }} · {{ run.template_id || '—' }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">耗时</span>
                        <span class="v">{{ run.total_ms || 0 }}ms</span>
                      </div>
                      <div v-if="run.fallback_reason" class="panel-row">
                        <span class="k">回退原因</span>
                        <span class="v">{{ run.fallback_reason }}</span>
                      </div>
                    </div>
                    <div v-if="detail.data?.nodes?.length" class="node-list">
                      <div v-for="node in detail.data.nodes" :key="node.id" class="node-item">
                        <span class="name">{{ node.agent_id || node.node_id }}</span>
                        <span class="badge" :class="node.status === 'SUCCESS' ? 'green' : 'red'">
                          {{ node.status }}
                        </span>
                        <span class="muted">{{ node.duration_ms || 0 }}ms</span>
                      </div>
                    </div>
                  </template>

                  <template v-else-if="detail.kind === 'files'">
                    <div v-if="!detail.data || !detail.data.length" class="muted">
                      本轮没有读取技能文件。
                    </div>
                    <div v-for="file in detail.data || []" :key="file.key" class="trace-item">
                      <div class="trace-head">
                        <strong class="mono">{{ file.path }}</strong>
                        <span class="muted">
                          {{ formatBytes(file.size) }}{{ file.truncated ? ' · 已截断' : '' }}
                        </span>
                      </div>
                      <div class="panel-row">
                        <span class="k">技能</span>
                        <span class="v">{{ file.skillId || '—' }}</span>
                      </div>
                      <pre v-if="file.content" class="trace-pre">{{ file.content }}</pre>
                      <div v-else class="muted">未取到文件内容。</div>
                    </div>
                  </template>

                  <template v-else>
                    <template v-if="detail.data">
                      <div class="panel-row">
                        <span class="k">链路</span>
                        <span class="v">{{ pathLabel(detail.data) }}（{{ detail.data.path }}）</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">角色</span>
                        <span class="v">{{ detail.data.persona || '—' }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">原因</span>
                        <span class="v">{{ detail.data.reason || '—' }}</span>
                      </div>
                      <div v-if="detail.data.kb" class="panel-row">
                        <span class="k">知识库判定</span>
                        <span class="v">
                          {{ detail.data.kb.need ? '是' : '否' }}
                          （来源：{{ kbSourceLabel(detail.data.kb.source) }} · {{ kbKindLabel(detail.data.kb.kind) }}）
                          <template v-if="detail.data.kb.need">
                            · 范围 {{ detail.data.kb.scope === 'session' ? '当前会话' : '全部可读库' }}
                          </template>
                          <template v-if="detail.data.kb.reroute"> · 已改道检索智能体</template>
                        </span>
                      </div>
                      <div v-if="detail.data.from_path" class="panel-row">
                        <span class="k">回退</span>
                        <span class="v">
                          {{ detail.data.from_path }} → {{ detail.data.path }}
                          <template v-if="detail.data.fallback_reason">
                            （{{ detail.data.fallback_reason }}）
                          </template>
                        </span>
                      </div>
                      <div class="panel-row">
                        <span class="k">模型</span>
                        <span class="v">{{ detail.data.model || '—' }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">智能体</span>
                        <span class="v">{{ (detail.data.agent_ids || []).join('、') || '—' }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">契约</span>
                        <span class="v mono">{{ detail.data.definition_signature || '—' }}</span>
                      </div>
                      <div class="panel-row">
                        <span class="k">耗时</span>
                        <span class="v">{{ detail.data.elapsed_ms || 0 }}ms</span>
                      </div>
                      <div v-if="detail.data.stages?.length" class="panel-row">
                        <span class="k">阶段</span>
                        <span class="v">{{ detail.data.stages.join(' → ') }}</span>
                      </div>
                    </template>
                    <div v-else class="muted">该消息没有路由快照。</div>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </template>

        <div v-if="streaming" class="msg assistant">
          <div class="bubble-avatar">协</div>
          <div class="msg-body">
            <div class="msg-meta">
              <span>协同智能体</span>
              <span v-if="streaming.recovering">正在恢复连接…</span>
              <span v-else-if="streaming.gap">部分过程已跳过</span>
              <span v-else-if="streaming.text">正在输出</span>
              <span v-else-if="busy">正在思考</span>
              <span v-else-if="streaming.resumable">连接已中断</span>
            </div>
            <div class="bubble" :class="{ error: streaming.error }">
              <template v-if="streaming.text">
                <template
                  v-for="(part, partIndex) in contentParts(streaming.text, streaming.citations)"
                  :key="partIndex"
                >
                  <span v-if="part.text">{{ part.text }}</span>
                  <button
                    v-else
                    type="button"
                    class="cite-ref"
                    :title="`查看来源 ${part.index}`"
                    @click="focusCitation(streaming.citations, null, part.index)"
                  >[{{ part.index }}]</button>
                </template>
              </template>
              <span v-else-if="!streaming.error && !streaming.gap" class="typing"><i></i><i></i><i></i></span>
              <template v-if="streaming.error">{{ streaming.error }}</template>
              <template v-else-if="streaming.cancelled">（本轮回答已取消）</template>
              <template v-else-if="streaming.gap">{{ streaming.gapMessage }}</template>
            </div>

            <button
              v-if="streaming.citations.length"
              type="button"
              class="citation-summary"
              :class="{ low: isLowConfidence(streaming.citations) }"
              @click="openSourceList(streaming.citations, null, streaming.text)"
            >
              <span class="citation-summary-label">参考来源（{{ streaming.citations.length }}）</span>
              <span class="citation-summary-libs">
                {{ citationGroups(streaming.citations).map((group) => group.series_name).join(' · ') }}
              </span>
              <span v-if="isLowConfidence(streaming.citations)" class="citation-summary-warn">
                相关性低
              </span>
              <span class="citation-summary-chev">›</span>
            </button>

            <div v-if="streaming.resumable && !busy" class="msg-tools">
              <button class="primary sm" @click="retryResume">重试续传</button>
              <button class="ghost sm" @click="discardStream">放弃本轮</button>
            </div>

            <div v-if="streaming.events.length" class="event-feed">
              <div v-for="(event, index) in streaming.events" :key="index" class="ev" :class="event.cls">
                <span class="tag">{{ event.tag }}</span>
                <span>{{ event.text }}</span>
              </div>
            </div>

            <div v-if="streaming.tools.length" class="event-feed">
              <div v-for="tool in streaming.tools" :key="tool.id" class="ev tool">
                <span class="tag">工具</span>
                <span>
                  {{ tool.name }}
                  <span class="muted">{{ tool.state }}{{ tool.duration ? ` · ${tool.duration}ms` : '' }}</span>
                </span>
              </div>
            </div>

            <div v-if="streamingFileCount" class="msg-tools">
              <span class="chip plain">引用文件 {{ streamingFileCount }} 个</span>
            </div>

            <details v-if="streaming.thinking" class="panel">
              <summary class="panel-head" style="cursor: pointer">思考过程</summary>
              <div class="panel-body" style="white-space: pre-wrap">{{ streaming.thinking }}</div>
            </details>

            <details v-if="streaming.agents.some((agent) => agent.text)" class="panel">
              <summary class="panel-head" style="cursor: pointer">各智能体输出</summary>
              <div class="panel-body">
                <div v-for="agent in streaming.agents.filter((item) => item.text)" :key="agent.id">
                  <strong>{{ agent.label }}</strong>
                  <div style="white-space: pre-wrap; margin-top: 4px">{{ agent.text }}</div>
                </div>
              </div>
            </details>

            <div
              v-for="proposal in streaming.proposals"
              :key="proposal.proposal_id"
              class="proposal"
              :class="`risk-${(proposal.risk_level || '').toLowerCase()}`"
            >
              <div class="proposal-head">
                <strong>{{ proposal.summary || proposal.tool_name }}</strong>
                <span class="badge" :class="proposalRisk(proposal).cls">
                  {{ proposalRisk(proposal).text }}
                </span>
                <span class="badge">{{ statusText(proposal.status) }}</span>
              </div>
              <div class="proposal-summary">
                工具 <span class="mono">{{ proposal.tool_name }}</span>
              </div>
              <pre v-if="proposal.params_json" class="proposal-params">{{ pretty(proposal.params_json) }}</pre>
              <div class="proposal-actions">
                <button class="primary sm" @click="decide(proposal, 'CONFIRM')">确认执行</button>
                <button class="ghost sm" @click="decide(proposal, 'CANCEL')">取消</button>
              </div>
            </div>

            <div v-if="streaming.routing" class="msg-tools">
              <span class="chip plain" :class="pathClass(streaming.routing)">
                <span class="dot"></span>{{ pathLabel(streaming.routing) }}
              </span>
              <span v-if="streaming.citations.length" class="chip plain">
                引用 {{ streaming.citations.length }} 条
              </span>
              <span v-if="streaming.cache" class="chip plain">缓存命中</span>
            </div>
          </div>
        </div>
      </div>

      <nav v-if="questionRail.length > 1" class="question-rail" aria-label="问题导航">
        <div v-for="item in questionRail" :key="item.id" class="rail-item">
          <button
            class="rail-dash"
            :class="{ active: activeQuestion === item.id }"
            :aria-label="`跳到第 ${item.no} 个问题`"
            @click="jumpToQuestion(item.id)"
          ></button>
          <span class="rail-tip"><b>Q{{ item.no }}</b>{{ excerpt(item.text) }}</span>
        </div>
      </nav>

      <footer class="composer">
        <div class="kb-bar">
          <button class="chip" :disabled="!activeId" @click="openKbPanel">
            知识库{{ kbMode === 'SELECTED' ? ` ${kbBound.length}/${kbMax}` : '：全部可读' }}
          </button>
          <span v-for="item in kbBound" :key="item.id" class="chip plain">
            {{ item.name }}<template v-if="!item.searchable">（未启用）</template>
          </span>
          <span v-if="kbMode !== 'SELECTED'" class="muted" style="font-size: 11.5px">
            默认检索你有权限的全部已启用知识库，回答中给出引用
          </span>
          <span v-else class="muted" style="font-size: 11.5px">
            已收窄检索范围
          </span>
        </div>
        <div class="composer-box">
          <textarea
            v-model="draft"
            rows="1"
            placeholder="输入问题，Enter 发送，Shift+Enter 换行"
            @keydown="onKeydown"
          ></textarea>
          <button v-if="busy" class="danger" @click="stop">停止</button>
          <button v-else class="primary" :disabled="!canSend" @click="send">发送</button>
        </div>
        <div class="composer-foot">
          <span class="left">
            <span>问题会按路由规则交给单个智能体或动态团队处理</span>
          </span>
          <span>{{ draft.length }} / 2000</span>
        </div>
      </footer>

      <div v-if="kbPanel" class="modal-mask" @click.self="kbPanel = false">
        <div class="modal" style="width: min(520px, 96vw)">
          <div class="modal-head">
            <h3>会话知识库范围</h3>
            <button class="subtle sm" @click="kbPanel = false">关闭</button>
          </div>
          <div class="modal-body">
            <div class="muted" style="font-size: 12px">
              不勾选时默认检索你有只读权限的全部已启用知识库；勾选后仅检索所选库（最多
              {{ kbMax }} 个）。范围从下一轮生效。
            </div>
            <div v-if="kbLoading" class="muted">加载中…</div>
            <template v-else>
              <div v-if="!kbOptions.length" class="muted">没有可绑定的知识库。</div>
              <div v-else class="kb-options">
                <label
                  v-for="item in kbOptions"
                  :key="item.id"
                  class="kb-option"
                  :class="{ disabled: !item.searchable }"
                >
                  <input
                    type="checkbox"
                    :checked="kbSelection.includes(item.id)"
                    :disabled="!item.searchable"
                    @change="toggleKbOption(item)"
                  />
                  <span class="name">{{ item.name }}</span>
                  <span v-if="!item.searchable" class="muted">未启用</span>
                  <span v-else class="muted">{{ item.docCount }} 篇</span>
                  <span v-if="item.role" class="badge">{{ kbRoleLabel(item.role) }}</span>
                </label>
              </div>
            </template>
          </div>
          <div class="modal-foot">
            <span class="muted" style="margin-right: auto; font-size: 12px">
              {{
                kbSelection.length
                  ? `已选 ${kbSelection.length}/${kbMax}`
                  : '未选择，使用全部可读'
              }}
            </span>
            <button
              v-if="kbSelection.length"
              class="ghost"
              @click="kbSelection = []"
            >
              恢复全部可读
            </button>
            <button class="ghost" @click="kbPanel = false">取消</button>
            <button class="primary" :disabled="kbSaving || kbLoading" @click="saveKbBinding">
              {{ kbSaving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="sourceListModal" class="modal-mask" @click.self="sourceListModal = null">
        <div class="modal citation-modal source-list-modal">
          <div class="modal-head">
            <h3>参考来源（{{ sourceListModal.citations.length }}）</h3>
            <button class="subtle sm" @click="sourceListModal = null">关闭</button>
          </div>
          <div class="modal-body">
            <div v-if="sourceListModal.low" class="citation-warn">
              未找到与问题直接相关的依据，请谨慎参考。
            </div>
            <div v-else-if="sourceListModal.hint" class="citation-hint">
              以下为本次回答检索到的依据
            </div>
            <div
              v-for="group in citationGroups(sourceListModal.citations)"
              :key="group.series_id"
              class="citation-group"
            >
              <div class="citation-group-head">
                <span class="citation-lib">{{ group.series_name }}</span>
                <span class="muted">{{ group.items.length }} 条</span>
              </div>
              <button
                v-for="cite in group.items"
                :key="`${cite.series_id}-${cite.chunk_id}`"
                type="button"
                class="citation-card"
                @click="openCitationFromList(cite)"
              >
                <span class="citation-index">{{ cite.index }}</span>
                <span class="citation-main">
                  <span class="citation-title">{{ cite.document_name || '未知文档' }}</span>
                  <span class="citation-meta">{{ citationMeta(cite) }}</span>
                  <span class="citation-snippet">{{ cite.snippet }}</span>
                </span>
                <span class="citation-open">详情 ›</span>
              </button>
            </div>
          </div>
          <div class="modal-foot">
            <button class="ghost" @click="copyAllCitations">复制全部</button>
            <button class="primary" @click="sourceListModal = null">关闭</button>
          </div>
        </div>
      </div>

      <div v-if="citationModal" class="modal-mask" @click.self="citationModal = null">
        <div class="modal citation-modal">
          <div class="modal-head">
            <h3>来源详情</h3>
            <button class="subtle sm" @click="citationModal = null">关闭</button>
          </div>
          <div class="modal-body">
            <div v-if="citationModal.loading" class="muted">正在校验来源状态…</div>
            <div
              v-else-if="citationModal.statusText"
              class="citation-status"
              :class="citationModal.state"
            >
              {{ citationModal.statusText }}
            </div>
            <div class="panel-row">
              <span class="k">知识库</span>
              <span class="v">
                <strong>{{ citationModal.cite.series_name || '未知知识库' }}</strong>
                <span class="muted mono"> #{{ citationModal.cite.series_id ?? '—' }}</span>
              </span>
            </div>
            <div class="panel-row">
              <span class="k">文档</span>
              <span class="v">
                {{ citationModal.cite.document_name || '未知文档' }}
                <span class="muted mono"> #{{ citationModal.cite.document_id ?? '—' }}</span>
              </span>
            </div>
            <div class="panel-row">
              <span class="k">位置</span>
              <span class="v">
                {{ citationModal.cite.title_path || '—' }}
                <template v-if="citationModal.cite.page_no">
                  · 第 {{ citationModal.cite.page_no }} 页
                </template>
                <template v-if="citationModal.cite.chunk_seq">
                  · 分块 #{{ citationModal.cite.chunk_seq }}
                </template>
                <span class="muted mono">（chunk {{ citationModal.cite.chunk_id }}）</span>
              </span>
            </div>
            <div v-if="citationModal.cite.query" class="panel-row">
              <span class="k">查询词</span>
              <span class="v">{{ citationModal.cite.query }}</span>
            </div>
            <div class="panel-row">
              <span class="k">召回</span>
              <span class="v mono">
                {{ (citationModal.cite.routes || []).join(' / ') || 'keyword' }} ·
                {{ Number(citationModal.cite.score || 0).toFixed(3) }}
              </span>
            </div>
            <div v-if="citationModal.cite.retrieved_at" class="panel-row">
              <span class="k">检索时间</span>
              <span class="v">{{ fullTime(citationModal.cite.retrieved_at) }}</span>
            </div>
            <div v-if="citationModal.cite.document_version" class="panel-row">
              <span class="k">文档版本</span>
              <span class="v mono">{{ citationModal.cite.document_version }}</span>
            </div>
            <div v-if="isLowConfidence([citationModal.cite])" class="citation-warn">
              未找到与问题直接相关的字面依据，请谨慎参考。
            </div>
            <div class="snapshot-block">
              <div class="muted" style="margin-bottom: 4px">回答时的内容快照</div>
              <div class="snapshot-text">
                {{ citationModal.cite.content_snapshot || citationModal.cite.snippet || '（无片段）' }}
              </div>
            </div>
          </div>
          <div class="modal-foot">
            <button class="ghost" @click="copyCitation(citationModal.cite)">复制引用</button>
            <button class="primary" @click="citationModal = null">关闭</button>
          </div>
        </div>
      </div>

      <div v-if="routePanel" class="route-preview">
        <h4>
          <span>路由预览</span>
          <button class="subtle sm" @click="routePanel = null">关闭</button>
        </h4>
        <div v-if="!routePanel.enabled" class="muted">智能体路由未启用，本轮将沿用静态链路。</div>
        <template v-else>
          <div class="panel-row">
            <span class="k">目标</span>
            <span class="v">
              <span class="badge blue">{{ routePanel.target || '—' }}</span>
              <template v-if="routePanel.agent_name"> {{ routePanel.agent_name }}</template>
            </span>
          </div>
          <div class="panel-row">
            <span class="k">置信度</span>
            <span class="v">
              {{ routePanel.confidence != null ? Number(routePanel.confidence).toFixed(2) : '—' }}
            </span>
          </div>
          <div class="panel-row">
            <span class="k">类别</span>
            <span class="v">{{ routePanel.payroll ? '薪酬问题（不判检索）' : '非薪酬问题' }}</span>
          </div>
          <div v-if="routePanel.kb" class="panel-row">
            <span class="k">知识库</span>
            <span class="v">
              {{ routePanel.kb.need ? '需要检索' : '不检索' }}
              （{{ kbKindLabel(routePanel.kb.kind) }} · 来源：{{ kbSourceLabel(routePanel.kb.source) }}）
            </span>
          </div>
          <div v-if="routePanel.semantic && routePanel.semantic.label !== 'unavailable'" class="panel-row">
            <span class="k">语义</span>
            <span class="v">
              {{ semanticLabel(routePanel.semantic.label) }}
              · score {{ Number(routePanel.semantic.score || 0).toFixed(2) }}
              · margin {{ Number(routePanel.semantic.margin || 0).toFixed(2) }}
              · {{ routePanel.semantic.confident ? '采用' : '未采用' }}
            </span>
          </div>
          <div v-else-if="routePanel.semantic" class="panel-row">
            <span class="k">语义</span>
            <span class="v">{{ routePanel.semantic.reason || '未启用' }}</span>
          </div>
          <div class="panel-row">
            <span class="k">默认流程</span>
            <span class="v">{{ routePanel.flow_active ? `已启用（${routePanel.flow_id}）` : '未启用' }}</span>
          </div>
          <div class="panel-row">
            <span class="k">团队</span>
            <span class="v">{{ routePanel.team_available ? '可用' : '不可用' }}</span>
          </div>
          <div class="panel-row">
            <span class="k">理由</span>
            <span class="v">{{ routePanel.reason || '—' }}</span>
          </div>
          <div v-if="routePanel.failure_reason" class="panel-row">
            <span class="k">回退</span>
            <span class="v error-text">{{ routePanel.failure_reason }}</span>
          </div>
          <div class="panel-row">
            <span class="k">耗时</span>
            <span class="v">{{ routePanel.elapsed_ms || 0 }}ms</span>
          </div>
          <div v-if="routePanel.candidates?.length">
            <div class="muted" style="margin-bottom: 6px">候选智能体</div>
            <div class="route-candidates">
              <div v-for="candidate in routePanel.candidates" :key="candidate.id" class="route-candidate">
                <strong>{{ candidate.name || candidate.id }}</strong>
                <small>{{ candidate.description || candidate.id }}</small>
              </div>
            </div>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

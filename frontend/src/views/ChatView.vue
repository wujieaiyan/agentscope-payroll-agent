<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { api } from '../api/http';
import { resumeTurn, streamChat } from '../api/sse';
import { clearActiveTurn, loadActiveTurn, saveActiveTurn } from '../stores/activeTurn';
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

let controller = null;
let turnState = { turnId: null, lastSeq: 0, startedAt: 0 };
let scrollQueued = false;
let resuming = false;
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

function formatTime(value) {
  if (!value) return '';
  const text = String(value).replace('T', ' ').slice(0, 16);
  return text.length > 10 ? text.slice(5) : text;
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
  } catch (error) {
    if (!silent) toast(error.message, 'error');
  }
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
    routing: null,
    model: '',
    messageId: null,
    cache: null,
    error: '',
    finished: false,
    cancelled: false,
    recovering: false,
    resumable: false,
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
    case 'start':
      state.model = data.model || state.model;
      break;
    case 'agent_start':
      state.agents.push({ id: data.agent, label: data.label || data.agent, text: '' });
      pushEvent('智能体', 'agent', `${data.label || data.agent} 开始工作`);
      break;
    case 'agent_text': {
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
    case 'text':
      enqueueText(data.text || '');
      break;
    case 'action_proposal':
      if (data.proposal) state.proposals.push(data.proposal);
      break;
    case 'done':
      state.finished = true;
      state.routing = data.routing || null;
      state.model = data.model || state.model;
      state.messageId = data.message_id || null;
      state.cache = data.cache || null;
      if (Array.isArray(data.proposals)) state.proposals = data.proposals;
      // 模型没有流式文本时才用 done 的整段回复兜底，避免与队列重复。
      if (!state.text && !state.pendingText && data.reply) enqueueText(data.reply);
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
      await resumeTurn(sessionId, turnId, cursor, handleEvent, controller.signal);
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
    controller = new AbortController();
    try {
      await resumeTurn(
        sessionId,
        turnState.turnId,
        turnState.lastSeq,
        handleEvent,
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
      if (activeId.value === sessionId) await finalizeStream();
    }
  } finally {
    resuming = false;
  }
}

/** 收尾：成功的轮次以数据库消息呈现，可续传的中断保留气泡与重试入口。 */
async function finalizeStream() {
  const state = streaming.value;
  if (!state) return;
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
  try {
    await resumeLoop(activeId.value, turnState.lastSeq);
  } finally {
    await finalizeStream();
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
  busy.value = true;
  draft.value = '';
  turnState = { turnId: null, lastSeq: 0, startedAt: 0 };
  stopTyping();
  streaming.value = newStreamingState();
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
    await streamChat(sessionId, text, handleEvent, controller.signal);
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

async function openDetail(message, kind) {
  if (!message.id || message.id.startsWith('local-')) return;
  if (detail.value && detail.value.messageId === message.id && detail.value.kind === kind) {
    detail.value = null;
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
          <button class="ghost sm" :disabled="routeBusy" @click="previewRoute">
            {{ routeBusy ? '路由中…' : '路由预览' }}
          </button>
          <button class="ghost sm" :disabled="busy || !activeId" @click="resetSession">重置</button>
          <button v-if="busy" class="danger sm" @click="stop">停止</button>
        </div>
      </header>

      <div ref="messageBox" class="messages">
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
          <div class="msg" :class="message.role === 'USER' ? 'user' : 'assistant'">
            <div class="bubble-avatar">
              {{ message.role === 'USER' ? (auth.user?.display_name || '我').slice(0, 1) : '协' }}
            </div>
            <div class="msg-body">
              <div class="msg-meta">
                <span>{{ roleLabel(message.role) }}</span>
                <span>{{ formatTime(message.created_at) }}</span>
              </div>
              <div class="bubble" :class="{ error: message.role === 'ERROR' }">
                {{ message.content }}
              </div>

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
                    @click="submitFeedback(message, 'UP')"
                  >有帮助</button>
                  <button
                    :class="{ on: feedbackMap[message.id] === 'DOWN' }"
                    title="没帮助"
                    @click="submitFeedback(message, 'DOWN')"
                  >没帮助</button>
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
              <span v-else-if="streaming.text">正在输出</span>
              <span v-else-if="busy">正在思考</span>
              <span v-else-if="streaming.resumable">连接已中断</span>
            </div>
            <div class="bubble" :class="{ error: streaming.error }">
              <template v-if="streaming.text">{{ streaming.text }}</template>
              <span v-else-if="!streaming.error" class="typing"><i></i><i></i><i></i></span>
              <template v-if="streaming.error">{{ streaming.error }}</template>
              <template v-else-if="streaming.cancelled">（本轮回答已取消）</template>
            </div>

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
              <span v-if="streaming.cache" class="chip plain">缓存命中</span>
            </div>
          </div>
        </div>
      </div>

      <footer class="composer">
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

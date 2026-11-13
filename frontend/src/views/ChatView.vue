<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { api } from '../api/http';
import { resumeTurn, streamChat } from '../api/sse';
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
let turnState = { turnId: null, lastSeq: 0 };
let scrollQueued = false;

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
  streaming.value = null;
  await loadMessages();
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
      activeId.value = sessions.value.length ? sessions.value[0].id : null;
      messages.value = [];
      if (activeId.value) await loadMessages();
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
    await loadMessages();
    toast('会话已重置', 'success');
  } catch (error) {
    toast(error.message, 'error');
  }
}

function pushEvent(tag, cls, text) {
  streaming.value.events.push({ tag, cls, text });
}

function touchSeq(data) {
  if (data && Number.isFinite(data.seq)) {
    turnState.lastSeq = Math.max(turnState.lastSeq, data.seq);
  }
}

function handleEvent(event, data) {
  const state = streaming.value;
  if (!state) return;
  touchSeq(data);
  switch (event) {
    case 'turn':
      turnState.turnId = data.turn_id;
      turnState.lastSeq = 0;
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
      state.text += data.text || '';
      scrollBottom();
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
      if (!state.text && data.reply) state.text = data.reply;
      break;
    case 'error':
      if (data.cancelled) {
        state.cancelled = true;
      } else {
        state.error = data.message || '回答中断';
        toast(state.error, 'error');
      }
      break;
    default:
      break;
  }
}

function streamFailed(message) {
  if (!streaming.value) return;
  streaming.value.error = message || '连接中断，请重试';
  toast(streaming.value.error, 'error');
}

async function tryResume(reason) {
  const state = streaming.value;
  if (!state || state.finished || state.cancelled) return;
  const { turnId, lastSeq } = turnState;
  if (!turnId) {
    streamFailed(reason.message);
    return;
  }
  try {
    const turn = await api(`/api/chat/sessions/${activeId.value}/turns/${turnId}`);
    if (turn && turn.status === 'SUCCEEDED') {
      await loadMessages();
      return;
    }
    if (turn && ['FAILED', 'CANCELED', 'EXPIRED'].includes(turn.status)) {
      streamFailed(turn.error_message || reason.message || '本轮回答已中断');
      return;
    }
    toast('连接中断，正在尝试续流…', 'warning');
    await resumeTurn(activeId.value, turnId, lastSeq, handleEvent, controller ? controller.signal : undefined);
  } catch (error) {
    streamFailed(error.message || reason.message);
  }
}

async function send() {
  const text = draft.value.trim();
  if (!text || busy.value) return;
  if (!activeId.value) {
    await createSession();
    if (!activeId.value) return;
  }
  const sessionId = activeId.value;
  busy.value = true;
  draft.value = '';
  turnState = { turnId: null, lastSeq: 0 };
  streaming.value = {
    text: '',
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
  };
  messages.value.push({
    id: `local-${Date.now()}`,
    role: 'USER',
    content: text,
    tools: [],
    created_at: new Date().toISOString(),
  });
  scrollBottom(true);

  controller = new AbortController();
  let refreshed = false;
  try {
    await streamChat(sessionId, text, handleEvent, controller.signal);
  } catch (error) {
    if (error.name !== 'AbortError' && !streaming.value.cancelled) {
      await tryResume(error);
    }
  } finally {
    const state = streaming.value;
    if (state && state.finished && !state.error && !state.cancelled) {
      await loadMessages(true);
      refreshed = true;
    }
    busy.value = false;
    controller = null;
    if (!refreshed && state) {
      if (state.cancelled) {
        toast('本轮回答已取消', 'warning');
        await loadMessages(true);
      } else if (state.messageId) {
        await loadMessages(true);
      }
    }
    streaming.value = null;
    detail.value = null;
    scrollBottom(true);
  }
}

async function stop() {
  if (!busy.value || !turnState.turnId) return;
  try {
    await api(`/api/chat/sessions/${activeId.value}/turns/${turnState.turnId}/cancel`, {
      method: 'POST',
    });
    if (streaming.value) streaming.value.cancelled = true;
    toast('已请求取消本轮回答', 'warning');
  } catch (error) {
    toast(error.message, 'error');
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
  await loadSessions();
  if (sessions.value.length) {
    await selectSession(sessions.value[0].id);
  } else {
    await createSession();
  }
});

onBeforeUnmount(() => {
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
              <span v-if="busy">正在思考</span>
            </div>
            <div class="bubble" :class="{ error: streaming.error }">
              <template v-if="streaming.text">{{ streaming.text }}</template>
              <span v-else-if="!streaming.error" class="typing"><i></i><i></i><i></i></span>
              <template v-if="streaming.error">{{ streaming.error }}</template>
              <template v-else-if="streaming.cancelled">（本轮回答已取消）</template>
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

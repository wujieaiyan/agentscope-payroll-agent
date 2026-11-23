<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';
import UsageTrendChart from '../../components/UsageTrendChart.vue';
import UsageDonutChart from '../../components/UsageDonutChart.vue';
import { compactNumber, formatTime, fullNumber, percent } from '../../utils/usageFormat';

const REFRESH_MS = 60_000;
const RANGES = [
  { value: '24h', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
];

const filters = reactive({ range: '24h', plane: '', model: '' });
const overview = ref(null);
const loading = ref(false);
const updatedAt = ref('');
let timer = null;

const summary = computed(
  () =>
    overview.value?.summary || {
      calls: 0,
      successes: 0,
      failures: 0,
      prompt_tokens: 0,
      completion_tokens: 0,
      cached_tokens: 0,
      total_tokens: 0,
      avg_latency_ms: 0,
      active_users: 0,
      active_agents: 0,
    }
);

const empty = computed(() => !loading.value && summary.value.calls === 0);
const kpis = computed(() => {
  const s = summary.value;
  return [
    {
      key: 'tokens',
      label: 'Token 总消耗',
      value: compactNumber(s.total_tokens),
      hint: `提示 ${compactNumber(s.prompt_tokens)} · 生成 ${compactNumber(s.completion_tokens)}`,
      accent: 'blue',
    },
    {
      key: 'calls',
      label: '模型调用',
      value: compactNumber(s.calls),
      hint: `成功 ${compactNumber(s.successes)} · 失败 ${compactNumber(s.failures)}`,
      accent: 'green',
    },
    {
      key: 'failure',
      label: '失败率',
      value: percent(s.failures, s.calls),
      hint: `${fullNumber(s.failures)} 次失败调用`,
      accent: s.failures ? 'red' : 'green',
    },
    {
      key: 'latency',
      label: '平均时延',
      value: `${s.avg_latency_ms || 0} ms`,
      hint: '按成功返回的调用统计',
      accent: 'orange',
    },
    {
      key: 'users',
      label: '活跃用户',
      value: compactNumber(s.active_users),
      hint: '当前时间窗内发起调用',
      accent: 'purple',
    },
    {
      key: 'agents',
      label: '活跃智能体',
      value: compactNumber(s.active_agents),
      hint: '按智能体归因去重',
      accent: 'cyan',
    },
  ];
});

const planeBars = computed(() => bars(overview.value?.by_plane || []));
const agentBars = computed(() => bars(overview.value?.by_agent || []));
const userBars = computed(() => bars(overview.value?.by_user || []));
const recent = computed(() => overview.value?.recent || []);

function bars(slices) {
  const max = Math.max(1, ...slices.map((item) => Number(item.total_tokens || 0)));
  return slices.map((item) => ({
    ...item,
    width: `${Math.max(3, (Number(item.total_tokens || 0) / max) * 100)}%`,
  }));
}

function query() {
  const params = new URLSearchParams({ range: filters.range });
  if (filters.plane) params.set('plane', filters.plane);
  if (filters.model.trim()) params.set('model', filters.model.trim());
  return params.toString();
}

async function load(silent = false) {
  if (loading.value) return;
  loading.value = true;
  try {
    overview.value = await api(`/api/admin/usage/overview?${query()}`);
    updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  } catch (error) {
    if (!silent) toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function switchRange(value) {
  if (filters.range === value) return;
  filters.range = value;
  load();
}

function reset() {
  filters.plane = '';
  filters.model = '';
  load();
}

function tokenTitle(row) {
  return `提示 ${fullNumber(row.prompt_tokens)} / 生成 ${fullNumber(row.completion_tokens)} / 缓存 ${fullNumber(
    row.cached_tokens
  )} tokens`;
}

function agentLabel(row) {
  const name = row.agent_name || row.agent_id || '未归因';
  return row.role ? `${name}（${row.role}）` : name;
}

function userLabel(row) {
  return row.user_id ? `用户 #${row.user_id}` : '系统';
}

onMounted(() => {
  load();
  timer = window.setInterval(() => load(true), REFRESH_MS);
});

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
});
</script>

<template>
  <div class="page usage-page">
    <div class="page-head">
      <div>
        <h2>用量看板</h2>
        <p>监控模型调用的 Token 消耗、时延与失败情况；数据来自运行期逐次调用埋点，每 60 秒自动刷新。</p>
      </div>
      <div class="toolbar">
        <div class="segmented">
          <button
            v-for="item in RANGES"
            :key="item.value"
            class="seg-btn"
            :class="{ active: filters.range === item.value }"
            @click="switchRange(item.value)"
          >
            {{ item.label }}
          </button>
        </div>
        <select v-model="filters.plane" style="width: 130px" @change="load()">
          <option value="">全部使用面</option>
          <option value="控制面">控制面</option>
          <option value="轻量面">轻量面</option>
          <option value="编码面">编码面</option>
          <option value="未标注">未标注</option>
        </select>
        <input
          v-model="filters.model"
          placeholder="模型名，如 qwen2.5"
          style="width: 170px"
          @keyup.enter="load()"
        />
        <button class="ghost sm" @click="reset">重置</button>
        <button class="primary" :disabled="loading" @click="load()">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </div>

    <div class="kpi-grid">
      <div v-for="kpi in kpis" :key="kpi.key" class="kpi" :class="kpi.accent">
        <span class="kpi-label">{{ kpi.label }}</span>
        <strong class="kpi-value">{{ kpi.value }}</strong>
        <span class="kpi-hint">{{ kpi.hint }}</span>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <h3>Token 消耗趋势</h3>
        <small>
          {{ overview?.granularity === 'hour' ? '按小时聚合' : '按天聚合' }}
          <template v-if="updatedAt"> · 更新于 {{ updatedAt }}</template>
        </small>
      </div>
      <UsageTrendChart :points="overview?.series || []" :granularity="overview?.granularity || 'hour'" />
    </div>

    <div class="dist-grid">
      <div class="card">
        <div class="card-title"><h3>模型占比</h3><small>按 Token 合计</small></div>
        <UsageDonutChart :slices="overview?.by_model || []" />
      </div>
      <div class="card">
        <div class="card-title"><h3>使用面分布</h3><small>控制面 / 轻量面 / 编码面</small></div>
        <ul v-if="planeBars.length" class="rank-list">
          <li v-for="item in planeBars" :key="item.key">
            <div class="rank-head">
              <span class="rank-name">{{ item.label || item.key }}</span>
              <span class="rank-value">{{ compactNumber(item.total_tokens) }}</span>
            </div>
            <div class="rank-track"><span class="rank-bar blue" :style="{ width: item.width }"></span></div>
            <div class="rank-foot">{{ compactNumber(item.calls) }} 次调用 · 失败 {{ item.failures }}</div>
          </li>
        </ul>
        <div v-else class="empty">暂无使用面数据</div>
      </div>
      <div class="card">
        <div class="card-title"><h3>智能体 Top</h3><small>按 Token 合计</small></div>
        <ul v-if="agentBars.length" class="rank-list">
          <li v-for="item in agentBars" :key="item.key">
            <div class="rank-head">
              <span class="rank-name" :title="item.label || item.key">{{ item.label || item.key }}</span>
              <span class="rank-value">{{ compactNumber(item.total_tokens) }}</span>
            </div>
            <div class="rank-track"><span class="rank-bar green" :style="{ width: item.width }"></span></div>
            <div class="rank-foot">{{ compactNumber(item.calls) }} 次调用 · 失败 {{ item.failures }}</div>
          </li>
        </ul>
        <div v-else class="empty">暂无智能体数据</div>
      </div>
      <div class="card">
        <div class="card-title"><h3>用户 Top</h3><small>按 Token 合计</small></div>
        <ul v-if="userBars.length" class="rank-list">
          <li v-for="item in userBars" :key="item.key">
            <div class="rank-head">
              <span class="rank-name" :title="item.label || item.key">{{ item.label || item.key }}</span>
              <span class="rank-value">{{ compactNumber(item.total_tokens) }}</span>
            </div>
            <div class="rank-track"><span class="rank-bar orange" :style="{ width: item.width }"></span></div>
            <div class="rank-foot">{{ compactNumber(item.calls) }} 次调用 · 失败 {{ item.failures }}</div>
          </li>
        </ul>
        <div v-else class="empty">暂无用户数据</div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <h3>最近调用</h3>
        <small>最近 12 次模型调用</small>
      </div>
      <div v-if="recent.length" class="table-wrap recent">
        <table class="table">
          <thead>
            <tr>
              <th>时间</th>
              <th>用户</th>
              <th>智能体</th>
              <th>模型 / 使用面</th>
              <th>Tokens</th>
              <th>时延</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in recent" :key="`${row.occurred_at}-${row.reply_id}-${row.agent_id}`">
              <td class="mono">{{ formatTime(row.occurred_at) }}</td>
              <td>{{ userLabel(row) }}</td>
              <td>
                <span class="agent-cell" :title="agentLabel(row)">{{ agentLabel(row) }}</span>
                <small v-if="row.stage_key" class="muted"> · {{ row.stage_key }}</small>
              </td>
              <td>
                <span class="mono">{{ row.model || '未标注' }}</span>
                <span class="badge blue" style="margin-left: 6px">{{ row.plane || '未标注' }}</span>
              </td>
              <td :title="tokenTitle(row)" class="mono">{{ fullNumber(row.total_tokens) }}</td>
              <td class="mono">{{ fullNumber(row.latency_ms) }} ms</td>
              <td>
                <span class="badge" :class="row.success ? 'green' : 'red'">
                  {{ row.success ? '成功' : row.error_type || '失败' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty">{{ loading ? '加载中…' : '当前时间窗没有模型调用' }}</div>
    </div>

    <div v-if="empty" class="card empty-banner">
      当前筛选条件下没有用量数据。发起一次对话或研发任务后，运行期会逐次记录模型调用并在这里展示。
    </div>
  </div>
</template>

<style scoped>
.usage-page { max-width: 1500px; }

.segmented {
  display: inline-flex;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface);
}
.seg-btn {
  border: none;
  border-radius: 0;
  background: transparent;
  color: var(--text-soft);
  padding: 6px 12px;
  font-size: 12.5px;
}
.seg-btn + .seg-btn { border-left: 1px solid var(--border); }
.seg-btn.active { background: var(--primary-soft); color: var(--primary-dark); font-weight: 600; }

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.kpi {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px 14px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  overflow: hidden;
}
.kpi::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--primary);
}
.kpi.green::before { background: var(--success); }
.kpi.red::before { background: var(--danger); }
.kpi.orange::before { background: var(--warning); }
.kpi.purple::before { background: #7c4dff; }
.kpi.cyan::before { background: #00b8d9; }
.kpi-label { font-size: 12.5px; color: var(--muted); }
.kpi-value { font-size: 24px; line-height: 1.2; color: var(--text); font-variant-numeric: tabular-nums; }
.kpi-hint { font-size: 11.5px; color: var(--faint); }

.dist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.rank-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.rank-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; font-size: 12.5px; }
.rank-name { color: var(--text-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-value { color: var(--text); font-variant-numeric: tabular-nums; }
.rank-track { height: 6px; margin-top: 5px; border-radius: 3px; background: var(--surface-3); overflow: hidden; }
.rank-bar { display: block; height: 100%; border-radius: 3px; background: var(--primary); }
.rank-bar.blue { background: var(--primary); }
.rank-bar.green { background: var(--success); }
.rank-bar.orange { background: var(--warning); }
.rank-foot { margin-top: 4px; font-size: 11.5px; color: var(--faint); }

.table-wrap.recent { border: none; }
.recent table.table th { background: var(--surface-2); }
.agent-cell { color: var(--text); }
.empty-banner { text-align: center; color: var(--muted); font-size: 13px; }
</style>

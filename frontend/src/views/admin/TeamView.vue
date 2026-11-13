<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const config = ref(null);
const runs = ref({ runs: [], counts_by_mode: {}, counts_by_status: {}, count: 0, days: 0 });
const loading = ref(false);
const detail = ref(null);
const detailLoading = ref(false);
const filters = reactive({ days: 7, mode: '', status: '' });

const modeLabel = { OFF: '关闭', STATIC: '静态链路', SHADOW: '影子', ACTIVE: '主运行' };

function modeText(mode) {
  return modeLabel[mode] || mode;
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(5, 16) : '—';
}

async function loadConfig() {
  try {
    config.value = await api('/api/admin/team/config');
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function loadRuns() {
  loading.value = true;
  try {
    const query = new URLSearchParams({
      days: String(filters.days),
      mode: filters.mode,
      status: filters.status,
      limit: '200',
    });
    runs.value = (await api(`/api/admin/team/runs?${query.toString()}`)) || runs.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function openRun(row) {
  detailLoading.value = true;
  detail.value = { row, loaded: false };
  try {
    detail.value = await api(`/api/admin/team/runs/${row.id}`);
    detail.value.loaded = true;
  } catch (error) {
    detail.value = null;
    toast(error.message, 'error');
  } finally {
    detailLoading.value = false;
  }
}

const modeEntries = computed(() => Object.entries(runs.value.counts_by_mode || {}));
const statusEntries = computed(() => Object.entries(runs.value.counts_by_status || {}));

onMounted(async () => {
  await Promise.all([loadConfig(), loadRuns()]);
});
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>团队运行</h2>
        <p>动态团队按 SHADOW 采样或 ACTIVE 白名单运行；回退链路与节点耗时在此追溯。</p>
      </div>
      <div class="toolbar">
        <select v-model.number="filters.days" style="width: 110px" @change="loadRuns">
          <option :value="1">近 1 天</option>
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
        </select>
        <select v-model="filters.mode" style="width: 120px" @change="loadRuns">
          <option value="">全部模式</option>
          <option value="STATIC">静态链路</option>
          <option value="SHADOW">影子</option>
          <option value="ACTIVE">主运行</option>
        </select>
        <select v-model="filters.status" style="width: 120px" @change="loadRuns">
          <option value="">全部状态</option>
          <option value="SUCCESS">成功</option>
          <option value="FAILED">失败</option>
          <option value="FALLBACK">回退</option>
          <option value="RUNNING">运行中</option>
        </select>
        <button class="ghost" @click="loadRuns">刷新</button>
      </div>
    </div>

    <div v-if="config" class="card">
      <div class="card-title">
        <h3>当前配置</h3>
        <span class="badge" :class="config.enabled ? 'green' : ''">
          {{ modeText(config.mode) }}
        </span>
      </div>
      <div class="form-grid">
        <div class="field">引擎 <strong>{{ config.enabled ? '启用' : '停用' }}</strong></div>
        <div class="field">影子采样 <strong>{{ config.shadow_enabled ? `${config.shadow_sample_rate * 100}%` : '未启用' }}</strong></div>
        <div class="field">
          主运行白名单
          <strong>
            {{ config.active_enabled ? (config.active_all_users ? '全部用户' : `${(config.active_user_ids || []).length} 个用户`) : '未启用' }}
          </strong>
        </div>
        <div class="field">
          会话范围
          <strong>
            {{ config.active_all_sessions ? '全部会话' : `${(config.active_session_ids || []).length} 个会话` }}
          </strong>
        </div>
        <div class="field">保留天数 <strong>{{ config.retention_days }} 天</strong></div>
        <div class="field">统计窗口 <strong>{{ config.stats_window_days }} 天</strong></div>
      </div>
    </div>

    <div class="toolbar">
      <span class="hint">窗口 {{ runs.days }} 天，共 {{ runs.count }} 次运行</span>
      <span v-for="[mode, count] in modeEntries" :key="mode" class="badge blue">
        {{ modeText(mode) }} {{ count }}
      </span>
      <span v-for="[status, count] in statusEntries" :key="status" class="badge">
        {{ status }} {{ count }}
      </span>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>运行</th>
            <th>模式</th>
            <th>状态</th>
            <th>模板</th>
            <th>节点</th>
            <th>智能体</th>
            <th>主运行</th>
            <th>耗时</th>
            <th>开始时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in runs.runs || []" :key="row.run_id">
            <td class="mono">#{{ row.run_id }}</td>
            <td>{{ modeText(row.mode) }}</td>
            <td>
              <span class="badge" :class="row.status === 'SUCCESS' ? 'green' : row.status === 'FAILED' ? 'red' : 'blue'">
                {{ row.status }}
              </span>
            </td>
            <td>{{ row.template_id || '—' }}</td>
            <td>{{ row.node_count }}</td>
            <td class="muted">{{ (row.agent_ids || []).join('、') || '—' }}</td>
            <td>{{ row.primary ? '是' : '否' }}</td>
            <td>{{ row.total_ms }}ms</td>
            <td>{{ formatTime(row.created_at) }}</td>
            <td><button class="subtle sm" @click="openRun(row)">详情</button></td>
          </tr>
          <tr v-if="!(runs.runs || []).length">
            <td colspan="10" class="empty">{{ loading ? '加载中…' : '窗口内没有团队运行' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>运行 #{{ detail.run?.id }}</h3>
          <button class="subtle sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="detailLoading || !detail.loaded" class="empty">加载中…</div>
          <template v-else>
            <div class="card">
              <div class="card-title"><h3>运行概要</h3></div>
              <div class="panel-row"><span class="k">模式</span><span class="v">{{ modeText(detail.run?.mode) }}</span></div>
              <div class="panel-row"><span class="k">状态</span><span class="v">{{ detail.run?.status }}</span></div>
              <div class="panel-row"><span class="k">任务</span><span class="v">{{ detail.run?.task_type || '—' }}</span></div>
              <div class="panel-row"><span class="k">模板</span><span class="v">{{ detail.run?.template_id || '—' }}</span></div>
              <div class="panel-row"><span class="k">耗时</span><span class="v">{{ detail.run?.total_ms }}ms</span></div>
              <div v-if="detail.run?.fallback_reason" class="panel-row">
                <span class="k">回退</span><span class="v error-text">{{ detail.run.fallback_reason }}</span>
              </div>
              <div v-if="detail.run?.baseline_run_id" class="panel-row">
                <span class="k">基线</span><span class="v">#{{ detail.run.baseline_run_id }}</span>
              </div>
            </div>
            <div class="card">
              <div class="card-title"><h3>节点</h3><small>{{ (detail.records || []).length }} 个</small></div>
              <div class="node-list">
                <div v-for="record in detail.records || []" :key="record.id" class="node-item">
                  <span class="name">{{ record.role || record.agent_id }}</span>
                  <span class="badge" :class="record.status === 'SUCCESS' ? 'green' : 'red'">{{ record.status }}</span>
                  <span class="muted">{{ record.tool_calls }} 次工具 · {{ record.duration_ms }}ms</span>
                </div>
              </div>
              <div v-if="!(detail.records || []).length" class="muted">没有节点记录。</div>
            </div>
            <div class="card">
              <div class="card-title"><h3>计划快照</h3></div>
              <pre class="trace-pre">{{ detail.run?.plan_json || '—' }}</pre>
            </div>
          </template>
        </div>
      </aside>
    </div>
  </div>
</template>

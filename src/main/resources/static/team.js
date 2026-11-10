/* 团队运行页：只读展示模式配置、近期运行与回退链路，复用 app.js 的全局 api()/showError()。 */
let teamVm = null;

function initTeamTab() {
  if (teamVm) {
    teamVm.reload();
    return;
  }
  if (typeof Vue === 'undefined') {
    showError(new Error('Vue 资源未加载，无法打开团队运行页'));
    return;
  }
  const { createApp } = Vue;

  const App = {
    data() {
      return {
        loading: false,
        config: null,
        report: { days: 0, count: 0, counts_by_mode: {}, counts_by_status: {}, runs: [] },
        filters: { days: 7, mode: '', status: '' },
        detail: null,
        detailLoading: false,
        selectedRunId: 0,
      };
    },
    computed: {
      modeBadge() {
        const mode = this.config ? this.config.mode : 'OFF';
        return {
          text: mode,
          className: mode === 'ACTIVE' ? 'ok' : mode === 'SHADOW' ? 'warn' : '',
        };
      },
      activeHint() {
        const c = this.config;
        if (!c) return '';
        if (c.mode !== 'ACTIVE') return '当前未启用 ACTIVE；白名单仅在 ACTIVE 模式下生效。';
        return c.active_enabled
          ? 'ACTIVE 已启用：白名单内先尝试动态组队，失败自动回退标准流程。'
          : '模式为 ACTIVE，但动态服务不可用，全部请求走标准流程。';
      },
      whitelistText() {
        const c = this.config;
        if (!c) return '';
        const users = c.active_all_users ? '全部用户' : `用户 ${c.active_user_ids.join('、')}`;
        const sessions = c.active_all_sessions
          ? '全部会话'
          : `会话 ${c.active_session_ids.join('、')}`;
        return `${users} · ${sessions}`;
      },
      modeCounts() {
        return Object.entries(this.report.counts_by_mode || {})
          .map(([key, value]) => `${this.modeText(key)} ${value}`)
          .join(' · ') || '暂无运行';
      },
      statusCounts() {
        return Object.entries(this.report.counts_by_status || {})
          .map(([key, value]) => `${this.statusText(key)} ${value}`)
          .join(' · ');
      },
    },
    methods: {
      async reload() {
        this.loading = true;
        try {
          const query = new URLSearchParams();
          if (this.filters.days) query.set('days', this.filters.days);
          if (this.filters.mode) query.set('mode', this.filters.mode);
          if (this.filters.status) query.set('status', this.filters.status);
          const [config, report] = await Promise.all([
            api('/api/admin/team/config'),
            api(`/api/admin/team/runs?${query.toString()}`),
          ]);
          this.config = config;
          this.report = report;
        } catch (error) {
          showError(error);
        } finally {
          this.loading = false;
        }
      },
      async openDetail(run) {
        this.selectedRunId = run.run_id;
        this.detailLoading = true;
        try {
          this.detail = await api(`/api/admin/team/runs/${run.run_id}`);
        } catch (error) {
          showError(error);
          this.detail = null;
        } finally {
          this.detailLoading = false;
        }
      },
      closeDetail() {
        this.selectedRunId = 0;
        this.detail = null;
      },
      modeText(value) {
        const map = { STATIC: '标准链路', SHADOW: '影子', ACTIVE: '动态生效', OFF: '未启用' };
        return map[value] || value || '-';
      },
      statusText(value) {
        const map = {
          PLANNED: '已计划', RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败',
          CANCELLED: '已取消', INTERRUPTED: '已中断', FALLBACK: '已回退',
          REJECTED: '已拒绝', CLOSED: '已关闭', EMPTY: '空输出',
          TIMEOUT: '超时', SKIPPED: '已跳过',
        };
        return map[value] || value || '-';
      },
      statusClass(value) {
        if (value === 'SUCCESS') return 'badge ok';
        if (value === 'FALLBACK' || value === 'FAILED' || value === 'CANCELLED' || value === 'INTERRUPTED') {
          return 'badge bad';
        }
        return 'badge';
      },
      formatTime(value) {
        return value ? String(value).replace('T', ' ').slice(0, 16) : '—';
      },
      durationText(value) {
        const ms = Number(value || 0);
        return ms > 0 ? `${(ms / 1000).toFixed(1)}s` : '—';
      },
      canOpen(row) {
        return row.message_id > 0 || row.node_count > 0;
      },
    },
    mounted() {
      this.reload();
    },
    template: `
      <div class="team-page">
        <div class="card">
          <div class="panel-head">
            <h2>动态团队</h2>
            <div class="actions">
              <span class="badge" :class="modeBadge.className">{{ modeBadge.text }}</span>
              <button type="button" @click="reload" :disabled="loading">
                {{ loading ? '加载中…' : '刷新' }}
              </button>
            </div>
          </div>
          <p class="hint">{{ activeHint }}</p>
          <p v-if="config" class="hint">
            保留 {{ config.retention_days }} 天 · 统计窗口 {{ config.stats_window_days }} 天 ·
            影子采样 {{ (config.shadow_sample_rate * 100).toFixed(0) }}% · ACTIVE 白名单：{{ whitelistText }}
          </p>
        </div>

        <div class="card team-runs-card">
          <div class="panel-head">
            <h2>近期运行</h2>
            <div class="actions team-filters">
              <label>天数<input type="number" min="1" max="180" v-model.number="filters.days"></label>
              <select v-model="filters.mode">
                <option value="">全部模式</option>
                <option value="STATIC">标准链路</option>
                <option value="SHADOW">影子</option>
                <option value="ACTIVE">动态生效</option>
              </select>
              <select v-model="filters.status">
                <option value="">全部状态</option>
                <option value="SUCCESS">成功</option>
                <option value="FALLBACK">已回退</option>
                <option value="RUNNING">运行中</option>
                <option value="FAILED">失败</option>
                <option value="CANCELLED">已取消</option>
              </select>
              <button type="button" @click="reload" :disabled="loading">查询</button>
            </div>
          </div>
          <p class="hint">窗口 {{ report.days }} 天 · {{ report.count }} 条 · {{ modeCounts }}<span v-if="statusCounts"> · {{ statusCounts }}</span></p>
          <p v-if="loading && !report.runs.length" class="hint">加载中…</p>
          <p v-else-if="!report.runs.length" class="hint">该窗口内没有团队运行</p>
          <div v-else class="table-wrap team-table-wrap">
            <table class="team-table">
              <thead>
                <tr>
                  <th class="num">#</th>
                  <th>时间</th>
                  <th>模式</th>
                  <th>状态</th>
                  <th>任务 / 模板</th>
                  <th>智能体</th>
                  <th class="num">耗时</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in report.runs" :key="row.run_id"
                    :class="{ selected: row.run_id === selectedRunId }"
                    @click="openDetail(row)">
                  <td class="num">
                    {{ row.run_id }}
                    <span v-if="row.primary" class="tag">主运行</span>
                  </td>
                  <td>{{ formatTime(row.created_at) }}</td>
                  <td>{{ modeText(row.mode) }}</td>
                  <td><span :class="statusClass(row.status)">{{ statusText(row.status) }}</span></td>
                  <td>
                    {{ row.task_type || '-' }}
                    <span class="hint">{{ row.template_id || '无模板' }}</span>
                  </td>
                  <td>
                    <span v-if="row.agent_ids.length">{{ row.agent_ids.join('、') }}</span>
                    <span v-else class="hint">—</span>
                  </td>
                  <td class="num">{{ durationText(row.total_ms) }}</td>
                  <td class="team-reason">
                    <span v-if="row.status === 'FALLBACK'" class="fallback-flag">已回退到标准流程</span>
                    <span v-if="row.fallback_reason" class="hint">{{ row.fallback_reason }}</span>
                    <span v-if="row.fallback_of_run_id" class="hint">→ 静态运行 #{{ row.fallback_of_run_id }}</span>
                    <span v-if="!row.fallback_reason && row.status !== 'FALLBACK'" class="hint">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="detail" class="card team-detail-card">
          <div class="panel-head">
            <h2>运行 #{{ detail.summary.run_id }}</h2>
            <div class="actions">
              <span :class="statusClass(detail.summary.status)">{{ statusText(detail.summary.status) }}</span>
              <button type="button" @click="closeDetail">关闭</button>
            </div>
          </div>
          <div class="team-detail-meta">
            <span>模式 {{ modeText(detail.summary.mode) }}</span>
            <span>计划 {{ detail.summary.plan_id || '—' }}</span>
            <span>模板 {{ detail.summary.template_id || '—' }}</span>
            <span>会话 #{{ detail.summary.session_id }} · 消息 #{{ detail.summary.message_id }}</span>
            <span>开始 {{ formatTime(detail.summary.created_at) }} · 结束 {{ formatTime(detail.summary.closed_at) }}</span>
          </div>
          <p v-if="detail.summary.status === 'FALLBACK'" class="team-fallback-banner">
            已回退到标准流程：{{ detail.summary.fallback_reason || '未提供原因' }}
            <span v-if="detail.summary.fallback_of_run_id">（实际执行静态运行 #{{ detail.summary.fallback_of_run_id }}）</span>
          </p>
          <p v-if="detailLoading" class="hint">加载中…</p>
          <p v-else-if="!detail.records.length" class="hint">该运行没有节点记录</p>
          <div v-else class="table-wrap">
            <table class="team-table">
              <thead>
                <tr>
                  <th>节点</th>
                  <th>角色</th>
                  <th>智能体</th>
                  <th>状态</th>
                  <th class="num">工具</th>
                  <th class="num">耗时</th>
                  <th>错误</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="node in detail.records" :key="node.id">
                  <td>{{ node.node_id || node.stage_key }}</td>
                  <td>{{ node.role || '—' }}</td>
                  <td>{{ node.agent_id || '—' }}</td>
                  <td>{{ statusText(node.status) }}</td>
                  <td class="num">{{ node.tool_calls }}<span v-if="node.tool_errors" class="hint">（错 {{ node.tool_errors }}）</span></td>
                  <td class="num">{{ durationText(node.duration_ms) }}</td>
                  <td class="hint">{{ node.error_message || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    `,
  };

  teamVm = createApp(App).mount('#team-app');
}

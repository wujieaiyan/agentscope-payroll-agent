/* Agent 管理页：Vue 3（本地 vendor 引入），复用 app.js 的全局 api()/toast()/showError()。 */
let agentsVm = null;

function initAgentsTab() {
  if (agentsVm) {
    agentsVm.reload();
    return;
  }
  if (typeof Vue === 'undefined') {
    showError(new Error('Vue 资源未加载，无法打开 Agent 管理'));
    return;
  }
  const { createApp } = Vue;

  const App = {
    data() {
      return {
        loading: false,
        saving: false,
        activating: false,
        clearing: false,
        agents: [],
        skills: [],
        stats: { window_days: 0, cached: false, record_count: 0, feedback_count: 0, agents: [] },
        statsLoading: false,
        meta: { writable: false, location: '', tags: [] },
        active: { agentId: '', name: '', configured: '', available: true },
        filters: { keyword: '', tag: '', skillId: '', status: '', visibility: '' },
        dialogVisible: false,
        editingId: null,
        form: this.emptyForm(),
      };
    },
    computed: {
      storageText() {
        return this.meta.writable
          ? `存储：Nacos ${this.meta.location}`
          : '存储：代码内置（未启用 Nacos，只读）';
      },
      activeText() {
        if (this.active.configured && this.active.available === false) {
          return `配置的核算智能体「${this.active.configured}」不可用，对话已阻止回退薪酬流水线`;
        }
        if (this.active.configured) {
          return `核算智能体：${this.active.name}`;
        }
        return '核算智能体：内置三段流水线';
      },
      filteredAgents() {
        const f = this.filters;
        return this.agents.filter((a) => {
          if (f.keyword) {
            const needle = f.keyword.trim().toLowerCase();
            if (!((a.name || '').toLowerCase().includes(needle)
              || (a.description || '').toLowerCase().includes(needle))) return false;
          }
          if (f.tag && !(a.tags || []).includes(f.tag)) return false;
          if (f.skillId && !(a.skillIds || []).includes(f.skillId)) return false;
          if (f.status && a.status !== f.status) return false;
          if (f.visibility && a.visibility !== f.visibility) return false;
          return true;
        });
      },
      dialogTitle() {
        return this.editingId ? '编辑智能体' : '新建智能体';
      },
    },
    methods: {
      emptyForm() {
        return {
          name: '', avatar: '🤖', description: '', tagInput: '',
          skillIds: [], visibility: 'PUBLIC', status: 'OFFLINE',
        };
      },
      async reload() {
        this.loading = true;
        try {
          const [agents, meta, skills, active] = await Promise.all([
            api('/api/admin/agents'),
            api('/api/admin/agents/meta'),
            api('/api/admin/agents/skills'),
            api('/api/admin/agents/active'),
          ]);
          this.agents = agents || [];
          this.meta = meta || { writable: false, location: '', tags: [] };
          this.skills = skills || [];
          this.active = active || { agentId: '', name: '', configured: '', available: true };
        } catch (error) {
          showError(error);
        } finally {
          this.loading = false;
        }
        await this.loadStats();
      },
      async loadStats() {
        this.statsLoading = true;
        try {
          const stats = await api('/api/admin/agent-stats');
          this.stats = stats || { window_days: 0, cached: false, record_count: 0, feedback_count: 0, agents: [] };
        } catch (error) {
          showError(error);
        } finally {
          this.statsLoading = false;
        }
      },
      openCreate() {
        this.editingId = null;
        this.form = this.emptyForm();
        this.dialogVisible = true;
      },
      openEdit(agent) {
        this.editingId = agent.id;
        this.form = {
          name: agent.name,
          avatar: agent.avatar || '🤖',
          description: agent.description || '',
          tagInput: (agent.tags || []).join('，'),
          skillIds: [...(agent.skillIds || [])],
          visibility: agent.visibility,
          status: agent.status,
        };
        this.dialogVisible = true;
      },
      parseTags() {
        return this.form.tagInput.split(/[,，、\s]+/)
          .map((t) => t.trim()).filter(Boolean).slice(0, 6);
      },
      async save() {
        if (!this.form.name.trim()) {
          toast('名称必填', 'error');
          return;
        }
        this.saving = true;
        try {
          const body = {
            id: this.editingId,
            name: this.form.name,
            avatar: this.form.avatar,
            description: this.form.description,
            tags: this.parseTags(),
            skillIds: this.form.skillIds,
            visibility: this.form.visibility,
            status: this.form.status,
          };
          if (this.editingId) {
            await api(`/api/admin/agents/${this.editingId}`, { method: 'PUT', body });
          } else {
            await api('/api/admin/agents', { method: 'POST', body });
          }
          toast('已保存', 'success');
          this.dialogVisible = false;
          await this.reload();
        } catch (error) {
          showError(error);
        } finally {
          this.saving = false;
        }
      },
      async remove(agent) {
        if (!window.confirm(`确认删除智能体「${agent.name}」？`)) return;
        try {
          await api(`/api/admin/agents/${agent.id}`, { method: 'DELETE' });
          toast('已删除', 'success');
          await this.reload();
        } catch (error) {
          showError(error);
        }
      },
      isActive(agent) {
        return agent.id === this.active.agentId;
      },
      canActivate(agent) {
        return this.meta.writable
          && !this.isActive(agent)
          && agent.status === 'ONLINE'
          && agent.visibility === 'PUBLIC';
      },
      async activate(agent) {
        this.activating = true;
        try {
          const result = await api(`/api/admin/agents/${agent.id}/active`, { method: 'PUT' });
          const cleared = result && result.clearedFlowId;
          toast(
            `已将「${agent.name}」设为核算智能体`
              + (cleared ? `，并取消默认流程 ${cleared}` : '')
              + '，下一轮对话生效',
            'success',
          );
          await this.reload();
        } catch (error) {
          showError(error);
        } finally {
          this.activating = false;
        }
      },
      async clearActive() {
        if (!this.meta.writable || !this.active.configured) return;
        if (!window.confirm('取消全局核算智能体？下一轮对话回到内置三段流水线。')) return;
        this.clearing = true;
        try {
          await api('/api/admin/agents/active', { method: 'DELETE' });
          toast('已取消核算智能体，下一轮对话生效', 'success');
          await this.reload();
        } catch (error) {
          showError(error);
        } finally {
          this.clearing = false;
        }
      },
      statusText(status) {
        return status === 'ONLINE' ? '已上架' : '已下架';
      },
      roleText(role) {
        const labels = {
          CALCULATOR: '计算', VALIDATOR: '复核', ACCOUNTANT: '入账',
          QUERY: '查询', KNOWLEDGE: '知识',
        };
        return labels[role] || role || '—';
      },
      percent(value) {
        return `${(Number(value || 0) * 100).toFixed(1)}%`;
      },
      scoreText(value) {
        return Number(value || 0).toFixed(1);
      },
      latencyText(value) {
        const ms = Number(value || 0);
        return ms > 0 ? `${(ms / 1000).toFixed(1)}s` : '—';
      },
      statsHint() {
        const s = this.stats;
        const cache = s.cached ? ' · 缓存' : '';
        return `窗口 ${s.window_days || 0} 天 · 样本 ${s.record_count || 0} · 反馈 ${s.feedback_count || 0}${cache}`;
      },
      visibilityText(v) {
        return v === 'PUBLIC' ? '公开' : '私有';
      },
      formatTime(value) {
        return value ? String(value).replace('T', ' ').slice(0, 16) : '';
      },
      avatarMarkup(value) {
        return window.payrollAvatarMarkup
          ? window.payrollAvatarMarkup(value)
          : (value || '🤖');
      },
      avatarOptions() {
        return window.PAYROLL_AVATAR_OPTIONS || [];
      },
    },
    template: `
      <div class="card">
        <div class="panel-head">
          <h2>Agent 管理</h2>
          <div class="actions">
            <span class="hint">{{ storageText }}</span>
            <span class="hint agent-active-hint"
                  :class="{ warn: active.configured && active.available === false }">{{ activeText }}</span>
            <button v-if="active.configured" type="button" @click="clearActive"
                    :disabled="!meta.writable || clearing">
              {{ clearing ? '取消中…' : '取消核算智能体' }}
            </button>
            <button type="button" @click="reload">刷新</button>
            <button type="button" class="primary" @click="openCreate" :disabled="!meta.writable">新建智能体</button>
          </div>
        </div>

        <div class="agent-filters">
          <input v-model="filters.keyword" placeholder="搜索名称/描述">
          <select v-model="filters.tag">
            <option value="">全部标签</option>
            <option v-for="tag in meta.tags" :key="tag" :value="tag">{{ tag }}</option>
          </select>
          <select v-model="filters.skillId">
            <option value="">全部技能</option>
            <option v-for="skill in skills" :key="skill.id" :value="skill.id">{{ skill.id }}</option>
          </select>
          <select v-model="filters.status">
            <option value="">全部状态</option>
            <option value="ONLINE">已上架</option>
            <option value="OFFLINE">已下架</option>
          </select>
          <select v-model="filters.visibility">
            <option value="">全部范围</option>
            <option value="PUBLIC">公开</option>
            <option value="PRIVATE">私有</option>
          </select>
        </div>

        <p v-if="loading" class="hint">加载中…</p>
        <p v-else-if="!filteredAgents.length" class="hint">没有匹配的智能体</p>

        <div class="agent-grid">
          <div v-for="agent in filteredAgents" :key="agent.id" class="agent-card">
            <div class="agent-card-head">
              <span class="agent-avatar" v-html="avatarMarkup(agent.avatar)"></span>
              <div class="agent-title">
                <strong>{{ agent.name }}</strong>
                <span class="hint">{{ visibilityText(agent.visibility) }} · {{ statusText(agent.status) }}</span>
              </div>
              <span v-if="isActive(agent)" class="agent-active-badge">当前核算</span>
            </div>
            <div class="agent-tags">
              <span v-for="tag in agent.tags" :key="tag" class="tag">{{ tag }}</span>
              <span class="tag skill">技能 {{ (agent.skillIds || []).length }}</span>
            </div>
            <p class="agent-desc">{{ agent.description || '（无描述）' }}</p>
            <p class="hint">更新于 {{ formatTime(agent.updatedAt) }} · 创建人 {{ agent.createdBy || '-' }}</p>
            <div class="actions">
              <button type="button" class="tiny" @click="activate(agent)" :disabled="!canActivate(agent) || activating">
                {{ isActive(agent) ? '核算中' : '设为核算智能体' }}
              </button>
              <button type="button" class="tiny" @click="openEdit(agent)" :disabled="!meta.writable">编辑</button>
              <button type="button" class="tiny danger" @click="remove(agent)" :disabled="!meta.writable">删除</button>
            </div>
          </div>
        </div>
      </div>

      <div class="card agent-stats-card">
        <div class="panel-head">
          <h2>智能体评分榜</h2>
          <div class="actions">
            <span class="hint">{{ statsHint() }}</span>
            <button type="button" @click="loadStats" :disabled="statsLoading">
              {{ statsLoading ? '加载中…' : '刷新统计' }}
            </button>
          </div>
        </div>
        <p class="hint">综合分 = 客观轨迹 60% + 用户反馈 40%，再按样本量向角色先验收缩；样本少于 5 条标记冷启动。</p>
        <p v-if="statsLoading && !stats.agents.length" class="hint">加载中…</p>
        <p v-else-if="!stats.agents.length" class="hint">还没有运行样本</p>
        <div v-else class="table-wrap">
          <table class="agent-stats-table">
            <thead>
              <tr>
                <th>智能体</th>
                <th>角色</th>
                <th class="num">样本</th>
                <th class="num">综合分</th>
                <th class="num">客观分 O</th>
                <th class="num">反馈分 F</th>
                <th class="num">成功率下界</th>
                <th class="num">工具下界</th>
                <th class="num">复核下界</th>
                <th class="num">P50 时延</th>
                <th class="num">稳定性</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in stats.agents" :key="row.agent_id">
                <td>
                  <strong>{{ row.name }}</strong>
                  <span v-if="row.name !== row.agent_id" class="hint"> · {{ row.agent_id }}</span>
                </td>
                <td>{{ roleText(row.role) }}</td>
                <td class="num">
                  {{ row.samples }}
                  <span v-if="row.cold_start" class="tag cold">冷启动</span>
                </td>
                <td class="num">
                  <strong>{{ scoreText(row.final_score) }}</strong>
                  <span class="hint">先验 {{ scoreText(row.prior_score) }}</span>
                </td>
                <td class="num">{{ scoreText(row.objective_score) }}</td>
                <td class="num">
                  {{ scoreText(row.feedback_score) }}
                  <span class="hint">👍{{ row.up_votes }} 👎{{ row.down_weight }}</span>
                </td>
                <td class="num">{{ row.samples ? percent(row.success_lower_bound) : '—' }}</td>
                <td class="num">{{ row.samples ? percent(row.tool_ok_lower_bound) : '—' }}</td>
                <td class="num">{{ row.samples ? percent(row.validation_lower_bound) : '—' }}</td>
                <td class="num">{{ latencyText(row.p50_latency_ms) }}</td>
                <td class="num">{{ row.samples ? percent(row.stability_score) : '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="dialogVisible" class="agent-modal-mask" @click.self="dialogVisible = false">
        <div class="agent-modal card">
          <h2>{{ dialogTitle }}</h2>
          <div class="grid">
            <label>名称<input v-model="form.name" maxlength="30" required></label>
            <div class="span-2 avatar-field">
              <span class="field-label">头像</span>
              <div class="avatar-preview-row">
                <span class="agent-avatar avatar-preview" v-html="avatarMarkup(form.avatar)"></span>
                <div class="avatar-custom">
                  <input v-model="form.avatar" maxlength="40" placeholder="自定义 Emoji，或从下方图标中选择">
                  <span class="hint">内置 Ant Design 图标，离线可用</span>
                </div>
              </div>
              <div class="avatar-grid">
                <button v-for="item in avatarOptions()" :key="item.value" type="button"
                  class="avatar-option" :class="{ active: form.avatar === item.value }"
                  :title="item.label" @click="form.avatar = item.value">
                  <span class="agent-avatar" v-html="item.svg"></span>
                </button>
              </div>
            </div>
            <label>可见范围
              <select v-model="form.visibility">
                <option value="PUBLIC">公开</option>
                <option value="PRIVATE">私有</option>
              </select>
            </label>
            <label>状态
              <select v-model="form.status">
                <option value="ONLINE">上架</option>
                <option value="OFFLINE">下架</option>
              </select>
            </label>
            <label class="span-2">标签（逗号分隔，最多 6 个）<input v-model="form.tagInput" placeholder="薪酬，核算"></label>
            <label class="span-2">描述<textarea v-model="form.description" rows="3" maxlength="200"></textarea></label>
          </div>
          <h3>绑定技能</h3>
          <div class="agent-skill-grid">
            <label v-for="skill in skills" :key="skill.id" class="agent-skill-item">
              <input type="checkbox" :value="skill.id" v-model="form.skillIds">
              <span>{{ skill.id }}</span>
              <span class="hint">{{ skill.source }}</span>
            </label>
          </div>
          <div class="actions">
            <button type="button" class="primary" @click="save" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
            <button type="button" @click="dialogVisible = false">取消</button>
          </div>
        </div>
      </div>
    `,
  };

  agentsVm = createApp(App).mount('#agents-app');
  agentsVm.reload();
}

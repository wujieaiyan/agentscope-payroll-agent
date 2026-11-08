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
        agents: [],
        skills: [],
        meta: { writable: false, location: '', tags: [] },
        active: { agentId: '', name: '', configured: '' },
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
        return this.active.name
          ? `核算智能体：${this.active.name}`
          : '核算智能体：默认流水线';
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
          this.active = active || { agentId: '', name: '', configured: '' };
        } catch (error) {
          showError(error);
        } finally {
          this.loading = false;
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
          await api(`/api/admin/agents/${agent.id}/active`, { method: 'PUT' });
          toast(`已将「${agent.name}」设为核算智能体，下一轮对话生效`, 'success');
          await this.reload();
        } catch (error) {
          showError(error);
        } finally {
          this.activating = false;
        }
      },
      statusText(status) {
        return status === 'ONLINE' ? '已上架' : '已下架';
      },
      visibilityText(v) {
        return v === 'PUBLIC' ? '公开' : '私有';
      },
      formatTime(value) {
        return value ? String(value).replace('T', ' ').slice(0, 16) : '';
      },
    },
    template: `
      <div class="card">
        <div class="panel-head">
          <h2>Agent 管理</h2>
          <div class="actions">
            <span class="hint">{{ storageText }}</span>
            <span class="hint agent-active-hint">{{ activeText }}</span>
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
              <span class="agent-avatar">{{ agent.avatar || '🤖' }}</span>
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

      <div v-if="dialogVisible" class="agent-modal-mask" @click.self="dialogVisible = false">
        <div class="agent-modal card">
          <h2>{{ dialogTitle }}</h2>
          <div class="grid">
            <label>名称<input v-model="form.name" maxlength="30" required></label>
            <label>头像（Emoji）<input v-model="form.avatar" maxlength="8"></label>
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

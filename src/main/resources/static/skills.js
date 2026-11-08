/* 技能管理页：直接读写 Nacos 的 <skill-id>.md，复用 app.js 的 api()/toast()/showError()。 */
let skillsVm = null;

function initSkillsTab() {
  if (skillsVm) {
    skillsVm.reload();
    return;
  }
  if (typeof Vue === 'undefined') {
    showError(new Error('Vue 资源未加载，无法打开技能管理'));
    return;
  }
  const { createApp } = Vue;

  const App = {
    data() {
      return {
        loading: false,
        saving: false,
        deleting: false,
        meta: { writable: false, location: '', skills: [] },
        keyword: '',
        editing: false,
        isNew: false,
        currentId: null,
        source: '',
        builtin: false,
        form: { id: '', description: '', content: '' },
      };
    },
    computed: {
      storageText() {
        return this.meta.writable
          ? `存储：Nacos ${this.meta.location}`
          : '存储：代码内置（未启用 Nacos，只读）';
      },
      filteredSkills() {
        const needle = this.keyword.trim().toLowerCase();
        if (!needle) return this.meta.skills || [];
        return (this.meta.skills || []).filter((skill) => {
          return (skill.id || '').toLowerCase().includes(needle)
            || (skill.description || '').toLowerCase().includes(needle);
        });
      },
      editorTitle() {
        return this.isNew ? '新增技能' : '编辑技能';
      },
      deletable() {
        return this.meta.writable && !this.isNew && this.source === 'nacos';
      },
      canSave() {
        return this.meta.writable && !this.saving && this.form.id.trim() && this.form.content.trim();
      },
    },
    methods: {
      async reload(selectId) {
        this.loading = true;
        try {
          const data = await api('/api/admin/skills');
          this.meta = data || { writable: false, location: '', skills: [] };
          const wanted = selectId || this.currentId;
          const exists = (this.meta.skills || []).some((skill) => skill.id === wanted);
          if (wanted && exists) {
            await this.open(wanted);
          } else {
            if (wanted) this.closeEditor();
            if (!this.editing && this.meta.skills && this.meta.skills.length) {
              await this.open(this.meta.skills[0].id);
            }
          }
        } catch (error) {
          showError(error);
        } finally {
          this.loading = false;
        }
      },
      async open(id) {
        try {
          const detail = await api(`/api/admin/skills/${encodeURIComponent(id)}`);
          this.currentId = detail.id;
          this.isNew = false;
          this.editing = true;
          this.source = detail.source;
          this.builtin = Boolean(detail.builtin);
          this.form = {
            id: detail.id,
            description: detail.description || '',
            content: detail.content || '',
          };
        } catch (error) {
          showError(error);
        }
      },
      openCreate() {
        this.currentId = null;
        this.isNew = true;
        this.editing = true;
        this.source = '';
        this.builtin = false;
        this.form = { id: '', description: '', content: '' };
      },
      closeEditor() {
        this.editing = false;
        this.isNew = false;
        this.currentId = null;
      },
      async save() {
        const id = this.form.id.trim();
        if (!/^[a-z][a-z0-9-]{1,63}$/.test(id)) {
          toast('技能 ID 只能包含小写字母、数字和短横线，且以小写字母开头（2–64 位）', 'error');
          return;
        }
        if (!this.form.content.trim()) {
          toast('提示词正文必填', 'error');
          return;
        }
        this.saving = true;
        try {
          const body = { description: this.form.description, content: this.form.content };
          if (this.isNew) {
            await api('/api/admin/skills', { method: 'POST', body: { id, ...body } });
            toast('技能已创建，下一轮对话生效', 'success');
          } else {
            await api(`/api/admin/skills/${encodeURIComponent(id)}`, { method: 'PUT', body });
            toast('技能已保存，下一轮对话生效', 'success');
          }
          await this.reload(id);
        } catch (error) {
          showError(error);
        } finally {
          this.saving = false;
        }
      },
      async remove() {
        if (!this.currentId) return;
        if (!window.confirm(`确认删除技能「${this.currentId}」？删除后不可恢复。`)) return;
        this.deleting = true;
        try {
          await api(`/api/admin/skills/${encodeURIComponent(this.currentId)}`, { method: 'DELETE' });
          toast('技能已删除', 'success');
          this.closeEditor();
          await this.reload();
        } catch (error) {
          showError(error);
        } finally {
          this.deleting = false;
        }
      },
      sourceText(skill) {
        if (skill.source === 'nacos') return skill.builtin ? '覆盖内置' : 'Nacos';
        return '内置';
      },
      sourceClass(skill) {
        return skill.source === 'nacos' ? 'nacos' : 'builtin';
      },
      formatTime(value) {
        if (!value) return '';
        const date = new Date(Number(value));
        if (Number.isNaN(date.getTime())) return '';
        const pad = (n) => String(n).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
          + `${pad(date.getHours())}:${pad(date.getMinutes())}`;
      },
    },
    template: `
      <div class="card">
        <div class="panel-head">
          <h2>技能管理</h2>
          <div class="actions">
            <span class="hint">{{ storageText }}</span>
            <button type="button" @click="reload()">刷新</button>
            <button type="button" class="primary" @click="openCreate" :disabled="!meta.writable">新增技能</button>
          </div>
        </div>
        <p class="hint">
          技能以 Nacos dataId &lt;id&gt;.md 存储于 payroll-agent 分组；保存后下一轮对话生效。
          内置技能可编辑覆盖，删除覆盖后回退代码内置提示词。
        </p>

        <div class="split">
          <div class="card list-card">
            <input v-model="keyword" placeholder="搜索 ID 或描述">
            <p v-if="loading" class="hint">加载中…</p>
            <p v-else-if="!filteredSkills.length" class="hint">没有匹配的技能</p>
            <ul v-else class="city-list skill-list">
              <li v-for="skill in filteredSkills" :key="skill.id"
                  :class="{ active: currentId === skill.id }" @click="open(skill.id)">
                <div class="skill-list-main">
                  <strong>{{ skill.id }}</strong>
                  <small>{{ skill.description || '—' }}</small>
                  <small v-if="skill.updatedAt">更新于 {{ formatTime(skill.updatedAt) }}</small>
                </div>
                <span class="skill-source" :class="sourceClass(skill)">{{ sourceText(skill) }}</span>
              </li>
            </ul>
          </div>

          <div class="card skill-editor">
            <p v-if="!editing" class="hint">从左侧选择技能进行编辑，或点击「新增技能」。</p>
            <template v-else>
              <div class="panel-head">
                <h2>{{ editorTitle }}</h2>
                <span v-if="!isNew" class="skill-source" :class="source === 'nacos' ? 'nacos' : 'builtin'">
                  {{ source === 'nacos' ? (builtin ? '覆盖内置' : 'Nacos') : '内置' }}
                </span>
              </div>
              <div class="grid">
                <label>技能 ID
                  <input v-model="form.id" :disabled="!isNew" maxlength="64"
                         placeholder="例如 payroll-auditor">
                </label>
                <label>描述（可选）<input v-model="form.description" maxlength="200"></label>
                <label class="span-2 skill-content-label">提示词正文
                  <textarea v-model="form.content" rows="18" maxlength="200000"
                            placeholder="## 角色&#10;你是……"></textarea>
                </label>
              </div>
              <div class="actions">
                <button type="button" class="primary" @click="save" :disabled="!canSave">
                  {{ saving ? '保存中…' : '保存技能' }}
                </button>
                <button type="button" class="danger" @click="remove" :disabled="!deletable || deleting">
                  {{ deleting ? '删除中…' : '删除技能' }}
                </button>
                <button type="button" @click="closeEditor">取消</button>
                <span class="hint">{{ form.content.length }} 字</span>
              </div>
              <p class="hint">保存会写入 Nacos，并统一生成 name/description frontmatter。</p>
            </template>
          </div>
        </div>
      </div>
    `,
  };

  skillsVm = createApp(App).mount('#skills-app');
  skillsVm.reload();
}

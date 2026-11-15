<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const data = ref({ writable: false, location: '-', skills: [] });
const keyword = ref('');
const loading = ref(false);
const detail = ref(null);
const detailLoading = ref(false);
const showForm = ref(false);
const editing = ref(false);
const saving = ref(false);
const form = reactive({ id: '', description: '', content: '', executionEnabled: false });

const tab = ref('body');
const files = ref([]);
const fileLoading = ref(false);
const showFileForm = ref(false);
const fileEditing = ref(false);
const fileSaving = ref(false);
const executionSaving = ref(false);
const fileForm = reactive({ path: '', originalPath: '', content: '' });

const skills = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  const list = data.value.skills || [];
  if (!needle) return list;
  return list.filter((skill) =>
    [skill.id, skill.description, skill.source]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle))
  );
});

const canEditFiles = computed(
  () => data.value.writable && detail.value && detail.value.source === 'nacos'
);
const referenceFiles = computed(() => files.value.filter((file) => file.kind !== 'script'));
const scriptFiles = computed(() => files.value.filter((file) => file.kind === 'script'));
const canToggleExecution = computed(
  () => canEditFiles.value && scriptFiles.value.length > 0 && !executionSaving.value
);
const activeFiles = computed(() =>
  tab.value === 'files-scripts' ? scriptFiles.value : referenceFiles.value
);
const activeKind = computed(() => (tab.value === 'files-scripts' ? 'script' : 'reference'));

function formatTimestamp(value) {
  if (!value) return '—';
  if (typeof value === 'number') return new Date(value).toLocaleString('zh-CN');
  return String(value).replace('T', ' ').slice(0, 16);
}

function formatSize(bytes) {
  if (!Number.isFinite(bytes)) return '—';
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;
}

function fileUrl(id, path) {
  const encoded = String(path).split('/').map(encodeURIComponent).join('/');
  return `/api/admin/skills/${encodeURIComponent(id)}/files/${encoded}`;
}

async function load() {
  loading.value = true;
  try {
    data.value = (await api('/api/admin/skills')) || data.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function openSkill(skill) {
  detailLoading.value = true;
  tab.value = 'body';
  files.value = [];
  detail.value = { ...skill, content: '', loaded: false };
  try {
    detail.value = await api(`/api/admin/skills/${encodeURIComponent(skill.id)}`);
    detail.value.loaded = true;
    files.value = detail.value.files || [];
  } catch (error) {
    detail.value = null;
    toast(error.message, 'error');
  } finally {
    detailLoading.value = false;
  }
}

function createSkill() {
  editing.value = false;
  Object.assign(form, { id: '', description: '', content: '', executionEnabled: false });
  showForm.value = true;
}

function editSkill(skill) {
  editing.value = true;
  Object.assign(form, {
    id: skill.id,
    description: skill.description || '',
    content: skill.content || '',
    executionEnabled: Boolean(skill.executionEnabled ?? skill.execution_enabled),
  });
  showForm.value = true;
}

async function editSkillById(skill) {
  await openSkill(skill);
  if (detail.value && detail.value.loaded) editSkill(detail.value);
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    const body = {
      id: form.id.trim(),
      description: form.description.trim(),
      content: form.content,
      executionEnabled: form.executionEnabled,
    };
    if (editing.value) {
      await api(`/api/admin/skills/${encodeURIComponent(body.id)}`, { method: 'PUT', body });
    } else {
      await api('/api/admin/skills', { method: 'POST', body });
    }
    toast('技能已保存', 'success');
    showForm.value = false;
    detail.value = null;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function remove(skill) {
  if (!window.confirm(`删除技能「${skill.id}」？`)) return;
  try {
    await api(`/api/admin/skills/${encodeURIComponent(skill.id)}`, { method: 'DELETE' });
    if (detail.value && detail.value.id === skill.id) detail.value = null;
    toast('技能已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function toggleExecution(enabled) {
  if (!canToggleExecution.value) return;
  executionSaving.value = true;
  try {
    const id = detail.value.id;
    await api(`/api/admin/skills/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: {
        id,
        description: detail.value.description || '',
        content: detail.value.content,
        executionEnabled: enabled,
      },
    });
    detail.value.executionEnabled = enabled;
    toast(enabled ? '已开启脚本执行' : '已关闭脚本执行', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    executionSaving.value = false;
  }
}

function startAddFile() {
  fileEditing.value = false;
  Object.assign(fileForm, {
    path: activeKind.value === 'script' ? 'scripts/' : 'references/',
    originalPath: '',
    content: '',
  });
  showFileForm.value = true;
}

async function startEditFile(file) {
  if (fileLoading.value) return;
  fileLoading.value = true;
  try {
    const payload = await api(fileUrl(detail.value.id, file.path));
    fileEditing.value = true;
    Object.assign(fileForm, {
      path: payload.path || file.path,
      originalPath: file.path,
      content: payload.content || '',
    });
    showFileForm.value = true;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    fileLoading.value = false;
  }
}

async function saveFile() {
  if (fileSaving.value) return;
  const id = detail.value.id;
  const path = fileForm.path.trim();
  if (!path) {
    toast('文件路径必填', 'error');
    return;
  }
  fileSaving.value = true;
  try {
    // 文件接口是整包替换，先把未改动的文件正文读回来。
    const all = [];
    for (const file of files.value) {
      if (file.path === path) continue;
      if (fileEditing.value && file.path === fileForm.originalPath) continue;
      const payload = await api(fileUrl(id, file.path));
      all.push({ path: file.path, content: payload.content || '' });
    }
    all.push({ path, content: fileForm.content });
    const result = await api(`/api/admin/skills/${encodeURIComponent(id)}/files`, {
      method: 'PUT',
      body: { files: all },
    });
    files.value = result.files || [];
    detail.value.files = files.value;
    showFileForm.value = false;
    toast('文件已保存', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    fileSaving.value = false;
  }
}

async function removeFile(file) {
  if (!window.confirm(`删除文件「${file.path}」？`)) return;
  try {
    await api(fileUrl(detail.value.id, file.path), { method: 'DELETE' });
    files.value = files.value.filter((item) => item.path !== file.path);
    detail.value.files = files.value;
    toast('文件已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>技能</h2>
        <p>技能正文以 Markdown 形式存入 {{ data.location || 'Nacos' }}，覆盖内置技能后仍可恢复；被智能体引用时不可删除。</p>
      </div>
      <div class="toolbar">
        <span class="badge" :class="data.writable ? 'green' : 'orange'">
          {{ data.writable ? '可写' : '只读（Nacos 未启用）' }}
        </span>
        <input v-model="keyword" placeholder="搜索技能" style="width: 220px" />
        <button class="primary" :disabled="!data.writable" @click="createSkill">新增技能</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>技能 ID</th>
            <th>说明</th>
            <th>来源</th>
            <th>类型</th>
            <th>长度</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="skill in skills" :key="skill.id">
            <td class="mono">{{ skill.id }}</td>
            <td class="muted">{{ skill.description }}</td>
            <td>{{ skill.source }}</td>
            <td>
              <span class="badge" :class="skill.builtin ? 'blue' : 'green'">
                {{ skill.builtin ? '内置' : '自定义' }}
              </span>
              <span v-if="skill.overridden" class="badge orange">已覆盖</span>
              <span v-if="skill.fileCount" class="badge blue">{{ skill.fileCount }} 个文件</span>
            </td>
            <td>{{ skill.contentLength ?? skill.content_length ?? '—' }}</td>
            <td>{{ formatTimestamp(skill.updatedAt ?? skill.updated_at) }}</td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" @click="openSkill(skill)">查看</button>
                <button class="subtle sm" :disabled="!data.writable" @click="editSkillById(skill)">
                  编辑
                </button>
                <button class="subtle sm" :disabled="!data.writable" @click="remove(skill)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!skills.length">
            <td colspan="7" class="empty">{{ loading ? '加载中…' : '暂无技能' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>{{ detail.id }}</h3>
          <button class="subtle sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="detailLoading" class="empty">加载中…</div>
          <template v-else>
            <div class="tabs">
              <button :class="{ active: tab === 'body' }" @click="tab = 'body'">正文</button>
              <button
                :class="{ active: tab === 'files-references' }"
                @click="tab = 'files-references'"
              >
                参考文件 <span class="badge blue">{{ referenceFiles.length }}</span>
              </button>
              <button :class="{ active: tab === 'files-scripts' }" @click="tab = 'files-scripts'">
                脚本文件 <span class="badge blue">{{ scriptFiles.length }}</span>
              </button>
            </div>

            <template v-if="tab === 'body'">
              <div class="panel-row"><span class="k">说明</span><span class="v">{{ detail.description || '—' }}</span></div>
              <div class="panel-row"><span class="k">来源</span><span class="v">{{ detail.source || '—' }}</span></div>
              <div class="panel-row">
                <span class="k">类型</span>
                <span class="v">
                  {{ detail.builtin ? '内置' : '自定义' }}
                  <span v-if="detail.overridden">· 已覆盖</span>
                </span>
              </div>
              <pre class="trace-pre" style="max-height: none">{{ detail.content || '（空）' }}</pre>
            </template>

            <template v-else>
              <div class="exec-row">
                <div>
                  <strong>允许对话执行脚本</strong>
                  <p class="file-hint">
                    开启后模型可提交 <code>scripts/</code> 下的脚本；每次执行仍需用户在确认卡片确认。
                    脚本在禁网、只读、非 root 的一次性容器中运行，最长 10 秒。
                  </p>
                </div>
                <label class="exec-switch">
                  <input
                    type="checkbox"
                    :checked="Boolean(detail.executionEnabled)"
                    :disabled="!canToggleExecution"
                    @change="toggleExecution($event.target.checked)"
                  />
                  <span>{{ detail.executionEnabled ? '已开启' : '已关闭' }}</span>
                </label>
              </div>
              <p v-if="!scriptFiles.length" class="file-hint warn">
                先上传至少一个脚本文件，才能开启执行。
              </p>
              <p class="file-hint">
                参考文件放 <code>references/</code>，脚本放 <code>scripts/</code>（仅 .py / .sh / .js）；
                单文件 ≤ 64KB、合计 ≤ 256KB、最多 20 个。对话中通过 <code>skill_read</code> 读取。
              </p>
              <div class="file-head">
                <span class="muted">{{ activeFiles.length }} 个文件</span>
                <button class="subtle sm" :disabled="!canEditFiles" @click="startAddFile">
                  新增文件
                </button>
              </div>
              <div v-for="file in activeFiles" :key="file.path" class="file-row">
                <span class="mono file-path">{{ file.path }}</span>
                <span class="muted file-size">{{ formatSize(file.size) }}</span>
                <div class="cell-actions">
                  <button class="subtle sm" @click="startEditFile(file)">编辑</button>
                  <button class="subtle sm" :disabled="!canEditFiles" @click="removeFile(file)">删除</button>
                </div>
              </div>
              <div v-if="!activeFiles.length" class="empty">暂无文件</div>
              <p v-if="detail.source !== 'nacos'" class="file-hint warn">
                内置技能尚未覆盖到 Nacos，请先在「正文」页签编辑并保存，再上传参考文件或脚本。
              </p>
            </template>
          </template>
        </div>
        <div class="drawer-foot">
          <button class="ghost" @click="detail = null">关闭</button>
          <button class="primary" :disabled="!data.writable" @click="editSkill(detail)">编辑正文</button>
        </div>
      </aside>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width: min(860px, 96vw)">
        <div class="modal-head">
          <h3>{{ editing ? `编辑技能 ${form.id}` : '新增技能' }}</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              技能 ID
              <input v-model="form.id" :disabled="editing" placeholder="payroll-policy-notes" />
            </label>
            <label class="field">
              一句话说明
              <input v-model="form.description" placeholder="用于解释社保与公积金口径" />
            </label>
            <label class="field span-2">
              正文（Markdown）
              <textarea v-model="form.content" rows="16" class="mono"></textarea>
            </label>
            <label class="field span-2 exec-check">
              <span>
                <input v-model="form.executionEnabled" type="checkbox" />
                允许对话执行 scripts/ 下的脚本（需部署沙箱服务；执行仍需用户确认）
              </span>
            </label>
          </div>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showForm = false">取消</button>
          <button class="primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="showFileForm" class="modal-mask" @click.self="showFileForm = false">
      <div class="modal" style="width: min(860px, 96vw)">
        <div class="modal-head">
          <h3>{{ fileEditing ? `编辑文件 ${fileForm.originalPath}` : '新增文件' }}</h3>
          <button class="subtle sm" @click="showFileForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <label class="field">
            文件路径
            <input v-model="fileForm.path" class="mono" placeholder="references/policy.md" />
          </label>
          <label class="field">
            文件正文
            <textarea v-model="fileForm.content" rows="18" class="mono"></textarea>
          </label>
          <p class="file-hint">
            路径只能包含字母、数字、点、下划线、短横线和斜杠，只允许 <code>references/</code> 与
            <code>scripts/</code> 目录；脚本后缀仅支持 <code>.py</code> / <code>.sh</code> /
            <code>.js</code>。
          </p>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showFileForm = false">取消</button>
          <button class="primary" :disabled="fileSaving" @click="saveFile">
            {{ fileSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
}

.tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  background: transparent;
  padding: 8px 12px;
  color: var(--muted);
}

.tabs button.active {
  border-bottom-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}

.tabs .badge {
  margin-left: 4px;
}

.file-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.6;
}

.file-hint.warn {
  color: #d97b00;
}

.file-hint code {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.file-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.file-row + .file-row {
  margin-top: 8px;
}

.file-path {
  flex: 1;
  word-break: break-all;
}

.file-size {
  flex: 0 0 auto;
  font-size: 12px;
}

.exec-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.exec-row + .file-hint {
  margin-top: 10px;
}

.exec-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--muted);
}

.exec-check input[type='checkbox'] {
  height: auto;
  margin-right: 6px;
}
</style>

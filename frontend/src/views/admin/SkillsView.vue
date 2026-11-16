<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
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

const showImport = ref(false);
const importMode = ref('git');
const importing = ref(false);
const previewing = ref(false);
const importPreview = ref(null);
const importForm = reactive({
  url: '',
  ref: '',
  id: '',
  overwrite: false,
  file: null,
  fileName: '',
});

const dataState = ref(null);
const dataLoading = ref(false);
const dataBusy = ref(false);
let dataTimer = null;

const dataStatusLabel = computed(() => {
  const status = dataState.value?.status;
  const labels = {
    READY: '已就绪',
    INITIALIZING: '初始化中',
    FAILED: '初始化失败',
    UNKNOWN: '未初始化',
    BUSY: '沙箱繁忙',
    UNAVAILABLE: '沙箱不可用',
    INVALID: '请求无效',
  };
  return labels[status] || '未知';
});

const dataStatusClass = computed(() => {
  const status = dataState.value?.status;
  if (status === 'READY') return 'green';
  if (status === 'FAILED') return 'red';
  if (status === 'INITIALIZING' || status === 'UNKNOWN') return 'orange';
  return 'blue';
});

const metadataEntries = computed(() => Object.entries(detail.value?.metadata || {}));

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
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
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
  stopDataPolling();
  detailLoading.value = true;
  tab.value = 'body';
  files.value = [];
  dataState.value = null;
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

function closeDetail() {
  stopDataPolling();
  detail.value = null;
}

function setTab(value) {
  tab.value = value;
  if (value === 'data') loadData(false);
}

function formatMetaValue(value) {
  if (value === null || value === undefined) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function stopDataPolling() {
  if (dataTimer) {
    clearInterval(dataTimer);
    dataTimer = null;
  }
}

function startDataPolling() {
  stopDataPolling();
  dataTimer = setInterval(() => loadData(true), 2500);
}

async function loadData(silent) {
  const id = detail.value?.id;
  if (!id) return;
  if (!silent) dataLoading.value = true;
  try {
    const payload = await api(`/api/admin/skills/${encodeURIComponent(id)}/data`);
    if (detail.value?.id !== id) return;
    dataState.value = payload;
    if (payload?.status === 'INITIALIZING') startDataPolling();
    else stopDataPolling();
  } catch (error) {
    if (!silent) toast(error.message, 'error');
  } finally {
    if (!silent) dataLoading.value = false;
  }
}

async function initData() {
  const id = detail.value?.id;
  if (!id || dataBusy.value) return;
  const overwrite = dataState.value?.status === 'READY';
  if (
    overwrite &&
    !window.confirm('重建会清空当前技能数据并重新执行 setup，确认继续？')
  ) {
    return;
  }
  dataBusy.value = true;
  try {
    const payload = await api(`/api/admin/skills/${encodeURIComponent(id)}/data/init`, {
      method: 'POST',
      body: { overwrite },
    });
    dataState.value = payload;
    toast('已提交初始化，完成后可刷新状态', 'success');
    if (payload?.status === 'INITIALIZING') startDataPolling();
    else await loadData(true);
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    dataBusy.value = false;
  }
}

async function removeData() {
  const id = detail.value?.id;
  if (!id || dataBusy.value) return;
  if (!window.confirm('删除技能数据？data/ 目录会被清空，之后需要重新初始化。')) return;
  dataBusy.value = true;
  try {
    dataState.value = await api(`/api/admin/skills/${encodeURIComponent(id)}/data`, {
      method: 'DELETE',
    });
    stopDataPolling();
    toast('技能数据已删除', 'success');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    dataBusy.value = false;
  }
}

function startImport() {
  importMode.value = 'git';
  Object.assign(importForm, {
    url: '',
    ref: '',
    id: '',
    overwrite: false,
    file: null,
    fileName: '',
  });
  importPreview.value = null;
  showImport.value = true;
}

function pickImportFile(event) {
  const file = (event.target.files || [])[0] || null;
  importForm.file = file;
  importForm.fileName = file ? file.name : '';
}

async function postZipImport(dryRun) {
  const body = new FormData();
  body.append('file', importForm.file);
  if (importForm.id.trim()) body.append('id', importForm.id.trim());
  body.append('overwrite', String(Boolean(importForm.overwrite)));
  body.append('dryRun', String(Boolean(dryRun)));
  const response = await fetch('/api/admin/skills/import/zip', {
    method: 'POST',
    body,
    credentials: 'same-origin',
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    throw new Error((payload && payload.message) || `导入失败：HTTP ${response.status}`);
  }
  return payload ? payload.data : null;
}

function normalizeImportPreview(payload) {
  if (!payload) return payload;
  return {
    ...payload,
    setupCommand: payload.setupCommand ?? payload.setup_command ?? null,
    dataMaxBytes: payload.dataMaxBytes ?? payload.data_max_bytes ?? null,
  };
}

async function submitImport(dryRun) {
  const busy = dryRun ? previewing : importing;
  if (busy.value) return;
  if (importMode.value === 'git' && !importForm.url.trim()) {
    toast('请填写 Git 地址', 'error');
    return;
  }
  if (importMode.value === 'zip' && !importForm.file) {
    toast('请选择 ZIP 文件', 'error');
    return;
  }
  busy.value = true;
  try {
    const payload =
      importMode.value === 'git'
        ? await api('/api/admin/skills/import/git', {
            method: 'POST',
            body: {
              url: importForm.url.trim(),
              ref: importForm.ref.trim() || null,
              id: importForm.id.trim() || null,
              overwrite: Boolean(importForm.overwrite),
              dryRun: Boolean(dryRun),
            },
          })
        : await postZipImport(dryRun);
    importPreview.value = normalizeImportPreview(payload);
    if (!dryRun) {
      toast(`技能「${payload?.id}」已导入`, 'success');
      showImport.value = false;
      await load();
      const saved = (data.value.skills || []).find((item) => item.id === payload?.id);
      if (saved) await openSkill(saved);
    }
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    busy.value = false;
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
onUnmounted(stopDataPolling);
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
        <button class="ghost" :disabled="!data.writable" @click="startImport">导入技能</button>
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

    <div v-if="detail" class="drawer-mask" @click.self="closeDetail">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>{{ detail.id }}</h3>
          <button class="subtle sm" @click="closeDetail">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="detailLoading" class="empty">加载中…</div>
          <template v-else>
            <div class="tabs">
              <button :class="{ active: tab === 'body' }" @click="setTab('body')">正文</button>
              <button
                :class="{ active: tab === 'files-references' }"
                @click="setTab('files-references')"
              >
                参考文件 <span class="badge blue">{{ referenceFiles.length }}</span>
              </button>
              <button :class="{ active: tab === 'files-scripts' }" @click="setTab('files-scripts')">
                脚本文件 <span class="badge blue">{{ scriptFiles.length }}</span>
              </button>
              <button :class="{ active: tab === 'data' }" @click="setTab('data')">数据</button>
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
              <div v-if="metadataEntries.length" class="panel-row">
                <span class="k">元信息</span>
                <div class="v meta-list">
                  <div v-for="[key, value] in metadataEntries" :key="key" class="meta-item">
                    <span class="mono">{{ key }}</span>
                    <span class="muted">{{ formatMetaValue(value) }}</span>
                  </div>
                </div>
              </div>
              <pre class="trace-pre" style="max-height: none">{{ detail.content || '（空）' }}</pre>
            </template>

            <template v-else-if="tab === 'data'">
              <div v-if="dataLoading" class="empty">加载中…</div>
              <template v-else-if="dataState">
                <div class="panel-row">
                  <span class="k">状态</span>
                  <span class="v">
                    <span class="badge" :class="dataStatusClass">{{ dataStatusLabel }}</span>
                    <span v-if="dataState.message" class="muted"> · {{ dataState.message }}</span>
                  </span>
                </div>
                <div class="panel-row">
                  <span class="k">初始化脚本</span>
                  <span class="v mono">{{ dataState.setupPath || '—' }}</span>
                </div>
                <div class="panel-row">
                  <span class="k">数据大小</span>
                  <span class="v">{{ formatSize(dataState.sizeBytes) }}</span>
                </div>
                <div class="panel-row">
                  <span class="k">最后初始化</span>
                  <span class="v">
                    {{ formatTimestamp(dataState.finishedAt || dataState.startedAt) }}
                  </span>
                </div>
                <p v-if="!dataState.requiresData" class="file-hint warn">
                  该技能未声明 <code>setup</code> 或 <code>setup.sh</code>，无需初始化数据。
                </p>
                <p class="file-hint">
                  setup 在独立初始化容器中执行（允许联网）；生成的 <code>data/</code>
                  会持久保留，并在脚本运行时只读挂载到容器内 <code>/data</code>。
                </p>
                <div class="data-actions">
                  <button
                    class="primary"
                    :disabled="dataBusy || !dataState.requiresData || dataState.status === 'INITIALIZING'"
                    @click="initData"
                  >
                    {{
                      dataBusy
                        ? '提交中…'
                        : dataState.status === 'READY'
                          ? '重建数据'
                          : dataState.status === 'FAILED'
                            ? '重新初始化'
                            : '初始化数据'
                    }}
                  </button>
                  <button
                    class="subtle"
                    :disabled="dataBusy || dataState.status === 'UNKNOWN' || dataState.status === 'INITIALIZING'"
                    @click="removeData"
                  >
                    删除数据
                  </button>
                  <button class="subtle" :disabled="dataLoading" @click="loadData(false)">
                    刷新状态
                  </button>
                </div>
                <template v-if="dataState.logTail">
                  <div class="file-head">
                    <span class="muted">初始化日志（尾部）</span>
                  </div>
                  <pre class="trace-pre" style="max-height: 260px">{{ dataState.logTail }}</pre>
                </template>
              </template>
              <div v-else class="empty">暂无数据状态</div>
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
          <button class="ghost" @click="closeDetail">关闭</button>
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

    <div v-if="showImport" class="modal-mask" @click.self="showImport = false">
      <div class="modal" style="width: min(760px, 96vw)">
        <div class="modal-head">
          <h3>导入技能项目</h3>
          <button class="subtle sm" @click="showImport = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="tabs">
            <button
              :class="{ active: importMode === 'git' }"
              @click="importMode = 'git'; importPreview = null"
            >
              Git 仓库
            </button>
            <button
              :class="{ active: importMode === 'zip' }"
              @click="importMode = 'zip'; importPreview = null"
            >
              ZIP 包
            </button>
          </div>
          <div class="form-grid">
            <template v-if="importMode === 'git'">
              <label class="field span-2">
                Git 地址（https）
                <input
                  v-model="importForm.url"
                  class="mono"
                  placeholder="https://github.com/Wscats/poetry-skills.git"
                />
              </label>
              <label class="field">
                分支或标签（可选）
                <input v-model="importForm.ref" placeholder="main" />
              </label>
            </template>
            <template v-else>
              <label class="field span-2">
                ZIP 文件
                <input type="file" accept=".zip" @change="pickImportFile" />
              </label>
            </template>
            <label class="field">
              技能 ID（可选）
              <input v-model="importForm.id" class="mono" placeholder="按 SKILL.md 自动生成" />
            </label>
            <label class="field span-2 exec-check">
              <span>
                <input v-model="importForm.overwrite" type="checkbox" />
                覆盖同名技能（内置技能也可覆盖）
              </span>
            </label>
          </div>
          <div v-if="importPreview" class="import-preview">
            <div class="panel-row">
              <span class="k">技能 ID</span>
              <span class="v mono">{{ importPreview.id }}</span>
            </div>
            <div class="panel-row">
              <span class="k">说明</span>
              <span class="v">{{ importPreview.description || '—' }}</span>
            </div>
            <div class="panel-row">
              <span class="k">文件</span>
              <span class="v">
                {{ importPreview.files?.length || 0 }} 个 ·
                {{
                  formatSize(
                    (importPreview.files || []).reduce((sum, file) => sum + (file.size || 0), 0)
                  )
                }}
              </span>
            </div>
            <div class="panel-row">
              <span class="k">setup</span>
              <span class="v mono">{{ importPreview.setupCommand || '—' }}</span>
            </div>
            <div class="panel-row">
              <span class="k">数据上限</span>
              <span class="v">{{ formatSize(importPreview.dataMaxBytes) }}</span>
            </div>
            <div v-if="importPreview.existing" class="file-hint warn">
              同名技能已存在，导入将覆盖现有文件。
            </div>
            <div v-if="importPreview.warnings?.length" class="file-hint warn">
              {{ importPreview.warnings.join('；') }}
            </div>
          </div>
          <p class="file-hint">
            目录式项目需包含 <code>SKILL.md</code>；<code>setup.sh</code>、<code>scripts/</code>
            会随包导入，<code>data/</code> 不参与导入，由初始化脚本生成。
          </p>
        </div>
        <div class="modal-foot">
          <button class="ghost" :disabled="previewing || importing" @click="submitImport(true)">
            {{ previewing ? '解析中…' : '预览' }}
          </button>
          <button class="primary" :disabled="previewing || importing" @click="submitImport(false)">
            {{ importing ? '导入中…' : '导入' }}
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

.meta-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-item {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
}

.meta-item .mono {
  flex: 0 0 auto;
  min-width: 120px;
}

.data-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.import-preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface, #fafbfc);
}

.modal-body .tabs {
  margin-bottom: 4px;
}
</style>

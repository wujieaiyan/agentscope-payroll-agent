<script setup>
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../../../../api/http';
import { toast } from '../../../../stores/toast';
import { fileUrl, formatSize } from '../../../../utils/format';
import ConfirmDialog from '../../../../components/ui/ConfirmDialog.vue';
import EmptyState from '../../../../components/ui/EmptyState.vue';
import PanelCard from '../../../../components/ui/PanelCard.vue';

const props = defineProps({
  skillId: { type: String, required: true },
  detail: { type: Object, required: true },
  writable: { type: Boolean, default: false },
});
const emit = defineEmits(['changed']);

const router = useRouter();

const TEXT_EXTENSIONS = new Set(['md', 'txt', 'py', 'sh', 'js', 'json', 'yaml', 'yml', 'csv', 'toml', 'ini']);
const BINARY_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'pdf', 'zip', 'gz', 'tar', 'jar',
  'class', 'bin', 'xlsx', 'xls', 'docx', 'doc', 'pt', 'pkl', 'npy', 'onnx', 'parquet',
]);

const selectedPath = ref('');
const previewContent = ref('');
const previewBinary = ref(false);
const previewLoading = ref(false);
const previewError = ref('');
const busy = ref(false);
const deleteTarget = ref(null);
const uploadInput = ref(null);

const files = computed(() =>
  (props.detail?.files || []).filter((file) => file.path !== 'SKILL.md')
);
const selected = computed(
  () => files.value.find((file) => file.path === selectedPath.value) || null
);
const groups = computed(() => {
  const references = files.value.filter((file) => file.path.startsWith('references/'));
  const scripts = files.value.filter((file) => file.path.startsWith('scripts/'));
  const roots = files.value.filter(
    (file) =>
      !file.path.startsWith('references/') && !file.path.startsWith('scripts/')
  );
  return [
    { key: 'references', label: 'references/', files: references },
    { key: 'scripts', label: 'scripts/', files: scripts },
    roots.length ? { key: 'root', label: '根目录', files: roots } : null,
  ].filter((group) => group && group.files.length);
});
const isDb = computed(() => props.detail?.source === 'db');
const canEdit = computed(() => props.writable && isDb.value);
const readonlyHint = computed(() => {
  if (!props.writable) return '当前存储只读，文件编辑入口已禁用。';
  if (props.detail?.source !== 'db') {
    return '当前技能来源不是 db 源，文件包只在 db 源可写。';
  }
  return '';
});

function extensionOf(path) {
  const name = String(path).split('/').pop() || '';
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

function isBinary(path) {
  return BINARY_EXTENSIONS.has(extensionOf(path));
}

function iconText(path) {
  const extension = extensionOf(path);
  return extension ? extension.slice(0, 3).toUpperCase() : 'FILE';
}

function iconTone(path) {
  const extension = extensionOf(path);
  if (extension === 'md') return 't1';
  if (['py', 'js', 'sh'].includes(extension)) return 't2';
  if (['json', 'yaml', 'yml', 'toml', 'csv', 'ini'].includes(extension)) return 't3';
  return 't4';
}

function shortName(path) {
  const index = String(path).lastIndexOf('/');
  return index >= 0 ? path.slice(index + 1) : path;
}

async function selectFile(file) {
  if (!file) {
    selectedPath.value = '';
    previewContent.value = '';
    previewBinary.value = false;
    previewError.value = '';
    return;
  }
  selectedPath.value = file.path;
  previewContent.value = '';
  previewError.value = '';
  previewBinary.value = isBinary(file.path);
  if (previewBinary.value) return;
  previewLoading.value = true;
  try {
    const payload = await api(fileUrl(props.skillId, file.path));
    if (selectedPath.value !== file.path) return;
    previewContent.value = payload?.content || '';
  } catch (caught) {
    if (selectedPath.value === file.path) previewError.value = caught.message;
  } finally {
    previewLoading.value = false;
  }
}

function openEditor() {
  if (!canEdit.value || !selected.value) return;
  router.push({
    path: `/platform/skills/${encodeURIComponent(props.skillId)}/edit`,
    query: { path: selected.value.path },
  });
}

function askDelete() {
  if (!canEdit.value || !selected.value) return;
  deleteTarget.value = selected.value;
}

async function confirmDelete() {
  if (!deleteTarget.value || busy.value) return;
  const path = deleteTarget.value.path;
  busy.value = true;
  try {
    const revision = props.detail?.revision;
    const query = revision ? `?revision=${encodeURIComponent(revision)}` : '';
    await api(`${fileUrl(props.skillId, path)}${query}`, { method: 'DELETE' });
    toast(`${path} 已删除`, 'success');
    deleteTarget.value = null;
    selectedPath.value = '';
    previewContent.value = '';
    emit('changed');
  } catch (caught) {
    toast(caught.message, 'error');
  } finally {
    busy.value = false;
  }
}

function pickUpload() {
  if (!canEdit.value || busy.value) return;
  uploadInput.value?.click();
}

async function onUpload(event) {
  const file = event.target.files?.[0];
  event.target.value = '';
  if (!file) return;
  const name = file.name.replace(/[^A-Za-z0-9._-]/g, '-');
  const extension = extensionOf(name);
  if (!TEXT_EXTENSIONS.has(extension)) {
    toast('仅支持文本类文件：md / txt / py / sh / js / json / yaml / csv / toml / ini', 'error');
    return;
  }
  const path = ['py', 'sh', 'js'].includes(extension)
    ? `scripts/${name}`
    : `references/${name}`;
  if (files.value.some((item) => item.path === path)) {
    const confirmed = window.confirm(`已存在 ${path}，覆盖后不会保留旧内容，继续？`);
    if (!confirmed) return;
  }
  const content = await file.text();
  busy.value = true;
  try {
    const revision = props.detail?.revision;
    const query = revision ? `?revision=${encodeURIComponent(revision)}` : '';
    await api(`${fileUrl(props.skillId, path)}${query}`, {
      method: 'PUT',
      body: { content },
    });
    toast(`已保存 ${path}`, 'success');
    selectedPath.value = path;
    previewContent.value = content;
    previewBinary.value = false;
    previewError.value = '';
    emit('changed');
  } catch (caught) {
    toast(caught.message, 'error');
  } finally {
    busy.value = false;
  }
}

watch(
  () => props.detail?.id,
  () => {
    selectedPath.value = '';
    previewContent.value = '';
    previewBinary.value = false;
    previewError.value = '';
  }
);

watch(
  files,
  (list) => {
    if (!list.length) {
      selectedPath.value = '';
      previewContent.value = '';
      previewBinary.value = false;
      return;
    }
    if (!list.some((file) => file.path === selectedPath.value)) selectFile(list[0]);
  },
  { immediate: true }
);
</script>

<template>
  <PanelCard title="文件" :flush="true">
    <template #hint>
      <span class="hint">{{ isDb ? `${files.length} 个` : '—' }}</span>
    </template>
    <template #actions>
      <button class="ghost sm" type="button" :disabled="!canEdit || busy" :title="readonlyHint" @click="pickUpload">
        替换 / 新增文件
      </button>
      <input
        ref="uploadInput"
        type="file"
        class="sr-only"
        accept=".md,.txt,.py,.sh,.js,.json,.yaml,.yml,.csv,.toml,.ini"
        @change="onUpload"
      />
    </template>

    <p v-if="readonlyHint" class="files-hint">{{ readonlyHint }}</p>

    <div v-if="!isDb" class="files-unknown">
      <strong>当前来源不提供文件列表</strong>
      <p>文件包只在 db 源可维护；Nacos 与内置技能不在此展示文件数量与内容。</p>
    </div>

    <div v-else class="files-split">
      <nav class="file-tree" aria-label="技能文件">
        <div v-for="group in groups" :key="group.key" class="file-group">
          <div class="file-group-label mono">{{ group.label }}</div>
          <button
            v-for="file in group.files"
            :key="file.path"
            type="button"
            class="file-node"
            :class="{ active: file.path === selectedPath }"
            :title="file.path"
            @click="selectFile(file)"
          >
            <span class="file-icon" :class="iconTone(file.path)">{{ iconText(file.path) }}</span>
            <span class="file-node-name">{{ shortName(file.path) }}</span>
            <span class="file-node-size">{{ formatSize(file.size) }}</span>
          </button>
        </div>
        <EmptyState
          v-if="!files.length"
          title="还没有文件"
          description="把参考资料放到 references/，脚本放到 scripts/，对话中即可通过 skill_read 读取。"
        />
      </nav>

      <section class="file-preview" :aria-label="selected ? selected.path : '文件预览'">
        <template v-if="selected">
          <header class="preview-head">
            <span class="preview-path mono" :title="selected.path">{{ selected.path }}</span>
            <div class="preview-actions">
              <button class="ghost sm" type="button" :disabled="!canEdit" :title="readonlyHint" @click="openEditor">
                编辑
              </button>
              <button class="ghost sm" type="button" :disabled="!canEdit || busy" :title="readonlyHint" @click="askDelete">
                删除
              </button>
            </div>
          </header>
          <div v-if="previewError" class="file-error" role="alert">
            <span>{{ previewError }}</span>
            <button class="subtle sm" @click="selectFile(selected)">重试</button>
          </div>
          <p v-else-if="previewLoading" class="muted file-loading">加载中…</p>
          <div v-else-if="previewBinary" class="preview-binary">
            <strong>二进制文件</strong>
            <span>暂不支持在线预览，可下载后用本地工具查看。</span>
            <span class="muted">{{ formatSize(selected.size) }}</span>
          </div>
          <pre v-else class="file-pre">{{ previewContent || '（空文件）' }}</pre>
        </template>
        <div v-else class="preview-empty">
          <p>还没有文件</p>
          <p class="muted">上传参考文件或脚本后，可在对话中通过 skill_read 读取。</p>
        </div>
      </section>
    </div>
  </PanelCard>

  <ConfirmDialog
    :open="Boolean(deleteTarget)"
    :title="deleteTarget ? `删除文件 ${deleteTarget.path}` : '删除文件'"
    confirm-text="删除"
    danger
    :busy="busy"
    @update:open="deleteTarget = null"
    @confirm="confirmDelete"
  >
    <p>将删除 <strong class="mono">{{ deleteTarget?.path }}</strong>，删除后无法恢复。</p>
  </ConfirmDialog>
</template>

<style scoped>
.files-hint {
  margin: 0;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
  font-size: 12px;
  color: var(--muted);
}
.files-unknown {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 48px 24px;
  text-align: center;
}
.files-unknown strong {
  font-size: 13px;
  color: var(--text-soft);
}
.files-unknown p {
  margin: 0;
  max-width: 420px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--muted);
}
.files-split {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  min-height: 360px;
}
.file-tree {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 12px;
  border-right: 1px solid var(--border);
  overflow: auto;
}
.file-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.file-group-label {
  padding: 0 6px 4px;
  font-size: 11.5px;
  color: var(--faint);
}
.file-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 30px;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text-soft);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.file-node:hover {
  background: var(--surface-3);
}
.file-node.active {
  background: var(--primary-soft);
  border-color: #d5e2fb;
  color: var(--primary-dark);
}
.file-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 18px;
  flex: none;
  border-radius: 4px;
  font-size: 8.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.file-icon.t1 {
  background: #edf3ff;
  color: #2457c4;
}
.file-icon.t2 {
  background: #e8f5ee;
  color: #18794e;
}
.file-icon.t3 {
  background: #fff6e3;
  color: #9a6700;
}
.file-icon.t4 {
  background: #f3edff;
  color: #6b3fc4;
}
.file-node-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-node-size {
  flex: none;
  font-size: 10.5px;
  color: var(--faint);
}
.file-preview {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 360px;
}
.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
}
.preview-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-soft);
}
.preview-actions {
  display: inline-flex;
  gap: 6px;
  flex: none;
}
.file-pre {
  flex: 1;
  margin: 0;
  max-height: 60vh;
  overflow: auto;
  padding: 14px 16px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.file-loading {
  padding: 16px;
  font-size: 12.5px;
}
.file-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px;
  padding: 10px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 12.5px;
}
.preview-binary,
.preview-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 32px 16px;
  text-align: center;
  font-size: 12.5px;
  color: var(--muted);
}
.preview-binary strong {
  font-size: 13px;
  color: var(--text-soft);
}
.preview-empty p {
  margin: 0;
  font-size: 13px;
  color: var(--text-soft);
}
@media (max-width: 820px) {
  .files-split {
    grid-template-columns: minmax(0, 1fr);
  }
  .file-tree {
    border-right: 0;
    border-bottom: 1px solid var(--border);
    max-height: 240px;
  }
}
</style>

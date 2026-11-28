<script setup>
import { computed, ref, watch } from 'vue';
import { api, uploadForm } from '../../../api/http';
import { formatSize, formatTimestamp } from '../../../utils/format';
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue';

const PREVIEW_BYTES = 256 * 1024;

const props = defineProps({
  skillId: { type: String, required: true },
  status: { type: String, default: 'UNKNOWN' },
  sandboxEnabled: { type: Boolean, default: true },
});

const emit = defineEmits(['changed']);

const children = ref({});
const expanded = ref(new Set(['']));
const loadingDirs = ref({});
const rootLoaded = ref(false);
const treeError = ref('');
const treeTruncated = ref(false);
const selectedPath = ref('');
const file = ref(null);
const fileLoading = ref(false);
const fileError = ref('');
const treeRef = ref(null);

const uploadInput = ref(null);
const uploading = ref(false);
const overwrite = ref(false);
const uploadNote = ref('');
const uploadError = ref('');
const deleteTarget = ref(null);
const deleting = ref(false);

const canBrowse = computed(
  () => props.sandboxEnabled && props.status !== 'INITIALIZING'
);
const rootEntries = computed(() => children.value[''] || []);
const rootLoading = computed(() => Boolean(loadingDirs.value['']));
const visibleNodes = computed(() => {
  if (!canBrowse.value || !rootLoaded.value) return [];
  return flatten('');
});
const selectedSegments = computed(() =>
  selectedPath.value ? selectedPath.value.split('/') : []
);
const treeTruncatedHint = computed(() =>
  treeTruncated.value ? '目录项过多，仅显示前 500 项，请缩小范围' : ''
);
const rootLabel = 'data/';
const uploadDir = computed(() => {
  if (!selectedPath.value) return '';
  const node = visibleNodes.value.find((item) => item.path === selectedPath.value);
  if (node && node.type === 'dir') return node.path;
  return selectedPath.value.split('/').slice(0, -1).join('/');
});

function flatten(path, depth = 0) {
  const list = [];
  for (const entry of children.value[path] || []) {
    list.push({ ...entry, depth });
    if (entry.type === 'dir' && expanded.value.has(entry.path)) {
      list.push(...flatten(entry.path, depth + 1));
    }
  }
  return list;
}

function friendlyError(error) {
  if (error?.status === 409) return '技能数据正在初始化，请稍后重试';
  if (error?.status === 404) return '技能数据或该路径不存在';
  if (error?.status === 503) return '沙箱服务暂时不可用，请稍后重试';
  return error?.message || '加载失败';
}

function browseUrl(kind, path) {
  const query = new URLSearchParams({ path });
  if (kind === 'tree') query.set('depth', '1');
  else query.set('maxBytes', String(PREVIEW_BYTES));
  return `/api/admin/skills/${encodeURIComponent(props.skillId)}/data/${kind}?${query}`;
}

async function loadDir(path, force = false) {
  if (!canBrowse.value) return;
  if (!force && children.value[path]) return;
  loadingDirs.value = { ...loadingDirs.value, [path]: true };
  try {
    const data = await api(browseUrl('tree', path));
    children.value = { ...children.value, [path]: data?.entries || [] };
    if (path === '') {
      treeTruncated.value = Boolean(data?.truncated);
      rootLoaded.value = true;
    }
    treeError.value = '';
  } catch (error) {
    treeError.value = friendlyError(error);
  } finally {
    const next = { ...loadingDirs.value };
    delete next[path];
    loadingDirs.value = next;
  }
}

async function reset() {
  children.value = {};
  expanded.value = new Set(['']);
  loadingDirs.value = {};
  rootLoaded.value = false;
  treeError.value = '';
  treeTruncated.value = false;
  selectedPath.value = '';
  file.value = null;
  fileError.value = '';
  uploadNote.value = '';
  uploadError.value = '';
  await loadDir('', true);
}

function toggleDir(entry) {
  const next = new Set(expanded.value);
  if (next.has(entry.path)) {
    next.delete(entry.path);
    expanded.value = next;
    return;
  }
  next.add(entry.path);
  expanded.value = next;
  loadDir(entry.path);
}

async function openFile(entry) {
  selectedPath.value = entry.path;
  file.value = null;
  fileError.value = '';
  fileLoading.value = true;
  try {
    file.value = await api(browseUrl('file', entry.path));
  } catch (error) {
    fileError.value = friendlyError(error);
  } finally {
    fileLoading.value = false;
  }
}

function activate(node) {
  if (!node) return;
  if (node.type === 'dir') toggleDir(node);
  else openFile(node);
}

function onTreeKeydown(event) {
  const items = Array.from(treeRef.value?.querySelectorAll('[role="treeitem"]') || []);
  const index = items.indexOf(document.activeElement);
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const delta = event.key === 'ArrowDown' ? 1 : -1;
    items[Math.max(0, Math.min(items.length - 1, index + delta))]?.focus();
    return;
  }
  if (index < 0) return;
  const path = items[index].dataset.path;
  const node = visibleNodes.value.find((item) => item.path === path);
  if (!node) return;
  if (event.key === 'ArrowRight' && node.type === 'dir' && !expanded.value.has(node.path)) {
    event.preventDefault();
    toggleDir(node);
  } else if (event.key === 'ArrowLeft' && node.type === 'dir' && expanded.value.has(node.path)) {
    event.preventDefault();
    toggleDir(node);
  }
}

function onSkillChange() {
  reset();
}

function pickFiles() {
  if (!canBrowse.value || uploading.value) return;
  uploadError.value = '';
  uploadNote.value = '';
  uploadInput.value?.click();
}

function uploadMessage(error) {
  if (error?.status === 409) return '同名文件已存在；勾选「覆盖同名」后重试';
  if (error?.status === 413) return error.message || '文件超过大小上限';
  if (error?.status === 400) return error.message || '文件名或内容不合法';
  return error?.message || '上传失败';
}

async function onUploadPicked(event) {
  const picked = Array.from(event.target.files || []);
  event.target.value = '';
  if (!picked.length) return;
  const dir = uploadDir.value;
  uploading.value = true;
  uploadError.value = '';
  uploadNote.value = '';
  const failed = [];
  let uploaded = 0;
  for (const raw of picked) {
    const target = dir ? `${dir}/${raw.name}` : raw.name;
    const query = new URLSearchParams({ path: target });
    if (overwrite.value) query.set('overwrite', 'true');
    const form = new FormData();
    form.append('file', raw);
    try {
      await uploadForm(
        `/api/admin/skills/${encodeURIComponent(props.skillId)}/data/file?${query}`,
        form
      );
      uploaded += 1;
    } catch (error) {
      failed.push(`${raw.name}：${uploadMessage(error)}`);
    }
  }
  uploading.value = false;
  if (uploaded) {
    uploadNote.value = `已上传 ${uploaded} 个文件到 data/${dir ? `/${dir}` : ''}`;
    await loadDir(dir, true);
    emit('changed');
  }
  if (failed.length) uploadError.value = failed.join('；');
}

function askDelete() {
  if (!selectedPath.value || deleting.value) return;
  deleteTarget.value = selectedPath.value;
}

async function confirmDelete() {
  if (!deleteTarget.value || deleting.value) return;
  const path = deleteTarget.value;
  const dir = path.split('/').slice(0, -1).join('/');
  deleting.value = true;
  try {
    const query = new URLSearchParams({ path });
    await api(`/api/admin/skills/${encodeURIComponent(props.skillId)}/data/file?${query}`, {
      method: 'DELETE',
    });
    deleteTarget.value = null;
    if (selectedPath.value === path) {
      selectedPath.value = '';
      file.value = null;
      fileError.value = '';
    }
    uploadError.value = '';
    uploadNote.value = `已删除 data/${path}`;
    await loadDir(dir, true);
    emit('changed');
  } catch (error) {
    uploadError.value = `删除失败：${error?.message || '未知错误'}`;
    deleteTarget.value = null;
  } finally {
    deleting.value = false;
  }
}

watch(
  () => [props.skillId, canBrowse.value],
  ([id, browse], previous) => {
    if (!previous || previous[0] !== id || (browse && !previous[1])) {
      reset();
    }
  },
  { immediate: true }
);
</script>

<template>
  <section class="data-browser">
    <header class="browser-head">
      <div>
        <h4>数据文件</h4>
        <p class="browser-sub">
          可上传文件到 data/，与初始化脚本写入的是同一目录；运行期只读挂载到 /data。
          重建数据会清空此目录（含上传文件）。
        </p>
      </div>
      <div class="browser-actions">
        <label class="overwrite-toggle">
          <input v-model="overwrite" type="checkbox" :disabled="uploading" />
          覆盖同名
        </label>
        <button class="primary sm" :disabled="!canBrowse || uploading" @click="pickFiles">
          {{ uploading ? '上传中…' : '上传文件' }}
        </button>
        <input
          ref="uploadInput"
          class="hidden-input"
          type="file"
          multiple
          @change="onUploadPicked"
        />
        <button class="subtle sm" :disabled="!canBrowse || rootLoading" @click="reset">
          刷新
        </button>
        <span class="readonly-pill">运行期只读</span>
      </div>
    </header>

    <p class="upload-target">
      上传到 <code class="mono">data/{{ uploadDir }}</code>
    </p>
    <p v-if="uploadNote" class="browser-hint" role="status">{{ uploadNote }}</p>
    <p v-if="uploadError" class="browser-error" role="alert">{{ uploadError }}</p>

    <div v-if="!sandboxEnabled" class="browser-note" role="status">
      沙箱未启用，无法浏览或上传技能数据。
    </div>
    <div v-else-if="status === 'INITIALIZING'" class="browser-note" role="status">
      技能数据正在初始化，完成后可继续浏览与上传。
    </div>
    <div v-else-if="status === 'UNKNOWN'" class="browser-note" role="status">
      尚未初始化数据；上传的文件会直接写入 data/，脚本运行时可读到。
    </div>
    <div v-else-if="status === 'FAILED'" class="browser-note warn" role="status">
      上次初始化失败，以下为残留数据，仅供参考；上传仍会写入 data/。
    </div>

    <div v-if="canBrowse" class="browser-grid">
      <div ref="treeRef" class="tree-pane" role="tree" :aria-label="`${rootLabel} 目录树`" @keydown="onTreeKeydown">
        <div v-if="treeError" class="browser-error" role="alert">
          <span>{{ treeError }}</span>
          <button class="subtle sm" @click="reset">重试</button>
        </div>
        <div v-else-if="!rootLoaded" class="browser-empty">加载中…</div>
        <template v-else>
          <div class="tree-root mono">{{ rootLabel }}</div>
          <p v-if="!rootEntries.length" class="browser-empty">
            目录为空，点击右上角「上传文件」写入 data/
          </p>
          <ul v-else class="tree-list">
            <li
              v-for="node in visibleNodes"
              :key="node.path"
              class="tree-row"
              :class="{ selected: selectedPath === node.path, dir: node.type === 'dir' }"
              :style="{ paddingLeft: `${10 + node.depth * 16}px` }"
              role="treeitem"
              tabindex="0"
              :data-path="node.path"
              :aria-expanded="node.type === 'dir' ? expanded.has(node.path) : undefined"
              :aria-selected="selectedPath === node.path"
              @click="activate(node)"
              @keydown.enter.prevent="activate(node)"
              @keydown.space.prevent="activate(node)"
            >
              <span v-if="node.type === 'dir'" class="caret" aria-hidden="true">
                {{ expanded.has(node.path) ? '▾' : '▸' }}
              </span>
              <span v-else class="file-dot" aria-hidden="true"></span>
              <span class="tree-name mono">{{ node.name }}</span>
              <span v-if="node.type === 'file'" class="tree-size">{{ formatSize(node.size) }}</span>
            </li>
          </ul>
          <p v-if="treeTruncatedHint" class="browser-hint">{{ treeTruncatedHint }}</p>
        </template>
      </div>

      <div class="preview-pane" role="region" aria-label="文件预览">
        <div v-if="!selectedPath" class="browser-empty tall">选择左侧文件查看内容</div>
        <div v-else-if="fileLoading" class="browser-empty tall">加载中…</div>
        <div v-else-if="fileError" class="browser-error" role="alert">
          <span>{{ fileError }}</span>
        </div>
        <template v-else-if="file">
          <nav class="preview-crumbs" aria-label="文件路径">
            <span class="mono">data</span>
            <template v-for="segment in selectedSegments" :key="segment">
              <span aria-hidden="true">/</span>
              <span class="mono">{{ segment }}</span>
            </template>
          </nav>
          <div class="preview-meta">
            <span>{{ formatSize(file.size) }}</span>
            <span>{{ formatTimestamp(file.modifiedAt) }}</span>
            <span class="mono">{{ file.mime }}</span>
            <span v-if="!file.isText" class="badge">二进制</span>
            <button
              v-if="file"
              class="subtle sm danger-text"
              :disabled="deleting"
              @click="askDelete"
            >
              删除文件
            </button>
          </div>
          <p v-if="file.truncated" class="browser-hint warn">
            仅预览前 {{ formatSize(PREVIEW_BYTES) }}，完整文件未加载。
          </p>
          <pre v-if="file.isText" class="preview-pre">{{ file.content }}</pre>
          <div v-else class="browser-empty tall">
            二进制文件不展示内容，仅提供大小、修改时间与类型。
          </div>
        </template>
      </div>
    </div>

    <ConfirmDialog
      :open="Boolean(deleteTarget)"
      title="删除数据文件"
      confirm-text="删除文件"
      :danger="true"
      :busy="deleting"
      @update:open="deleteTarget = null"
      @confirm="confirmDelete"
    >
      <p class="mono">data/{{ deleteTarget }}</p>
      <p>删除后运行期容器将不再看到该文件，且不可恢复。</p>
    </ConfirmDialog>
  </section>
</template>

<style scoped>
.data-browser {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
}
.browser-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.browser-head h4 {
  margin: 0;
  font-size: 13.5px;
}
.browser-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}
.browser-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.readonly-pill {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--surface-3);
  color: var(--muted);
  font-size: 11px;
}
.overwrite-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-soft);
  cursor: pointer;
}
.hidden-input {
  display: none;
}
.upload-target {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
.browser-note {
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 12.5px;
  color: var(--muted);
}
.browser-note.warn {
  border-color: #f0d9a8;
  background: var(--st-warn-soft, #fdf6e3);
  color: var(--st-warn, #8a5a00);
}
.browser-grid {
  display: grid;
  grid-template-columns: minmax(220px, 34%) 1fr;
  gap: 12px;
  min-height: 320px;
}
.tree-pane,
.preview-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
}
.tree-pane {
  padding: 8px 0;
  max-height: 460px;
  overflow: auto;
}
.preview-pane {
  padding: 12px;
  overflow: auto;
}
.tree-root {
  padding: 2px 10px 6px;
  font-size: 12px;
  color: var(--muted);
}
.tree-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  font-size: 12.5px;
  color: var(--text-soft);
  cursor: pointer;
  border-left: 2px solid transparent;
}
.tree-row:hover {
  background: var(--surface-3);
}
.tree-row:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.tree-row.selected {
  border-left-color: var(--primary);
  background: var(--surface-3);
  color: var(--text);
}
.caret {
  width: 12px;
  color: var(--muted);
  font-size: 10px;
}
.file-dot {
  width: 12px;
  text-align: center;
  color: var(--faint);
}
.file-dot::before {
  content: '·';
}
.tree-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-size {
  flex: none;
  color: var(--muted);
  font-size: 11px;
}
.browser-empty {
  padding: 16px;
  font-size: 12.5px;
  color: var(--muted);
}
.browser-empty.tall {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  flex: 1;
}
.browser-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 0 10px 8px;
  padding: 8px 10px;
  border: 1px solid #f3d3d3;
  border-radius: 6px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 12px;
}
.browser-hint {
  margin: 8px 10px 0;
  font-size: 11.5px;
  color: var(--muted);
}
.browser-hint.warn {
  color: var(--st-warn, #8a5a00);
}
.preview-crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
}
.preview-crumbs span:last-child {
  color: var(--text-soft);
}
.preview-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--muted);
}
.badge {
  padding: 0 6px;
  border-radius: 999px;
  background: var(--surface-3);
  color: var(--muted);
}
.danger-text {
  margin-left: auto;
  color: var(--st-bad, #c92a2a);
}
.preview-pre {
  margin: 10px 0 0;
  padding: 10px;
  border-radius: 6px;
  background: var(--surface);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
}
@media (max-width: 860px) {
  .browser-grid {
    grid-template-columns: 1fr;
  }
  .preview-pane {
    min-height: 240px;
  }
}
</style>

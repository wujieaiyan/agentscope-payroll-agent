<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { api, ApiError } from '../../../api/http';
import { toast } from '../../../stores/toast';
import { loadSkillEnv, useSkillEnv } from '../../../stores/skillEnv';
import { fileUrl, formatCount, formatSize, formatTimestamp } from '../../../utils/format';
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue';
import EmptyState from '../../../components/ui/EmptyState.vue';
import SkeletonRows from '../../../components/ui/SkeletonRows.vue';
import SegmentedTabs from '../../../components/ui/SegmentedTabs.vue';
import MarkdownPreview from './MarkdownPreview.vue';
import DiffView from './DiffView.vue';
import { MAX_DESCRIPTION_LENGTH, contentChecks, displayUser } from './rules';
import { clearDraft, readDraft, writeDraft } from './draft';

const MAX_NOTE = 200;
const ID_PATTERN = /^[a-z][a-z0-9-]{1,63}$/;
const RESERVED_IDS = ['new', 'import', 'templates', 'edit'];
const BINARY_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'pdf', 'zip', 'gz', 'tar', 'jar',
  'class', 'bin', 'xlsx', 'xls', 'docx', 'doc', 'pt', 'pkl', 'npy', 'onnx', 'parquet',
]);

const route = useRoute();
const router = useRouter();
const env = useSkillEnv();

const mode = computed(() => (route.name === 'skill-create-editor' ? 'create' : 'edit'));
const isCreate = computed(() => mode.value === 'create');
const skillId = computed(() => (isCreate.value ? '' : String(route.params.id || '')));
const templateId = computed(() =>
  isCreate.value ? String(route.query.templateId || '') : ''
);

const detail = ref(null);
const users = ref({});
const writable = ref(true);
const loading = ref(true);
const loadError = ref('');

const form = reactive({ id: '', description: '', executionEnabled: false });
const baselineForm = reactive({ description: '', executionEnabled: false });
const body = reactive({ content: '', baseline: '' });
const files = reactive({});
const activePath = ref('SKILL.md');
const view = ref('source');
const changeNote = ref('');
const idTouched = ref(false);
const busy = ref(false);
const inlineError = ref('');
const conflict = ref(null);
const diffOverride = ref(null);
const draftSavedAt = ref(0);
const draftAvailable = ref(null);

const reloadOpen = ref(false);
const leaveOpen = ref(false);
const pendingTo = ref(null);
const leaving = ref(false);
let draftTimer = null;
let skipDraftOffer = false;

const versionNo = computed(() => Number(detail.value?.versionNo || 0));
const isDb = computed(() => detail.value?.source === 'db');
const readonly = computed(
  () => !writable.value || detail.value?.source === 'builtin'
);
const readonlyReason = computed(() => {
  if (!writable.value) return '当前存储只读：编辑器仅展示内容，保存已禁用。';
  return '内置技能尚未落库，无法直接编辑；如需修改请先导入为自定义技能。';
});
const fileWritable = computed(() => writable.value && isDb.value);

const isBinary = computed(() => BINARY_EXTENSIONS.has(extensionOf(activePath.value)));
const activeFile = computed(() => (activePath.value === 'SKILL.md' ? null : files[activePath.value] || null));

const activeContent = computed({
  get() {
    if (activePath.value === 'SKILL.md') return body.content;
    return files[activePath.value]?.content ?? '';
  },
  set(value) {
    if (readonly.value) return;
    if (activePath.value === 'SKILL.md') body.content = value;
    else if (files[activePath.value]) files[activePath.value].content = value;
    onEdited();
  },
});
const activeBaseline = computed(() => {
  if (activePath.value === 'SKILL.md') return body.baseline;
  return files[activePath.value]?.baseline ?? '';
});
const activeDirty = computed(() => activeContent.value !== activeBaseline.value);
const bodyDirty = computed(() => body.content !== body.baseline);
const fileDirty = computed(() =>
  Object.values(files).some((file) => file.loaded && file.content !== file.baseline)
);
const formDirty = computed(
  () =>
    form.description.trim() !== baselineForm.description ||
    Boolean(form.executionEnabled) !== baselineForm.executionEnabled
);
const dirty = computed(() => bodyDirty.value || formDirty.value || fileDirty.value);

const checks = computed(() => contentChecks(body.content, form.description));
const failedChecks = computed(() => checks.value.filter((item) => !item.ok));
const charCount = computed(() => activeContent.value.length);
const checkSummary = computed(() => {
  if (activePath.value !== 'SKILL.md') return '文件保存不产生新版本';
  return failedChecks.value.length ? `${failedChecks.value.length} 项待改` : '检查全部通过';
});

const idError = computed(() => {
  if (!isCreate.value) return '';
  const id = form.id.trim();
  if (!id) return '请填写技能 ID';
  if (!ID_PATTERN.test(id)) return 'ID 需为 2–64 位小写字母、数字或连字符，且以字母开头';
  if (RESERVED_IDS.includes(id)) return `ID 不能使用管理台保留字：${RESERVED_IDS.join('、')}`;
  return '';
});
const descriptionError = computed(() =>
  form.description.length > MAX_DESCRIPTION_LENGTH
    ? `说明最多 ${MAX_DESCRIPTION_LENGTH} 字`
    : ''
);

const canCompare = computed(() => {
  if (isCreate.value || isBinary.value) return false;
  if (diffOverride.value) return true;
  if (activePath.value === 'SKILL.md') return isDb.value && versionNo.value > 0;
  return Boolean(activeFile.value?.loaded);
});
const diffLabel = computed(() => {
  if (diffOverride.value) return diffOverride.value.label;
  return activePath.value === 'SKILL.md' ? `v${versionNo.value}` : '最近保存';
});
const diffBefore = computed(() =>
  diffOverride.value ? diffOverride.value.before : activeBaseline.value
);

const viewTabs = computed(() => [
  { key: 'source', label: '源码' },
  { key: 'preview', label: '预览' },
  ...(canCompare.value ? [{ key: 'diff', label: `对比 ${diffLabel.value}` }] : []),
]);

const activeSaveable = computed(() => {
  if (readonly.value || busy.value || Boolean(conflict.value) || isBinary.value) return false;
  if (!activeFile.value && activePath.value !== 'SKILL.md') return false;
  if (activePath.value === 'SKILL.md') {
    if (idError.value || descriptionError.value || failedChecks.value.length) return false;
    if (!isCreate.value && !bodyDirty.value && !formDirty.value) return false;
    return true;
  }
  if (!fileWritable.value || !activeFile.value?.loaded) return false;
  return activeDirty.value;
});
const saveLabel = computed(() => {
  if (isCreate.value) return '保存';
  if (activePath.value !== 'SKILL.md') return '保存文件';
  return `保存为 v${versionNo.value + 1}`;
});

const gutterText = computed(() => {
  const lines = activeContent.value.split('\n').length;
  return Array.from({ length: lines }, (_, index) => index + 1).join('\n');
});

const fileGroups = computed(() => {
  const list = (detail.value?.files || []).filter((file) => file.path !== 'SKILL.md');
  const references = list.filter((file) => file.path.startsWith('references/'));
  const scripts = list.filter((file) => file.path.startsWith('scripts/'));
  const roots = list.filter(
    (file) => !file.path.startsWith('references/') && !file.path.startsWith('scripts/')
  );
  return [
    { key: 'references', label: 'references/', files: references },
    { key: 'scripts', label: 'scripts/', files: scripts },
    roots.length ? { key: 'root', label: '根目录', files: roots } : null,
  ].filter((group) => group && group.files.length);
});

const backTo = computed(() =>
  isCreate.value
    ? { path: '/platform/skills/new' }
    : { name: 'skill-workbench', params: { id: skillId.value, tab: 'overview' } }
);
const crumbLabel = computed(() => (isCreate.value ? '新建技能' : `技能 ${skillId.value}`));

function extensionOf(path) {
  const name = String(path || '').split('/').pop() || '';
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

function shortName(path) {
  const index = String(path).lastIndexOf('/');
  return index >= 0 ? path.slice(index + 1) : path;
}

function clock(millis) {
  const date = new Date(millis);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (part) => String(part).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function authorName(username) {
  return displayUser(username, users.value) || '系统导入';
}

function storage() {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch (caught) {
    return null;
  }
}

function applyDetail(next) {
  detail.value = next;
  form.id = next?.id || '';
  form.description = next?.description || '';
  form.executionEnabled = Boolean(next?.executionEnabled);
  baselineForm.description = form.description.trim();
  baselineForm.executionEnabled = Boolean(form.executionEnabled);
  body.content = next?.content || '';
  body.baseline = body.content;
  changeNote.value = '';
  for (const key of Object.keys(files)) delete files[key];
  for (const file of next?.files || []) {
    if (file.path === 'SKILL.md') continue;
    files[file.path] = {
      content: '',
      baseline: '',
      loaded: false,
      loading: false,
      error: '',
      binary: BINARY_EXTENSIONS.has(extensionOf(file.path)),
      size: file.size,
      kind: file.kind,
    };
  }
}

async function loadEnv() {
  if (!env.value) await loadSkillEnv();
  if (!env.value) writable.value = true;
}

async function loadEdit() {
  loading.value = true;
  loadError.value = '';
  conflict.value = null;
  diffOverride.value = null;
  view.value = 'source';
  idTouched.value = false;
  draftSavedAt.value = 0;
  draftAvailable.value = null;
  await loadEnv();
  const [detailResult, overviewResult] = await Promise.allSettled([
    api(`/api/admin/skills/${encodeURIComponent(skillId.value)}`),
    api(`/api/admin/skills/${encodeURIComponent(skillId.value)}/overview`),
  ]);
  if (detailResult.status === 'rejected') {
    loadError.value = detailResult.reason?.message || '技能加载失败';
    loading.value = false;
    return;
  }
  applyDetail(detailResult.value);
  if (overviewResult.status === 'fulfilled') {
    users.value = overviewResult.value?.users || {};
    const flagged = overviewResult.value?.identity?.writable;
    writable.value = typeof flagged === 'boolean' ? flagged : Boolean(env.value?.writable);
  } else {
    users.value = {};
    writable.value = Boolean(env.value?.writable);
  }
  const requested = String(route.query.path || '');
  activePath.value = requested && files[requested] ? requested : 'SKILL.md';
  if (activePath.value !== 'SKILL.md') await ensureFile(activePath.value);
  offerDraft();
  loading.value = false;
}

async function loadCreate() {
  loading.value = true;
  loadError.value = '';
  view.value = 'source';
  idTouched.value = false;
  draftSavedAt.value = 0;
  draftAvailable.value = null;
  await loadEnv();
  applyDetail(null);
  if (templateId.value) {
    try {
      const payload = (await api('/api/admin/skills/templates')) || {};
      const template = (payload.templates || []).find((item) => item.id === templateId.value);
      if (template) {
        body.content = template.body || '';
        body.baseline = body.content;
        form.description = template.description || '';
        baselineForm.description = form.description.trim();
      } else {
        toast('模板不存在或已下线，按空白内容新建', 'error');
      }
    } catch (caught) {
      toast(caught.message || '模板加载失败，按空白内容新建', 'error');
    }
  }
  offerDraft();
  loading.value = false;
}

function offerDraft() {
  if (skipDraftOffer) return;
  const draft = readDraft(storage(), skillId.value || null);
  if (!draft) return;
  const updatedAt = Number(detail.value?.updatedAt || 0);
  if (!isCreate.value && updatedAt && draft.savedAt <= updatedAt) {
    clearDraft(storage(), skillId.value);
    return;
  }
  draftAvailable.value = draft;
}

function restoreDraft() {
  const draft = draftAvailable.value;
  if (!draft) return;
  if (isCreate.value && typeof draft.id === 'string') form.id = draft.id;
  if (typeof draft.description === 'string') form.description = draft.description;
  if (typeof draft.executionEnabled === 'boolean') form.executionEnabled = draft.executionEnabled;
  if (typeof draft.content === 'string') body.content = draft.content;
  if (typeof draft.changeNote === 'string') changeNote.value = draft.changeNote;
  draftSavedAt.value = Number(draft.savedAt) || Date.now();
  draftAvailable.value = null;
  view.value = 'source';
}

function discardDraft() {
  clearDraft(storage(), skillId.value || null);
  draftSavedAt.value = 0;
  draftAvailable.value = null;
}

function onEdited() {
  inlineError.value = '';
  if (readonly.value) return;
  clearTimeout(draftTimer);
  draftTimer = setTimeout(persistDraft, 2000);
}

function persistDraft() {
  if (readonly.value) return;
  clearTimeout(draftTimer);
  draftTimer = null;
  if (!dirty.value) return;
  writeDraft(storage(), skillId.value || null, {
    id: form.id,
    description: form.description,
    executionEnabled: Boolean(form.executionEnabled),
    content: body.content,
    changeNote: changeNote.value,
    templateId: templateId.value,
  });
  draftSavedAt.value = Date.now();
}

async function ensureFile(path) {
  const target = files[path];
  if (!target || target.loaded || target.loading) return;
  target.loading = true;
  target.error = '';
  try {
    const payload = await api(fileUrl(skillId.value, path));
    target.content = payload?.content || '';
    target.baseline = target.content;
    target.loaded = true;
  } catch (caught) {
    target.error = caught?.message || '文件加载失败';
  } finally {
    target.loading = false;
  }
}

function selectPath(path) {
  if (path === activePath.value) return;
  activePath.value = path;
  diffOverride.value = null;
  view.value = 'source';
  if (path !== 'SKILL.md') ensureFile(path);
}

function syncScroll(event) {
  const gutter = event.target?.parentElement?.querySelector('.ed-gutter');
  if (gutter) gutter.scrollTop = event.target.scrollTop;
}

function onEditorKeydown(event) {
  if (event.key !== 'Tab' || event.ctrlKey || event.metaKey || event.altKey) return;
  if (readonly.value) return;
  event.preventDefault();
  const el = event.target;
  const start = el.selectionStart;
  const end = el.selectionEnd;
  const value = el.value;
  activeContent.value = `${value.slice(0, start)}  ${value.slice(end)}`;
  requestAnimationFrame(() => {
    el.selectionStart = el.selectionEnd = start + 2;
  });
}

async function refreshDetail() {
  try {
    const fresh = await api(`/api/admin/skills/${encodeURIComponent(skillId.value)}`);
    detail.value = fresh;
    for (const file of fresh?.files || []) {
      if (file.path === 'SKILL.md') continue;
      const existing = files[file.path];
      if (existing) {
        existing.size = file.size;
        existing.kind = file.kind;
      } else {
        files[file.path] = {
          content: '',
          baseline: '',
          loaded: false,
          loading: false,
          error: '',
          binary: BINARY_EXTENSIONS.has(extensionOf(file.path)),
          size: file.size,
          kind: file.kind,
        };
      }
    }
    for (const path of Object.keys(files)) {
      if (!(fresh?.files || []).some((file) => file.path === path)) delete files[path];
    }
    if (activePath.value !== 'SKILL.md' && !files[activePath.value]) {
      activePath.value = 'SKILL.md';
    }
  } catch (caught) {
    // 刷新失败只影响文件大小与更新时间，已保存的基线不回退。
  }
}

function openConflict(message) {
  conflict.value = { message: message || '技能已被他人修改', latest: null };
  api(`/api/admin/skills/${encodeURIComponent(skillId.value)}`)
    .then((latest) => {
      if (conflict.value) conflict.value.latest = latest;
    })
    .catch(() => {
      // 取不到最新版本不影响冲突条与三个出口。
    });
}

function handleSaveError(caught) {
  if (caught instanceof ApiError && caught.status === 409) {
    if (isCreate.value) {
      inlineError.value = caught.message || '技能 ID 已存在';
      return;
    }
    openConflict(caught.message);
    return;
  }
  if (caught instanceof ApiError && caught.status === 413) {
    inlineError.value = '内容过大被服务端拒绝；请把大段资料放到 references/ 文件里。';
    return;
  }
  inlineError.value = caught?.message || '保存失败';
}

async function saveActive() {
  if (!activeSaveable.value) return;
  if (isCreate.value) return saveCreate();
  if (activePath.value === 'SKILL.md') return saveBody();
  return saveFile(activePath.value);
}

async function saveBody() {
  if (failedChecks.value.length) {
    toast('正文未通过检查，请按右侧提示修改后再保存', 'error');
    return;
  }
  busy.value = true;
  inlineError.value = '';
  try {
    const revision = detail.value?.revision || '';
    const query = revision ? `?revision=${encodeURIComponent(revision)}` : '';
    const payload = {
      id: skillId.value,
      description: form.description.trim(),
      content: body.content,
      executionEnabled: Boolean(form.executionEnabled),
      changeNote: changeNote.value.trim() || null,
    };
    const saved = await api(
      `/api/admin/skills/${encodeURIComponent(skillId.value)}${query}`,
      { method: 'PUT', body: payload }
    );
    body.baseline = body.content;
    form.description = form.description.trim();
    baselineForm.description = form.description;
    baselineForm.executionEnabled = Boolean(form.executionEnabled);
    changeNote.value = '';
    conflict.value = null;
    diffOverride.value = null;
    clearDraft(storage(), skillId.value);
    draftSavedAt.value = 0;
    await refreshDetail();
    toast(`已保存为 v${Number(saved?.versionNo || 0) || versionNo.value}`, 'success');
  } catch (caught) {
    handleSaveError(caught);
  } finally {
    busy.value = false;
  }
}

async function saveFile(path) {
  const target = files[path];
  if (!target) return;
  busy.value = true;
  inlineError.value = '';
  try {
    const revision = detail.value?.revision || '';
    const query = revision ? `?revision=${encodeURIComponent(revision)}` : '';
    const result = await api(`${fileUrl(skillId.value, path)}${query}`, {
      method: 'PUT',
      body: { content: target.content },
    });
    target.baseline = target.content;
    if (result?.revision) detail.value = { ...detail.value, revision: result.revision };
    conflict.value = null;
    diffOverride.value = null;
    toast(`已保存 ${path}`, 'success');
    await refreshDetail();
  } catch (caught) {
    handleSaveError(caught);
  } finally {
    busy.value = false;
  }
}

async function saveCreate() {
  if (idError.value) {
    idTouched.value = true;
    return;
  }
  busy.value = true;
  inlineError.value = '';
  try {
    const payload = {
      id: form.id.trim(),
      description: form.description.trim(),
      content: body.content,
      executionEnabled: Boolean(form.executionEnabled),
    };
    if (templateId.value) payload.templateId = templateId.value;
    const saved = await api('/api/admin/skills', { method: 'POST', body: payload });
    body.baseline = body.content;
    clearDraft(storage(), null);
    draftSavedAt.value = 0;
    toast(`技能 ${saved?.id || payload.id} 已创建`, 'success');
    leaving.value = true;
    router.replace({
      name: 'skill-workbench',
      params: { id: saved?.id || payload.id, tab: 'overview' },
    });
  } catch (caught) {
    handleSaveError(caught);
  } finally {
    busy.value = false;
  }
}

async function showConflictDiff() {
  if (!conflict.value) return;
  const latest = conflict.value.latest;
  if (activePath.value === 'SKILL.md') {
    if (!latest) {
      toast('最新版本还未取到，请稍后重试「查看差异」', 'error');
      return;
    }
    diffOverride.value = {
      before: latest.content || '',
      label: `v${latest.versionNo || ''}`,
    };
    view.value = 'diff';
    return;
  }
  try {
    const payload = await api(fileUrl(skillId.value, activePath.value));
    diffOverride.value = { before: payload?.content || '', label: '服务器最新' };
    view.value = 'diff';
  } catch (caught) {
    toast(caught.message || '读取最新文件失败', 'error');
  }
}

async function reloadLatest() {
  reloadOpen.value = false;
  skipDraftOffer = true;
  try {
    const fresh = await api(`/api/admin/skills/${encodeURIComponent(skillId.value)}`);
    applyDetail(fresh);
    if (activePath.value !== 'SKILL.md' && !files[activePath.value]) activePath.value = 'SKILL.md';
    if (activePath.value !== 'SKILL.md') await ensureFile(activePath.value);
    conflict.value = null;
    diffOverride.value = null;
    clearDraft(storage(), skillId.value);
    draftSavedAt.value = 0;
    toast('已载入最新版本，本地修改已丢弃', 'success');
  } catch (caught) {
    toast(caught.message || '加载最新版本失败', 'error');
  } finally {
    skipDraftOffer = false;
  }
}

function downloadDraft() {
  const blob = new Blob([activeContent.value], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${(isCreate.value ? form.id.trim() : skillId.value) || 'skill'}-draft.md`;
  link.click();
  URL.revokeObjectURL(url);
  toast('已下载为 .md，合并后再保存', 'success');
}

function requestLeave() {
  if (busy.value) {
    toast('正在保存，请稍候', 'error');
    return;
  }
  if (!dirty.value) {
    leaving.value = true;
    router.push(backTo.value);
    return;
  }
  persistDraft();
  pendingTo.value = backTo.value;
  leaveOpen.value = true;
}

function confirmLeave() {
  leaveOpen.value = false;
  const target = pendingTo.value || backTo.value;
  pendingTo.value = null;
  leaving.value = true;
  router.push(target);
}

function cancelLeave() {
  leaveOpen.value = false;
  pendingTo.value = null;
}

function goList() {
  leaving.value = true;
  router.push('/platform/skills');
}

function onDocumentKeydown(event) {
  if (!(event.metaKey || event.ctrlKey) || String(event.key).toLowerCase() !== 's') return;
  event.preventDefault();
  if (activeSaveable.value) saveActive();
}

function onBeforeUnload(event) {
  if (!busy.value && !dirty.value) return;
  event.preventDefault();
  event.returnValue = '';
}

onBeforeRouteLeave((to) => {
  if (leaving.value) return true;
  if (busy.value) return false;
  if (!dirty.value) return true;
  persistDraft();
  pendingTo.value = to.fullPath;
  leaveOpen.value = true;
  return false;
});

watch(
  () => route.params.id,
  () => {
    if (!isCreate.value) loadEdit();
  }
);

watch(canCompare, (value) => {
  if (!value && view.value === 'diff') view.value = 'source';
});

onMounted(() => {
  if (isCreate.value) loadCreate();
  else loadEdit();
  document.addEventListener('keydown', onDocumentKeydown);
  window.addEventListener('beforeunload', onBeforeUnload);
});

onBeforeUnmount(() => {
  clearTimeout(draftTimer);
  document.removeEventListener('keydown', onDocumentKeydown);
  window.removeEventListener('beforeunload', onBeforeUnload);
});
</script>

<template>
  <div class="page skills-canvas editor-page">
    <EmptyState v-if="loadError" title="无法打开编辑器" :description="loadError">
      <template #actions>
        <button class="ghost" @click="isCreate ? loadCreate() : loadEdit()">重试</button>
        <button class="ghost" @click="goList">返回技能列表</button>
      </template>
    </EmptyState>

    <div v-else-if="loading" class="ed-skeleton">
      <SkeletonRows :rows="7" :columns="['18%', '46%', '12%', '16%']" />
    </div>

    <template v-else>
      <nav class="ed-crumb" aria-label="面包屑">
        <button type="button" class="link-btn" @click="requestLeave">← {{ crumbLabel }}</button>
        <span aria-hidden="true">/</span>
        <span>编辑正文</span>
      </nav>

      <header class="ed-head">
        <div class="ed-head-main">
          <h2>编辑正文</h2>
          <p class="ed-sub">
            <span class="mono">{{ isCreate ? form.id || '新技能' : skillId }}</span>
            <template v-if="!isCreate"> · 基于 <span class="mono">v{{ versionNo }}</span> 编辑</template>
            <template v-if="draftSavedAt"> · 草稿保存于 {{ clock(draftSavedAt) }}</template>
          </p>
        </div>
        <div class="ed-head-actions">
          <SegmentedTabs
            :tabs="viewTabs"
            :model-value="view"
            aria-label="编辑视图"
            @update:model-value="view = $event"
          />
          <button class="ghost" type="button" :disabled="busy" @click="requestLeave">取消</button>
          <button class="primary" type="button" :disabled="!activeSaveable" @click="saveActive">
            {{ busy ? '保存中…' : saveLabel }}
          </button>
        </div>
      </header>

      <div v-if="readonly" class="ed-notice" role="status">{{ readonlyReason }}</div>

      <div v-if="conflict" class="conflict-bar" role="alert">
        <div class="conflict-text">
          <strong>
            {{ conflict.latest?.updatedBy ? authorName(conflict.latest.updatedBy) + ' ' : '他人' }}
            把该技能更新到了 v{{ conflict.latest?.versionNo || '?' }}
          </strong>
          <span>
            你的修改基于 v{{ versionNo }}，直接保存会覆盖对方的改动。请选择一个出口继续。
          </span>
        </div>
        <div class="conflict-actions">
          <button class="subtle" type="button" @click="showConflictDiff">查看差异</button>
          <button class="subtle" type="button" @click="reloadOpen = true">重新加载</button>
          <button class="subtle" type="button" @click="downloadDraft">另存为 .md</button>
        </div>
      </div>

      <div v-if="draftAvailable" class="draft-bar" role="status">
        <span>发现 {{ clock(draftAvailable.savedAt) }} 的本地草稿，可恢复继续编辑。</span>
        <div class="draft-actions">
          <button class="subtle sm" type="button" @click="restoreDraft">恢复草稿</button>
          <button class="ghost sm" type="button" @click="discardDraft">丢弃</button>
        </div>
      </div>

      <p v-if="inlineError" class="ed-inline-error" role="alert">{{ inlineError }}</p>

      <div class="skill-editor-grid">
        <aside class="panel-card ed-tree" aria-label="技能文件">
          <div class="ed-tree-head">文件</div>
          <button
            type="button"
            class="ed-file"
            :class="{ active: activePath === 'SKILL.md' }"
            @click="selectPath('SKILL.md')"
          >
            <span class="ed-file-name mono">SKILL.md</span>
            <i v-if="body.content !== body.baseline" class="ed-dirty" title="已修改" />
          </button>
          <template v-for="group in fileGroups" :key="group.key">
            <div class="ed-group-label mono">{{ group.label }}</div>
            <button
              v-for="file in group.files"
              :key="file.path"
              type="button"
              class="ed-file"
              :class="{ active: activePath === file.path }"
              :title="file.path"
              @click="selectPath(file.path)"
            >
              <span class="ed-file-name">{{ shortName(file.path) }}</span>
              <span class="ed-file-size">{{ formatSize(file.size) }}</span>
              <i
                v-if="files[file.path] && files[file.path].content !== files[file.path].baseline"
                class="ed-dirty"
                title="已修改"
              />
            </button>
          </template>
          <p v-if="isCreate" class="ed-tree-hint">保存后可添加参考文件与脚本。</p>
          <p v-else-if="!isDb" class="ed-tree-hint">当前来源不提供文件包，只有正文可编辑。</p>
          <p v-else-if="!fileGroups.length" class="ed-tree-hint">还没有参考文件或脚本。</p>
        </aside>

        <section class="panel-card ed-main" aria-label="编辑区">
          <header class="ed-filebar">
            <span class="mono" :title="activePath">{{ activePath }}</span>
            <span class="ed-dirty-state" :class="{ warn: activeDirty }">
              {{ activeDirty ? '有未保存修改' : '已同步' }}
            </span>
          </header>

          <div class="ed-body">
            <div v-if="isBinary" class="ed-binary">
              <strong>二进制文件</strong>
              <p>暂不支持在线编辑，请下载后用本地工具修改，再通过文件页签重新上传。</p>
            </div>
            <p v-else-if="activeFile?.loading" class="ed-placeholder">加载文件…</p>
            <div v-else-if="activeFile?.error" class="ed-error" role="alert">
              <span>{{ activeFile.error }}</span>
              <button class="subtle sm" type="button" @click="ensureFile(activePath)">重试</button>
            </div>
            <div v-else-if="view === 'source'" class="ed-source">
              <pre class="ed-gutter" aria-hidden="true">{{ gutterText }}</pre>
              <textarea
                v-model="activeContent"
                class="ed-area mono"
                :readonly="readonly"
                spellcheck="false"
                :aria-label="`编辑 ${activePath}`"
                @scroll="syncScroll"
                @keydown="onEditorKeydown"
                @blur="persistDraft"
              />
            </div>
            <MarkdownPreview v-else-if="view === 'preview'" :source="activeContent" class="ed-preview" />
            <DiffView
              v-else
              :before="diffBefore"
              :after="activeContent"
              :label="diffLabel"
              class="ed-diff"
            />
          </div>

          <footer class="ed-savebar">
            <span>{{ formatCount(charCount) }} 字符</span>
            <i class="ed-sep" aria-hidden="true"></i>
            <span :class="{ bad: activePath === 'SKILL.md' && failedChecks.length }">{{ checkSummary }}</span>
            <span class="spacer"></span>
            <span class="ed-hint">⌘/Ctrl + S</span>
            <button class="ghost sm" type="button" :disabled="busy" @click="requestLeave">取消</button>
            <button class="primary sm" type="button" :disabled="!activeSaveable" @click="saveActive">
              {{ busy ? '保存中…' : saveLabel }}
            </button>
          </footer>
        </section>

        <aside class="skill-editor-rail">
          <section class="panel-card ed-card">
            <div class="panel-card-head">
              <div class="panel-card-title">
                <h3>检查</h3>
                <span
                  class="ed-badge"
                  :class="failedChecks.length ? 'warn' : 'ok'"
                >
                  {{ failedChecks.length ? `${failedChecks.length} 项待改` : '全部通过' }}
                </span>
              </div>
            </div>
            <div class="panel-card-body">
              <ul class="rail-checks" :class="{ dimmed: activePath !== 'SKILL.md' }">
                <li v-for="item in checks" :key="item.key" :class="{ failed: !item.ok }">
                  <span class="check-mark">{{ item.ok ? '✓' : '✕' }}</span>
                  {{ item.label }}
                </li>
              </ul>
              <p class="ed-hint">
                {{ activePath === 'SKILL.md' ? '正文不满足检查时无法保存。' : '文件保存不参与正文检查，也不产生新版本。' }}
              </p>
            </div>
          </section>

          <section v-if="!isCreate && activePath === 'SKILL.md'" class="panel-card ed-card">
            <div class="panel-card-head">
              <div class="panel-card-title">
                <h3>变更说明</h3>
                <span class="ed-hint">{{ changeNote.length }} / {{ MAX_NOTE }}</span>
              </div>
            </div>
            <div class="panel-card-body">
              <input
                v-model="changeNote"
                class="ed-input"
                :maxlength="MAX_NOTE"
                :disabled="readonly"
                placeholder="写进版本记录，方便回滚时辨认"
                @input="onEdited"
              />
              <p class="ed-hint">保存在版本时间线里；文件保存不产生版本，故不显示此项。</p>
            </div>
          </section>

          <section class="panel-card ed-card">
            <div class="panel-card-head">
              <div class="panel-card-title"><h3>基本信息</h3></div>
            </div>
            <div class="panel-card-body ed-form">
              <label class="ed-field">
                <span class="ed-label">技能 ID</span>
                <input
                  v-model="form.id"
                  class="ed-input mono"
                  :disabled="!isCreate || readonly"
                  placeholder="payroll-policy-notes"
                  autocomplete="off"
                  @input="onEdited"
                  @blur="idTouched = true"
                />
                <span v-if="isCreate && (idTouched || form.id) && idError" class="ed-field-error">
                  {{ idError }}
                </span>
                <span v-else-if="!isCreate" class="ed-hint">保存后 ID 不可修改。</span>
              </label>

              <label class="ed-field">
                <span class="ed-label">
                  说明
                  <span :class="{ bad: Boolean(descriptionError) }">
                    {{ form.description.length }} / {{ MAX_DESCRIPTION_LENGTH }}
                  </span>
                </span>
                <input
                  v-model="form.description"
                  class="ed-input"
                  :disabled="readonly"
                  placeholder="一句话说明技能用途"
                  @input="onEdited"
                />
                <span v-if="descriptionError" class="ed-field-error">{{ descriptionError }}</span>
              </label>

              <div class="exec-row">
                <div>
                  <strong>允许对话执行脚本</strong>
                  <p class="ed-hint">开启后模型可提交 scripts/ 下的脚本，执行仍需在对话中确认。</p>
                </div>
                <label class="exec-switch">
                  <input
                    v-model="form.executionEnabled"
                    type="checkbox"
                    :disabled="readonly"
                    @change="onEdited"
                  />
                  <span>{{ form.executionEnabled ? '已开启' : '已关闭' }}</span>
                </label>
              </div>
            </div>
          </section>

          <section class="panel-card ed-card">
            <div class="panel-card-head">
              <div class="panel-card-title"><h3>最近保存</h3></div>
            </div>
            <div class="panel-card-body">
              <dl v-if="!isCreate" class="ed-facts">
                <div>
                  <dt>作者</dt>
                  <dd>{{ authorName(detail?.updatedBy) }}</dd>
                </div>
                <div>
                  <dt>时间</dt>
                  <dd>{{ formatTimestamp(detail?.updatedAt) }}</dd>
                </div>
                <div>
                  <dt>版本</dt>
                  <dd class="mono">{{ isDb ? `v${versionNo}` : '—' }}</dd>
                </div>
              </dl>
              <p v-else class="ed-hint">首次保存后生成 v1。</p>
            </div>
          </section>
        </aside>
      </div>
    </template>

    <ConfirmDialog
      v-model:open="reloadOpen"
      title="重新加载最新版本？"
      confirm-text="丢弃并重新加载"
      danger
      @confirm="reloadLatest"
    >
      本地未保存的正文、说明与开关改动都会丢弃，随后载入服务器上的最新版本。
    </ConfirmDialog>

    <ConfirmDialog
      v-model:open="leaveOpen"
      title="离开编辑器？"
      confirm-text="离开并保留草稿"
      @confirm="confirmLeave"
    >
      有未保存的修改。离开后会保留一份本地草稿，下次打开可恢复；也可以先取消并保存。
    </ConfirmDialog>
  </div>
</template>

<style scoped>
.editor-page {
  padding-bottom: 48px;
}
.ed-skeleton {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.ed-crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}
.ed-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.ed-head h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
}
.ed-sub {
  margin: 5px 0 0;
  font-size: 12.5px;
  color: var(--muted);
}
.ed-sub .mono {
  color: var(--text-soft);
}
.ed-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ed-notice {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 12.5px;
  color: var(--muted);
}
.conflict-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border: 1px solid #f0d9a8;
  border-radius: 8px;
  background: var(--st-warn-soft, #fff6e3);
  color: var(--st-warn, #9a6700);
}
.conflict-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 12.5px;
  line-height: 1.55;
}
.conflict-actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.draft-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 12px;
  border: 1px dashed var(--border-strong);
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 12.5px;
  color: var(--text-soft);
}
.draft-actions {
  display: flex;
  gap: 8px;
}
.ed-inline-error {
  margin: 0;
  padding: 9px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 12.5px;
}
.ed-tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 460px;
  padding: 10px;
}
.ed-tree-head {
  padding: 4px 8px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}
.ed-group-label {
  padding: 10px 8px 2px;
  font-size: 11px;
  color: var(--faint);
}
.ed-file {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 30px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text-soft);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.ed-file:hover {
  background: var(--surface-3);
}
.ed-file.active {
  background: var(--primary-soft);
  border-color: #d5e2fb;
  color: var(--primary-dark);
}
.ed-file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ed-file-size {
  flex: none;
  font-size: 10.5px;
  color: var(--faint);
}
.ed-dirty {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--st-warn);
}
.ed-tree-hint {
  margin: auto 0 2px;
  padding: 10px 8px 2px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--muted);
}
.ed-main {
  display: flex;
  flex-direction: column;
  min-height: 460px;
  overflow: hidden;
}
.ed-filebar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}
.ed-filebar .mono {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-soft);
}
.ed-dirty-state {
  flex: none;
  font-size: 11.5px;
  color: var(--st-ok);
}
.ed-dirty-state.warn {
  color: var(--st-warn);
}
.ed-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
.ed-source {
  display: grid;
  flex: 1;
  grid-template-columns: 52px minmax(0, 1fr);
  min-height: 360px;
}
.ed-gutter {
  margin: 0;
  padding: 12px 8px;
  overflow: hidden;
  border-right: 1px solid var(--border);
  background: var(--surface-2);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  line-height: 1.65;
  text-align: right;
  color: var(--faint);
  user-select: none;
}
.ed-area {
  width: 100%;
  min-height: 360px;
  padding: 12px 14px;
  border: 0;
  outline: none;
  resize: none;
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre;
  overflow: auto;
}
.ed-area[readonly] {
  background: var(--surface-2);
}
.ed-preview {
  flex: 1;
  max-height: 62vh;
  overflow: auto;
}
.ed-diff {
  flex: 1;
  max-height: 62vh;
  overflow: hidden;
}
.ed-binary,
.ed-placeholder {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 40px 20px;
  text-align: center;
  font-size: 12.5px;
  color: var(--muted);
}
.ed-binary strong {
  font-size: 13px;
  color: var(--text-soft);
}
.ed-binary p {
  margin: 0;
  max-width: 380px;
  line-height: 1.6;
}
.ed-error {
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
.ed-savebar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  border-top: 1px solid var(--border);
  background: var(--surface-2);
  font-size: 12px;
  color: var(--muted);
}
.ed-savebar .spacer {
  flex: 1;
}
.ed-sep {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--faint);
}
.bad {
  color: var(--st-bad);
}
.ed-card {
  padding: 0;
}
.ed-card .panel-card-head {
  padding: 11px 14px;
}
.ed-card .panel-card-body {
  padding: 12px 14px;
}
.ed-badge {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 600;
}
.ed-badge.ok {
  background: var(--st-ok-soft, #e8f5ee);
  color: var(--st-ok, #18794e);
}
.ed-badge.warn {
  background: var(--st-warn-soft, #fff6e3);
  color: var(--st-warn, #9a6700);
}
.rail-checks {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
  font-size: 12.5px;
  color: var(--text-soft);
}
.rail-checks.dimmed {
  opacity: 0.55;
}
.rail-checks li {
  display: flex;
  align-items: center;
  gap: 6px;
}
.rail-checks li.failed {
  color: var(--st-bad, #c92a2a);
}
.check-mark {
  width: 14px;
  text-align: center;
  font-weight: 700;
}
.ed-hint {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--muted);
}
.ed-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ed-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ed-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}
.ed-label .bad {
  font-weight: 400;
}
.ed-input {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  background: var(--surface);
  font: inherit;
  font-size: 13px;
}
.ed-input:focus {
  outline: 2px solid rgb(47 111 235 / 18%);
  border-color: var(--primary);
}
.ed-input:disabled {
  background: var(--surface-3);
  color: var(--muted);
}
.ed-field-error {
  font-size: 11.5px;
  color: var(--st-bad);
}
.exec-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
.exec-row strong {
  font-size: 12.5px;
}
.exec-row .ed-hint {
  margin-top: 3px;
}
.exec-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  font-size: 12.5px;
  color: var(--muted);
}
.ed-facts {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 0;
  font-size: 11.5px;
}
.ed-facts div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.ed-facts dt {
  flex: none;
  color: var(--muted);
}
.ed-facts dd {
  margin: 0;
  text-align: right;
  word-break: break-word;
  color: var(--text-soft);
}
@media (max-width: 820px) {
  .ed-head-actions {
    width: 100%;
  }
}
</style>

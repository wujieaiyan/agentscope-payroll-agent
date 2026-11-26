<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, ApiError } from '../../../api/http';
import { toast } from '../../../stores/toast';
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue';
import EmptyState from '../../../components/ui/EmptyState.vue';
import SkeletonRows from '../../../components/ui/SkeletonRows.vue';
import SkillTile from './SkillTile.vue';
import SkillStatusPill from './SkillStatusPill.vue';
import AgentAvatars from './AgentAvatars.vue';
import { dataStatusMeta, displayUser, formatRelative } from './rules';
import { formatTimestamp } from '../../../utils/format';

const PAGE_SIZE = 20;
const DEFAULT_SORT = 'updated';
const COLUMNS_KEY = 'skills.columns';
const VIEW_KEYS = ['all', 'builtin', 'custom', 'overridden', 'bound', 'attention', 'unbound'];
const SORT_KEYS = ['updated', 'id', 'size'];
const DATA_FILTERS = ['all', 'ready', 'attention', 'none'];

const route = useRoute();
const router = useRouter();

const data = ref({
  writable: false,
  location: '',
  sandboxEnabled: true,
  skills: [],
  total: 0,
  page: 1,
  size: PAGE_SIZE,
  stats: {},
  bindings: {},
  dataStates: {},
  users: {},
});
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
const keywordInput = ref('');
const colOpen = ref(false);
const menuFor = ref('');
const deleteTarget = ref(null);
const deleteBusy = ref(false);
const searchEl = ref(null);
const colsetEl = ref(null);
let menuTrigger = null;
let searchTimer = null;
let loadSeq = 0;

const columns = reactive(readColumns());

const queryKeyword = computed(() => String(route.query.keyword || '').trim());
const queryView = computed(() =>
  VIEW_KEYS.includes(route.query.view) ? String(route.query.view) : 'all'
);
const querySort = computed(() =>
  SORT_KEYS.includes(route.query.sort) ? String(route.query.sort) : DEFAULT_SORT
);
const queryDataStatus = computed(() =>
  DATA_FILTERS.includes(route.query.dataStatus) ? String(route.query.dataStatus) : 'all'
);
const queryPage = computed(() => {
  const parsed = Number(route.query.page);
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 1;
});

const skills = computed(() => data.value.skills || []);
const total = computed(() => Number(data.value.total || 0));
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)));
const writable = computed(() => Boolean(data.value.writable));
const sandboxEnabled = computed(() => data.value.sandboxEnabled !== false);
const stats = computed(() => data.value.stats || {});
const views = computed(() => stats.value.views || {});

const statTotal = computed(() => Number(stats.value.totalSkills ?? total.value) || 0);
const statUsable = computed(() => Number(stats.value.usable || 0));
const statNeedsAttention = computed(() => Number(stats.value.needsAttention || 0));
const statUnbound = computed(() => Number(stats.value.unbound || 0));
const statUnknown = computed(() => Number(stats.value.unknownData || 0));
const unknownTooltip = computed(() =>
  sandboxEnabled.value
    ? '当前来源不提供数据状态，无法判定是否需要初始化'
    : '沙箱未启用，数据状态无法判定'
);

const viewChips = computed(() => [
  { key: 'all', label: '全部', count: Number(views.value.all ?? statTotal.value) },
  { key: 'builtin', label: '内置', count: Number(views.value.builtin || 0) },
  { key: 'custom', label: '自定义', count: Number(views.value.custom || 0) },
  { key: 'overridden', label: '已覆盖', count: Number(views.value.overridden || 0) },
]);

const hasFilters = computed(
  () =>
    Boolean(queryKeyword.value) ||
    queryView.value !== 'all' ||
    queryDataStatus.value !== 'all' ||
    querySort.value !== DEFAULT_SORT
);

const pageItems = computed(() => {
  const count = pageCount.value;
  const current = queryPage.value;
  if (count <= 7) return Array.from({ length: count }, (_, index) => index + 1);
  const items = [1];
  const start = Math.max(2, current - 1);
  const end = Math.min(count - 1, current + 1);
  if (start > 2) items.push('gap-start');
  for (let page = start; page <= end; page += 1) items.push(page);
  if (end < count - 1) items.push('gap-end');
  items.push(count);
  return items;
});

function readColumns() {
  try {
    const raw = JSON.parse(localStorage.getItem(COLUMNS_KEY) || '{}');
    return { source: Boolean(raw?.source), fileCount: Boolean(raw?.fileCount) };
  } catch (caught) {
    return { source: false, fileCount: false };
  }
}

function persistColumns() {
  try {
    localStorage.setItem(COLUMNS_KEY, JSON.stringify({ ...columns }));
  } catch (caught) {
    // 隐私模式下 localStorage 不可写，列设置降级为本次会话有效。
  }
}

function requestUrl() {
  const params = new URLSearchParams();
  if (queryKeyword.value) params.set('keyword', queryKeyword.value);
  if (queryView.value !== 'all') params.set('view', queryView.value);
  if (queryDataStatus.value !== 'all') params.set('dataStatus', queryDataStatus.value);
  if (querySort.value !== DEFAULT_SORT) params.set('sort', querySort.value);
  params.set('page', String(queryPage.value));
  params.set('size', String(PAGE_SIZE));
  return `/api/admin/skills?${params.toString()}`;
}

async function load() {
  const token = (loadSeq += 1);
  loading.value = true;
  error.value = '';
  try {
    const payload = (await api(requestUrl())) || {};
    if (token !== loadSeq) return;
    data.value = {
      writable: false,
      location: '',
      sandboxEnabled: true,
      skills: [],
      total: 0,
      page: 1,
      size: PAGE_SIZE,
      stats: {},
      bindings: {},
      dataStates: {},
      users: {},
      ...payload,
    };
    loaded.value = true;
    const pages = Math.max(1, Math.ceil(Number(payload.total || 0) / PAGE_SIZE));
    if (queryPage.value > pages) {
      const next = { ...route.query };
      if (pages > 1) next.page = String(pages);
      else delete next.page;
      router.replace({ name: 'skills', query: next });
    }
  } catch (caught) {
    if (token !== loadSeq) return;
    error.value = caught?.message || '技能列表加载失败';
  } finally {
    if (token === loadSeq) loading.value = false;
  }
}

function apply(patch = {}, resetPage = false) {
  const next = { ...route.query };
  for (const [key, value] of Object.entries(patch)) {
    const empty = value === undefined || value === null || value === '';
    const isDefault =
      (key === 'view' && value === 'all') ||
      (key === 'dataStatus' && value === 'all') ||
      (key === 'sort' && value === DEFAULT_SORT);
    if (empty || isDefault) delete next[key];
    else next[key] = String(value);
  }
  if (resetPage) delete next.page;
  const same =
    Object.keys(next).length === Object.keys(route.query).length &&
    Object.entries(next).every(([key, value]) => String(route.query[key]) === String(value));
  if (same) return;
  router.replace({ name: 'skills', query: next });
}

function setView(key) {
  apply({ view: key }, true);
}

function setDataStatus(key) {
  apply({ dataStatus: key }, true);
}

function setSort(key) {
  apply({ sort: key }, true);
}

function goPage(page) {
  const target = Math.min(Math.max(1, Number(page) || 1), pageCount.value);
  apply({ page: target > 1 ? target : undefined });
}

function commitKeyword() {
  clearTimeout(searchTimer);
  searchTimer = null;
  const value = keywordInput.value.trim();
  if (value === queryKeyword.value) return;
  apply({ keyword: value }, true);
}

function onKeywordInput() {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(commitKeyword, 250);
}

function clearFilters() {
  keywordInput.value = '';
  apply({ keyword: undefined, view: undefined, dataStatus: undefined, sort: undefined }, true);
}

function agentsOf(skill) {
  return data.value.bindings?.[skill.id] || [];
}

function boundCount(skill) {
  return agentsOf(skill).length;
}

function dataStateOf(skill) {
  return data.value.dataStates?.[skill.id] || null;
}

function statusOf(skill) {
  return dataStatusMeta({
    needsData: skill.needsData ?? null,
    dataState: dataStateOf(skill),
    sandboxEnabled: sandboxEnabled.value,
  });
}

function isAttention(skill) {
  return statusOf(skill).key === 'attention';
}

function sourceLabel(skill) {
  return { db: 'db', nacos: 'Nacos', builtin: '内置' }[skill.source] || skill.source || '—';
}

function versionLabel(skill) {
  const version = Number(skill.versionNo || 0);
  return version > 0 ? `v${version}` : '—';
}

function updatedUser(skill) {
  return displayUser(skill.updatedBy, data.value.users) || '系统导入';
}

function updatedTitle(skill) {
  return formatTimestamp(skill.updatedAt);
}

function canEdit(skill) {
  return writable.value && skill.source !== 'builtin';
}

function editDisabledReason(skill) {
  if (!writable.value) return '当前存储只读';
  if (skill.source === 'builtin') return '内置技能尚未落库，无法编辑';
  return '';
}

function canDelete(skill) {
  return writable.value && skill.source !== 'builtin' && boundCount(skill) === 0;
}

function deleteDisabledReason(skill) {
  if (!writable.value) return '当前存储只读';
  if (skill.source === 'builtin') return '内置技能尚未落库，无法删除';
  if (boundCount(skill) > 0) return `被 ${boundCount(skill)} 个智能体引用，需先解绑`;
  return '';
}

function openSkill(skill) {
  router.push({ name: 'skill-workbench', params: { id: skill.id, tab: 'overview' } });
}

function openTab(skill, tab) {
  router.push({ name: 'skill-workbench', params: { id: skill.id, tab } });
}

function openEdit(skill) {
  router.push(`/platform/skills/${encodeURIComponent(skill.id)}/edit`);
}

function goCreate() {
  router.push('/platform/skills/new');
}

function goImport() {
  router.push('/platform/skills/import');
}

function importVersion(skill) {
  router.push({ path: '/platform/skills/import', query: { id: skill.id } });
}

function toggleMenu(skill, event) {
  if (menuFor.value === skill.id) {
    closeMenu(true);
    return;
  }
  menuTrigger = event?.currentTarget || null;
  menuFor.value = skill.id;
}

function closeMenu(restoreFocus = false) {
  menuFor.value = '';
  if (restoreFocus && menuTrigger && document.contains(menuTrigger)) menuTrigger.focus();
  menuTrigger = null;
}

async function runMenu(action) {
  const trigger = menuTrigger;
  closeMenu(false);
  if (trigger && document.contains(trigger)) trigger.focus();
  await action();
}

async function copyId(skill) {
  try {
    await navigator.clipboard.writeText(skill.id);
    toast(`已复制技能 ID：${skill.id}`, 'success');
  } catch (caught) {
    toast('当前浏览器不允许写入剪贴板', 'error');
  }
}

function askDelete(skill) {
  if (!canDelete(skill)) return;
  deleteTarget.value = skill;
}

async function confirmDelete() {
  if (!deleteTarget.value || deleteBusy.value) return;
  deleteBusy.value = true;
  const target = deleteTarget.value;
  try {
    const revision = target.revision ? `?revision=${encodeURIComponent(target.revision)}` : '';
    await api(`/api/admin/skills/${encodeURIComponent(target.id)}${revision}`, { method: 'DELETE' });
    toast(`技能 ${target.id} 已删除`, 'success');
    deleteTarget.value = null;
    await load();
  } catch (caught) {
    if (caught instanceof ApiError && caught.status === 409) {
      toast('技能刚刚被他人修改，已刷新到最新版本', 'error');
      deleteTarget.value = null;
      await load();
    } else {
      toast(caught?.message || '删除失败', 'error');
    }
  } finally {
    deleteBusy.value = false;
  }
}

function isTypingTarget(target) {
  if (!target) return false;
  const tag = String(target.tagName || '').toUpperCase();
  return (
    tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || Boolean(target.isContentEditable)
  );
}

function onDocumentClick(event) {
  if (menuFor.value && !event.target.closest?.('.skill-menu')) menuFor.value = '';
  if (colOpen.value && colsetEl.value && !colsetEl.value.contains(event.target)) colOpen.value = false;
}

function onDocumentKeydown(event) {
  if (event.key === 'Escape') {
    if (menuFor.value) {
      closeMenu(true);
      return;
    }
    if (colOpen.value) {
      colOpen.value = false;
      return;
    }
  }
  if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
  if (isTypingTarget(event.target) || deleteTarget.value) return;
  event.preventDefault();
  searchEl.value?.focus();
  searchEl.value?.select?.();
}

watch(
  () => route.query,
  () => {
    if (keywordInput.value.trim() !== queryKeyword.value) keywordInput.value = queryKeyword.value;
    load();
  },
  { immediate: true }
);

onMounted(() => {
  keywordInput.value = queryKeyword.value;
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onDocumentKeydown);
});

onUnmounted(() => {
  clearTimeout(searchTimer);
  document.removeEventListener('click', onDocumentClick);
  document.removeEventListener('keydown', onDocumentKeydown);
});
</script>

<template>
  <div class="page skills-canvas">
    <header class="skills-head">
      <div>
        <h1>技能</h1>
        <p class="skills-sub">让智能体按固定流程和资料工作。保存即生成版本，可随时回滚。</p>
      </div>
      <div class="skills-head-actions">
        <button class="ghost" type="button" :disabled="!writable" @click="goImport">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 15V4M8 8l4-4 4 4M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4" />
          </svg>
          导入
        </button>
        <button class="primary" type="button" :disabled="!writable" @click="goCreate">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          新建技能
        </button>
      </div>
    </header>

    <div v-if="loaded && !writable" class="skills-readonly" role="status">
      当前技能存储只读，可以浏览与打开技能，新增、导入、编辑与删除均不可用。
    </div>

    <div v-if="loaded" class="skills-statline">
      <span><b class="stat-strong">{{ statTotal }}</b> 个技能</span>
      <i class="sep" aria-hidden="true"></i>
      <button type="button" class="stat-link" @click="setDataStatus('ready')">
        <i class="stat-dot ok" aria-hidden="true"></i>
        <b class="stat-strong">{{ statUsable }}</b> 可用
      </button>
      <i class="sep" aria-hidden="true"></i>
      <button
        type="button"
        class="stat-link warn"
        :title="statNeedsAttention ? '筛选需处理的技能' : '当前没有需处理的技能'"
        @click="setView('attention')"
      >
        <i class="stat-dot warn" aria-hidden="true"></i>
        <b class="stat-strong">{{ statNeedsAttention }}</b> 需处理
      </button>
      <i class="sep" aria-hidden="true"></i>
      <button type="button" class="stat-link" @click="setView('unbound')">
        <b class="stat-strong">{{ statUnbound }}</b> 未绑定
      </button>
      <a class="review-link" href="#" @click.prevent="setView('attention')">查看需处理 →</a>
      <span v-if="statUnknown > 0" class="stat-unknown" :title="unknownTooltip">
        · {{ statUnknown }} 未知
      </span>
    </div>

    <div class="skills-panel">
      <div class="skills-toolbar">
        <label class="skills-search">
          <svg class="search-icon" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.2-3.2" />
          </svg>
          <input
            ref="searchEl"
            v-model="keywordInput"
            class="search"
            type="search"
            placeholder="搜索名称、ID 或说明"
            aria-label="搜索技能"
            @input="onKeywordInput"
            @keyup.enter="commitKeyword"
          />
          <kbd>/</kbd>
        </label>

        <div class="skills-chips" role="group" aria-label="类型筛选">
          <button
            v-for="chip in viewChips"
            :key="chip.key"
            type="button"
            :class="{ active: queryView === chip.key }"
            :aria-pressed="queryView === chip.key"
            @click="setView(chip.key)"
          >
            {{ chip.label }}<span class="count">{{ chip.count }}</span>
          </button>
        </div>

        <div class="spacer"></div>

        <select
          :value="queryDataStatus"
          aria-label="数据状态"
          @change="setDataStatus($event.target.value)"
        >
          <option value="all">全部数据状态</option>
          <option value="ready">就绪</option>
          <option value="attention">需处理</option>
          <option value="none">无需数据</option>
        </select>

        <select :value="querySort" aria-label="排序" @change="setSort($event.target.value)">
          <option value="updated">最近更新</option>
          <option value="id">名称</option>
          <option value="size">大小</option>
        </select>

        <div ref="colsetEl" class="skills-colset">
          <button
            class="btn-icon"
            type="button"
            aria-label="列设置"
            aria-haspopup="dialog"
            :aria-expanded="colOpen"
            @click="colOpen = !colOpen"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <rect x="4" y="5" width="16" height="14" rx="2" />
              <path d="M10 5v14" />
            </svg>
          </button>
          <div v-if="colOpen" class="skills-colset-pop" role="dialog" aria-label="列设置">
            <label>
              <input v-model="columns.source" type="checkbox" @change="persistColumns" />
              来源
            </label>
            <label>
              <input v-model="columns.fileCount" type="checkbox" @change="persistColumns" />
              文件数
            </label>
          </div>
        </div>
      </div>

      <div v-if="error && !skills.length" class="skills-error" role="alert">
        <span>{{ error }}</span>
        <button class="subtle sm" type="button" @click="load">重试</button>
      </div>

      <div v-else-if="loading && !skills.length" class="skills-table-wrap">
        <SkeletonRows :rows="6" :columns="['34%', '10%', '9%', '7%', '15%', '10%']" />
      </div>

      <div v-else-if="skills.length" class="skills-table-wrap">
        <table class="skills-table" aria-label="技能列表" :aria-busy="loading">
          <thead>
            <tr>
              <th class="col-skill">技能</th>
              <th v-if="columns.source">来源</th>
              <th>状态</th>
              <th>绑定</th>
              <th>版本</th>
              <th v-if="columns.fileCount" class="num">文件数</th>
              <th>最近更新</th>
              <th class="col-actions"><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="skill in skills"
              :key="skill.id"
              :class="{ 'row-attention': isAttention(skill) }"
              @click="openSkill(skill)"
            >
              <td class="col-skill">
                <div class="skill-cell">
                  <SkillTile :id="skill.id" :description="skill.description" />
                  <div class="skill-cell-text">
                    <span class="skill-cell-id">{{ skill.id }}</span>
                    <span class="skill-cell-desc" :class="{ 'is-empty': !skill.description }">
                      {{ skill.description || '暂无说明' }}
                    </span>
                  </div>
                </div>
              </td>
              <td v-if="columns.source">
                <span class="tag">{{ sourceLabel(skill) }}</span>
              </td>
              <td>
                <button
                  type="button"
                  class="cell-button"
                  :title="statusOf(skill).tooltip"
                  @click.stop="openTab(skill, 'data')"
                >
                  <SkillStatusPill
                    :needs-data="skill.needsData ?? null"
                    :data-state="dataStateOf(skill)"
                    :sandbox-enabled="sandboxEnabled"
                  />
                </button>
              </td>
              <td>
                <button
                  type="button"
                  class="cell-button"
                  :title="boundCount(skill) ? '查看引用方' : '还没有智能体引用'"
                  @click.stop="openTab(skill, 'bindings')"
                >
                  <template v-if="boundCount(skill)">
                    <AgentAvatars :agents="agentsOf(skill)" />
                    <span class="bind-count">{{ boundCount(skill) }} 个</span>
                  </template>
                  <span v-else class="muted-dash">未绑定</span>
                </button>
              </td>
              <td>
                <span class="mono">{{ versionLabel(skill) }}</span>
              </td>
              <td v-if="columns.fileCount" class="num">
                <span class="mono">{{ skill.fileCount || 0 }}</span>
              </td>
              <td>
                <div class="updated-cell" :title="updatedTitle(skill)">
                  <span>{{ formatRelative(skill.updatedAt) }}</span>
                  <span class="user">· {{ updatedUser(skill) }}</span>
                </div>
              </td>
              <td class="col-actions">
                <div class="skill-actions">
                  <button
                    v-if="isAttention(skill)"
                    type="button"
                    class="link-btn warn"
                    @click.stop="openTab(skill, 'data')"
                  >
                    查看原因
                  </button>
                  <button v-else type="button" class="link-btn" @click.stop="openSkill(skill)">
                    打开
                  </button>
                  <div class="skill-menu">
                    <button
                      type="button"
                      class="btn-icon"
                      :aria-label="`${skill.id} 的更多操作`"
                      aria-haspopup="menu"
                      :aria-expanded="menuFor === skill.id"
                      @click.stop="toggleMenu(skill, $event)"
                    >
                      ⋯
                    </button>
                    <div v-if="menuFor === skill.id" class="row-menu" role="menu" @click.stop>
                      <button
                        type="button"
                        role="menuitem"
                        :disabled="!canEdit(skill)"
                        :title="editDisabledReason(skill)"
                        @click="runMenu(() => openEdit(skill))"
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        role="menuitem"
                        :disabled="!canEdit(skill)"
                        :title="editDisabledReason(skill)"
                        @click="runMenu(() => importVersion(skill))"
                      >
                        导入新版本
                      </button>
                      <button type="button" role="menuitem" @click="runMenu(() => copyId(skill))">
                        复制 ID
                      </button>
                      <div class="sep"></div>
                      <button
                        type="button"
                        role="menuitem"
                        class="danger"
                        :disabled="!canDelete(skill)"
                        :title="deleteDisabledReason(skill)"
                        @click="runMenu(() => askDelete(skill))"
                      >
                        删除
                      </button>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="skills-empty">
        <EmptyState
          v-if="hasFilters"
          title="没有匹配的技能"
          description="当前筛选条件下没有结果，清空筛选后重新查看。"
        >
          <template #actions>
            <button class="ghost" type="button" @click="clearFilters">清空筛选</button>
          </template>
        </EmptyState>
        <EmptyState v-else title="还没有技能" description="从空白新建、从模板开始，或导入一个技能包。">
          <template #actions>
            <button class="primary" type="button" :disabled="!writable" @click="goCreate">
              新建技能
            </button>
            <button class="ghost" type="button" :disabled="!writable" @click="goImport">
              导入技能
            </button>
          </template>
        </EmptyState>
      </div>

      <div v-if="skills.length" class="skills-pager">
        <span>共 {{ total }} 个 · 每页 {{ PAGE_SIZE }}</span>
        <div class="skills-pages" role="navigation" aria-label="分页">
          <button
            type="button"
            :disabled="queryPage <= 1 || loading"
            aria-label="上一页"
            @click="goPage(queryPage - 1)"
          >
            上一页
          </button>
          <template v-for="item in pageItems" :key="item">
            <span v-if="typeof item === 'string'" class="gap" aria-hidden="true">…</span>
            <button
              v-else
              type="button"
              :class="{ active: item === queryPage }"
              :aria-current="item === queryPage ? 'page' : undefined"
              :disabled="loading"
              @click="goPage(item)"
            >
              {{ item }}
            </button>
          </template>
          <button
            type="button"
            :disabled="queryPage >= pageCount || loading"
            aria-label="下一页"
            @click="goPage(queryPage + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      :open="Boolean(deleteTarget)"
      :title="deleteTarget ? `删除技能 ${deleteTarget.id}` : '删除技能'"
      confirm-text="删除"
      danger
      :require-text="deleteTarget ? deleteTarget.id : ''"
      :busy="deleteBusy"
      @update:open="deleteTarget = null"
      @confirm="confirmDelete"
    >
      <p>
        将删除 <strong class="mono">{{ deleteTarget?.id }}</strong>
        的正文、参考文件与脚本，删除后无法恢复。
      </p>
    </ConfirmDialog>
  </div>
</template>

<style scoped>
.skills-empty {
  padding: 40px 16px;
}
.skills-search kbd {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  min-width: 18px;
  padding: 0 4px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--surface-2);
  color: var(--faint);
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  pointer-events: none;
}
.skills-search input {
  padding-right: 30px;
}
.skills-statline .stat-strong {
  font-weight: 600;
  color: var(--text);
}
.skills-statline .stat-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.skills-statline .stat-dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: var(--faint);
}
.skills-statline .stat-dot.ok {
  background: var(--st-ok);
}
.skills-statline .stat-dot.warn {
  background: var(--st-warn);
}
.skills-statline .stat-unknown {
  color: var(--faint);
}
</style>

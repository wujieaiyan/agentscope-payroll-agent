<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, ApiError } from '../../../api/http';
import { toast } from '../../../stores/toast';
import { loadSkillEnv, useSkillEnv } from '../../../stores/skillEnv';
import { formatTimestamp } from '../../../utils/format';
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue';
import EmptyState from '../../../components/ui/EmptyState.vue';
import SkeletonRows from '../../../components/ui/SkeletonRows.vue';
import PanelCard from '../../../components/ui/PanelCard.vue';
import SegmentedTabs from '../../../components/ui/SegmentedTabs.vue';
import SkillTile from './SkillTile.vue';
import SkillStatusPill from './SkillStatusPill.vue';
import AgentAvatars from './AgentAvatars.vue';
import WorkbenchOverview from './tabs/WorkbenchOverview.vue';
import WorkbenchBody from './tabs/WorkbenchBody.vue';
import WorkbenchFiles from './tabs/WorkbenchFiles.vue';
import WorkbenchData from './tabs/WorkbenchData.vue';
import WorkbenchVersions from './tabs/WorkbenchVersions.vue';
import WorkbenchBindings from './tabs/WorkbenchBindings.vue';
import { dataStatusMeta, displayUser } from './rules';

const route = useRoute();
const router = useRouter();
const env = useSkillEnv();

const TAB_KEYS = ['overview', 'body', 'files', 'data', 'versions', 'bindings'];
const TAB_ALIASES = { references: 'files', scripts: 'files' };

const detail = ref(null);
const overview = ref(null);
const bindings = ref([]);
const loading = ref(true);
const error = ref('');
const overviewFailed = ref(false);
const overviewError = ref('');
const menuOpen = ref(false);
const deleteOpen = ref(false);
const deleteBusy = ref(false);
let menuTrigger = null;
let loadSeq = 0;

const skillId = computed(() => String(route.params.id || ''));

const activeTab = computed(() => {
  const raw = String(route.params.tab || 'overview');
  const requested = TAB_ALIASES[raw] || raw;
  return TAB_KEYS.includes(requested) ? requested : 'overview';
});

const users = computed(() => overview.value?.users || {});
const sandboxEnabled = computed(() =>
  overview.value ? overview.value.sandboxEnabled !== false : true
);
const writable = computed(() => {
  const flagged = overview.value?.identity?.writable;
  if (typeof flagged === 'boolean') return flagged;
  return Boolean(env.value?.writable);
});
const isDb = computed(() => detail.value?.source === 'db');

// 非 db 来源不提供数据状态，任何「无需数据」推断都会误导（规格 §9.3-8）。
const needsData = computed(() => {
  if (!isDb.value) return null;
  const value = overview.value?.health?.data?.needsData;
  if (value === true || value === false) return value;
  const fallback = detail.value?.needsData;
  return fallback === true || fallback === false ? fallback : null;
});
const dataState = computed(() => ({
  status: overview.value?.health?.data?.status,
  message: overview.value?.health?.data?.message,
}));
const dataMeta = computed(() =>
  dataStatusMeta({
    needsData: needsData.value,
    dataState: dataState.value,
    sandboxEnabled: sandboxEnabled.value,
  })
);
const declaredMaxBytes = computed(() =>
  Number(overview.value?.health?.data?.declaredMaxBytes || 0)
);
const observedAt = computed(() => Number(overview.value?.health?.data?.observedAt || 0));

const sourceLabel = computed(() => {
  const source = detail.value?.source || '';
  return { db: 'db', nacos: 'Nacos', builtin: '内置' }[source] || source || '未知';
});
const sourceBadge = computed(() => `${sourceLabel.value} · ${writable.value ? '可写' : '只读'}`);
const versionNo = computed(() => Number(detail.value?.versionNo || 0));

const scriptCount = computed(() => {
  if (!isDb.value) return Number(overview.value?.health?.scripts?.count ?? 0);
  return (detail.value?.files || []).filter((file) => file.kind === 'script').length;
});
const scriptLabel = computed(() => {
  if (scriptCount.value === 0) return '无需脚本';
  return detail.value?.executionEnabled ? '脚本已开启' : '脚本未开启';
});
const scriptTone = computed(() => {
  if (scriptCount.value === 0) return 'neutral';
  return detail.value?.executionEnabled ? 'ok' : 'dim';
});

const fileCount = computed(
  () => (detail.value?.files || []).filter((file) => file.path !== 'SKILL.md').length
);

// Nacos/内置没有文件包与版本行的可靠计数，按 §9.3-8 统一显示 —。
const filesBadge = computed(() => (isDb.value ? fileCount.value : '—'));
const versionsBadge = computed(() => (isDb.value ? versionNo.value : '—'));
const versionLabel = computed(() =>
  isDb.value && versionNo.value > 0 ? `v${versionNo.value}` : '—'
);

const tabs = computed(() => [
  { key: 'overview', label: '概览' },
  { key: 'body', label: '正文' },
  { key: 'files', label: '文件', count: filesBadge.value },
  { key: 'data', label: '数据', dot: dataMeta.value.key === 'attention' },
  { key: 'versions', label: '版本', count: versionsBadge.value },
  { key: 'bindings', label: '绑定', count: bindings.value.length },
]);

const canEdit = computed(
  () => writable.value && detail.value?.source !== 'builtin'
);
const editReason = computed(() => {
  if (!writable.value) return '当前存储只读';
  if (detail.value?.source === 'builtin') return '内置技能尚未落库，无法编辑';
  return '';
});
const canDelete = computed(() => canEdit.value && bindings.value.length === 0);
const deleteReason = computed(() => {
  if (!writable.value) return '当前存储只读，无法删除';
  if (detail.value?.source === 'builtin') return '内置技能尚未落库，无法删除';
  if (bindings.value.length > 0) {
    return `被 ${bindings.value.length} 个智能体引用，需先解绑`;
  }
  return '';
});

const identity = computed(() => overview.value?.identity || {});
const contentHash = computed(() => String(identity.value.contentHash || ''));
const shortHash = computed(() => contentHash.value.slice(0, 8));

function authorName(username) {
  return displayUser(username, users.value) || '系统导入';
}

function provenanceLine(who, at) {
  const time = formatTimestamp(at);
  if (!who && time === '—') return '—';
  return `${who ? authorName(who) : '系统导入'} · ${time}`;
}

function createdLine() {
  return provenanceLine(
    identity.value.createdBy ?? detail.value?.createdBy,
    identity.value.createdAt ?? detail.value?.createdAt
  );
}

function updatedLine() {
  return provenanceLine(
    identity.value.updatedBy ?? detail.value?.updatedBy,
    identity.value.updatedAt ?? detail.value?.updatedAt
  );
}

async function fetchAll(initial = false) {
  const id = skillId.value;
  if (!id) return;
  const token = (loadSeq += 1);
  if (initial) loading.value = true;
  error.value = '';
  const [detailResult, overviewResult, bindingsResult] = await Promise.allSettled([
    api(`/api/admin/skills/${encodeURIComponent(id)}`),
    api(`/api/admin/skills/${encodeURIComponent(id)}/overview`),
    api(`/api/admin/skills/${encodeURIComponent(id)}/bindings`),
  ]);
  if (token !== loadSeq) return;
  if (detailResult.status === 'rejected') {
    detail.value = null;
    overview.value = null;
    bindings.value = [];
    error.value = detailResult.reason?.message || '技能加载失败';
    loading.value = false;
    return;
  }
  detail.value = detailResult.value;
  if (overviewResult.status === 'fulfilled') {
    overview.value = overviewResult.value || {};
    overviewFailed.value = false;
    overviewError.value = '';
  } else {
    overview.value = null;
    overviewFailed.value = true;
    overviewError.value = overviewResult.reason?.message || '概览加载失败';
  }
  bindings.value =
    bindingsResult.status === 'fulfilled' ? bindingsResult.value?.bindings || [] : [];
  loading.value = false;
}

function goTab(key) {
  menuOpen.value = false;
  if (!TAB_KEYS.includes(key) || key === activeTab.value) return;
  router.replace({ name: 'skill-workbench', params: { id: skillId.value, tab: key } });
}

function goEdit() {
  if (!canEdit.value) return;
  router.push(`/platform/skills/${encodeURIComponent(skillId.value)}/edit`);
}

function openImport() {
  menuOpen.value = false;
  if (!canEdit.value) return;
  router.push({ path: '/platform/skills/import', query: { id: skillId.value } });
}

function toggleMenu(event) {
  if (menuOpen.value) {
    closeMenu(true);
    return;
  }
  menuTrigger = event?.currentTarget || null;
  menuOpen.value = true;
}

function closeMenu(restoreFocus = false) {
  menuOpen.value = false;
  if (restoreFocus && menuTrigger && document.contains(menuTrigger)) menuTrigger.focus();
  menuTrigger = null;
}

function askDelete() {
  closeMenu(false);
  if (!canDelete.value) return;
  deleteOpen.value = true;
}

async function confirmDelete() {
  if (deleteBusy.value) return;
  deleteBusy.value = true;
  try {
    const revision = detail.value?.revision;
    const query = revision ? `?revision=${encodeURIComponent(revision)}` : '';
    await api(`/api/admin/skills/${encodeURIComponent(skillId.value)}${query}`, {
      method: 'DELETE',
    });
    toast(`技能 ${skillId.value} 已删除`, 'success');
    deleteOpen.value = false;
    router.push('/platform/skills');
  } catch (caught) {
    if (caught instanceof ApiError && caught.status === 409) {
      toast('技能刚刚被他人修改，已刷新到最新版本', 'error');
      deleteOpen.value = false;
      await fetchAll(false);
    } else {
      toast(caught?.message || '删除失败', 'error');
    }
  } finally {
    deleteBusy.value = false;
  }
}

async function copyHash() {
  if (!contentHash.value) return;
  try {
    await navigator.clipboard.writeText(contentHash.value);
    toast('contentHash 已复制', 'success');
  } catch (caught) {
    toast('当前浏览器不允许写入剪贴板', 'error');
  }
}

function onDocumentClick(event) {
  if (menuOpen.value && !event.target.closest?.('.wb-actions')) closeMenu(false);
}

function onDocumentKeydown(event) {
  if (event.key === 'Escape' && menuOpen.value) closeMenu(true);
}

watch(
  () => route.params.id,
  () => {
    detail.value = null;
    overview.value = null;
    bindings.value = [];
    fetchAll(true);
  }
);

onMounted(() => {
  if (!env.value) loadSkillEnv();
  fetchAll(true);
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onDocumentKeydown);
});

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick);
  document.removeEventListener('keydown', onDocumentKeydown);
});
</script>

<template>
  <div class="page skills-canvas">
    <div v-if="loading && !detail" class="wb-skeleton">
      <SkeletonRows :rows="6" :columns="['30%', '14%', '12%', '18%']" />
    </div>

    <EmptyState v-else-if="error" title="无法加载该技能" :description="error">
      <template #actions>
        <button class="ghost" @click="fetchAll(true)">重试</button>
        <router-link class="button-link" to="/platform/skills">返回技能列表</router-link>
      </template>
    </EmptyState>

    <template v-else-if="detail">
      <nav class="wb-crumb" aria-label="面包屑">
        <router-link to="/platform/skills">技能</router-link>
        <span aria-hidden="true">/</span>
        <span class="mono">{{ detail.id }}</span>
      </nav>

      <header class="wb-head">
        <div class="wb-head-main">
          <SkillTile :id="detail.id" :description="detail.description" size="lg" />
          <div class="wb-head-text">
            <h1 class="mono">{{ detail.id }}</h1>
            <p v-if="detail.description" class="wb-desc">{{ detail.description }}</p>
            <div class="wb-meta">
              <span class="wb-pill">{{ detail.builtin ? '内置' : '自定义' }}</span>
              <span class="wb-pill">来源 {{ sourceLabel }}</span>
              <span class="wb-pill mono">{{ versionLabel }}</span>
              <SkillStatusPill
                :needs-data="needsData"
                :data-state="dataState"
                :sandbox-enabled="sandboxEnabled"
              />
              <span class="wb-pill" :class="scriptTone">{{ scriptLabel }}</span>
            </div>
          </div>
        </div>
        <div class="wb-actions">
          <button class="primary" :disabled="!canEdit" :title="editReason" @click="goEdit">
            编辑正文
          </button>
          <div class="row-actions">
            <button
              class="btn-icon"
              type="button"
              aria-label="更多操作"
              aria-haspopup="menu"
              :aria-expanded="menuOpen"
              @click.stop="toggleMenu"
            >
              ⋯
            </button>
            <div v-if="menuOpen" class="row-menu" role="menu">
              <button
                type="button"
                role="menuitem"
                :disabled="!canEdit"
                :title="editReason"
                @click.stop="openImport"
              >
                导入新版本
              </button>
              <div class="sep"></div>
              <button
                type="button"
                role="menuitem"
                class="danger"
                :disabled="!canDelete"
                :title="deleteReason"
                @click.stop="askDelete"
              >
                删除技能
              </button>
            </div>
          </div>
        </div>
      </header>

      <SegmentedTabs
        :tabs="tabs"
        :model-value="activeTab"
        aria-label="技能工作台"
        @update:model-value="goTab"
      />

      <div class="skill-wb">
        <div class="skill-wb-main">
          <div v-if="overviewFailed" class="wb-banner" role="alert">
            <span>概览加载失败：{{ overviewError }}</span>
            <button class="ghost sm" @click="fetchAll(false)">重试</button>
          </div>

          <WorkbenchOverview
            v-if="activeTab === 'overview'"
            :overview="overview"
            :detail="detail"
            :users="users"
            :bindings="bindings"
            :sandbox-enabled="sandboxEnabled"
            @go="goTab"
            @edit="goEdit"
          />
          <WorkbenchBody
            v-else-if="activeTab === 'body'"
            :detail="detail"
            :writable="writable"
            @edit="goEdit"
          />
          <WorkbenchFiles
            v-else-if="activeTab === 'files'"
            :skill-id="detail.id"
            :detail="detail"
            :writable="writable"
            @changed="fetchAll(false)"
          />
          <WorkbenchData
            v-else-if="activeTab === 'data'"
            :skill-id="detail.id"
            :source="detail.source"
            :needs-data="needsData"
            :declared-max-bytes="declaredMaxBytes"
            :observed-at="observedAt"
            :sandbox-enabled="sandboxEnabled"
          />
          <WorkbenchVersions
            v-else-if="activeTab === 'versions'"
            :skill-id="detail.id"
            :source="detail.source"
            :writable="writable"
            :users="users"
            :current-version-no="versionNo"
            @changed="fetchAll(false)"
          />
          <WorkbenchBindings
            v-else-if="activeTab === 'bindings'"
            :bindings="bindings"
          />
        </div>

        <aside class="skill-wb-rail" aria-label="技能标识与危险区">
          <PanelCard title="引用方">
            <div v-if="bindings.length" class="rail-agents">
              <AgentAvatars :agents="bindings" :max="3" />
              <span class="rail-agent-names">{{ bindings.map((item) => item.name).join('、') }}</span>
              <router-link class="link-btn" to="/platform/agents">去智能体页</router-link>
            </div>
            <div v-else class="rail-empty">
              <p>还没有智能体引用</p>
              <router-link class="link-btn" to="/platform/agents">去智能体页</router-link>
            </div>
          </PanelCard>

          <PanelCard title="标识">
            <dl class="rail-facts">
              <div>
                <dt>ID</dt>
                <dd class="mono">{{ detail.id }}</dd>
              </div>
              <div>
                <dt>来源</dt>
                <dd>{{ sourceBadge }}</dd>
              </div>
              <div>
                <dt>创建</dt>
                <dd>{{ createdLine() }}</dd>
              </div>
              <div>
                <dt>更新</dt>
                <dd>{{ updatedLine() }}</dd>
              </div>
            </dl>
            <div v-if="contentHash" class="rail-hash">
              <span>contentHash</span>
              <button
                type="button"
                class="mono"
                :title="`复制完整哈希：${contentHash}`"
                @click="copyHash"
              >
                {{ shortHash }}
              </button>
            </div>
          </PanelCard>

          <PanelCard title="危险区">
            <p class="rail-danger-note">
              {{ deleteReason || '删除后无法恢复，正文、文件与脚本一并移除。' }}
            </p>
            <button
              class="danger"
              type="button"
              :disabled="!canDelete"
              :title="deleteReason"
              @click="askDelete"
            >
              删除技能
            </button>
          </PanelCard>
        </aside>
      </div>

      <ConfirmDialog
        :open="deleteOpen"
        :title="`删除技能 ${detail.id}`"
        confirm-text="删除技能"
        danger
        :require-text="detail.id"
        :busy="deleteBusy"
        @update:open="deleteOpen = false"
        @confirm="confirmDelete"
      >
        <p>
          将删除 <strong class="mono">{{ detail.id }}</strong> 的正文、参考文件、脚本与数据，删除后无法恢复。
        </p>
        <p v-if="bindings.length" class="danger-note">
          该技能正被 {{ bindings.map((agent) => agent.name).join('、') }} 使用，请先解绑。
        </p>
      </ConfirmDialog>
    </template>
  </div>
</template>

<style scoped>
.wb-skeleton {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.wb-crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}
.wb-crumb a {
  color: var(--muted);
  text-decoration: none;
}
.wb-crumb a:hover {
  color: var(--primary);
}
.wb-crumb .mono {
  color: var(--text-soft);
  word-break: break-all;
}
.wb-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.wb-head-main {
  display: flex;
  gap: 12px;
  flex: 1;
  min-width: 0;
}
.wb-head-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.wb-head-text h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 600;
  line-height: 1.3;
  word-break: break-all;
}
.wb-desc {
  margin: 0;
  max-width: 70ch;
  font-size: 12.5px;
  color: var(--muted);
}
.wb-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 2px;
}
.wb-pill {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-2);
  font-size: 11.5px;
  color: var(--text-soft);
  white-space: nowrap;
}
.wb-pill.ok {
  border-color: transparent;
  background: var(--st-ok-soft, #e8f5ee);
  color: var(--st-ok, #18794e);
}
.wb-pill.dim,
.wb-pill.neutral {
  border-color: transparent;
  background: var(--surface-3);
  color: var(--muted);
}
.wb-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.row-actions {
  position: relative;
}
.wb-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 12.5px;
}
.rail-agents {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rail-agent-names {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-soft);
  word-break: break-word;
}
.rail-empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12.5px;
  color: var(--muted);
}
.rail-empty p {
  margin: 0;
}
.rail-facts {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 11.5px;
}
.rail-facts div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.rail-facts dt {
  flex: none;
  color: var(--muted);
}
.rail-facts dd {
  margin: 0;
  text-align: right;
  word-break: break-word;
  color: var(--text-soft);
}
.rail-hash {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--muted);
}
.rail-hash button {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--primary);
  font-size: 11.5px;
  cursor: pointer;
}
.rail-hash button:hover {
  text-decoration: underline;
}
.rail-danger-note {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--muted);
}
.danger-note {
  margin-top: 8px;
  color: var(--st-bad, #c92a2a);
}
.button-link {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  color: var(--text);
  font-size: 13px;
  text-decoration: none;
}
.button-link:hover {
  border-color: var(--primary);
  color: var(--primary);
}
</style>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { api, ApiError } from '../../../../api/http';
import { useModalFocus } from '../../../../composables/useModalFocus';
import { toast } from '../../../../stores/toast';
import { formatCount, formatTimestamp } from '../../../../utils/format';
import ConfirmDialog from '../../../../components/ui/ConfirmDialog.vue';
import EmptyState from '../../../../components/ui/EmptyState.vue';
import PanelCard from '../../../../components/ui/PanelCard.vue';
import { displayUser, formatRelative } from '../rules';

const props = defineProps({
  skillId: { type: String, required: true },
  source: { type: String, default: '' },
  writable: { type: Boolean, default: false },
  users: { type: Object, default: () => ({}) },
  currentVersionNo: { type: Number, default: 0 },
});
const emit = defineEmits(['changed']);

const versions = ref([]);
const loading = ref(false);
const error = ref('');
const viewTarget = ref(null);
const viewLoading = ref(false);
const viewError = ref('');
const viewPanel = ref(null);
const rollbackTarget = ref(null);
const rollbackBusy = ref(false);

const isDb = computed(() => props.source === 'db');
const currentVersion = computed(
  () =>
    Number(props.currentVersionNo) ||
    Number(versions.value.find((version) => version.current)?.versionNo || 0)
);

useModalFocus(viewPanel, {
  active: () => Boolean(viewTarget.value),
  onEscape: closeView,
  enabled: () => !rollbackTarget.value,
});

async function loadVersions() {
  if (!isDb.value) return;
  loading.value = true;
  error.value = '';
  try {
    const payload = await api(
      `/api/admin/skills/${encodeURIComponent(props.skillId)}/versions`
    );
    versions.value = payload?.versions || [];
  } catch (caught) {
    error.value = caught.message;
  } finally {
    loading.value = false;
  }
}

async function openVersion(version) {
  viewTarget.value = { ...version, content: version.content || '' };
  viewError.value = '';
  viewLoading.value = true;
  try {
    const payload = await api(
      `/api/admin/skills/${encodeURIComponent(props.skillId)}/versions/${version.versionNo}`
    );
    if (viewTarget.value?.versionNo !== version.versionNo) return;
    viewTarget.value = payload;
  } catch (caught) {
    viewError.value = caught.message;
    toast(caught.message, 'error');
  } finally {
    viewLoading.value = false;
  }
}

function closeView() {
  viewTarget.value = null;
  viewError.value = '';
}

function askRollback(version) {
  if (!version || version.current || !props.writable) return;
  rollbackTarget.value = version;
}

async function confirmRollback() {
  if (!rollbackTarget.value || rollbackBusy.value) return;
  const target = rollbackTarget.value;
  rollbackBusy.value = true;
  try {
    const revision = '';
    const detail = await api(`/api/admin/skills/${encodeURIComponent(props.skillId)}`);
    const token = detail?.revision || revision;
    const query = token ? `?revision=${encodeURIComponent(token)}` : '';
    await api(
      `/api/admin/skills/${encodeURIComponent(props.skillId)}/versions/${target.versionNo}/rollback${query}`,
      { method: 'POST' }
    );
    toast(`已回滚到 v${target.versionNo}`, 'success');
    rollbackTarget.value = null;
    closeView();
    await loadVersions();
    emit('changed');
  } catch (caught) {
    if (caught instanceof ApiError && caught.status === 409) {
      toast('他人已保存新版本，已刷新版本列表，请重试', 'error');
      rollbackTarget.value = null;
      await loadVersions();
    } else {
      toast(caught.message, 'error');
    }
  } finally {
    rollbackBusy.value = false;
  }
}

function authorName(username) {
  return displayUser(username, props.users) || '未知操作者';
}

watch(
  () => props.skillId,
  () => {
    versions.value = [];
    closeView();
    rollbackTarget.value = null;
    loadVersions();
  }
);

onMounted(loadVersions);
</script>

<template>
  <PanelCard title="版本">
    <template #hint>
      <span class="hint">{{ isDb ? `${versions.length} 个版本` : '当前来源不提供版本' }}</span>
    </template>
    <template #actions>
      <button class="ghost sm" type="button" :disabled="loading || !isDb" @click="loadVersions">
        刷新
      </button>
    </template>

    <template v-if="isDb">
      <div v-if="error" class="inline-banner" role="alert">
        <span>{{ error }}</span>
        <button class="subtle sm" @click="loadVersions">重试</button>
      </div>
      <p class="version-tip">
        每个版本对应一次正文保存；回滚会追加一条新版本并把指针移过去。仅改文件不产生版本。
      </p>
      <ul v-if="versions.length" class="version-list">
        <li
          v-for="version in versions"
          :key="version.versionNo"
          class="version-row"
          :class="{ current: version.current }"
        >
          <span class="version-no mono">v{{ version.versionNo }}</span>
          <div class="version-main">
            <div class="version-note">{{ version.changeNote || '未填写变更说明' }}</div>
            <div class="version-meta">
              {{ authorName(version.createdBy) }} · {{ formatRelative(version.createdAt) }} ·
              {{ formatCount(version.contentLength) }} 字符 ·
              <span :title="formatTimestamp(version.createdAt)">{{ formatTimestamp(version.createdAt) }}</span>
            </div>
          </div>
          <span v-if="version.current" class="tag tag--accent">当前</span>
          <div class="version-actions">
            <button class="ghost sm" type="button" @click="openVersion(version)">查看</button>
            <button
              v-if="!version.current"
              class="ghost sm"
              type="button"
              :disabled="!writable || rollbackBusy"
              :title="writable ? '' : '当前存储只读'"
              @click="askRollback(version)"
            >
              回滚
            </button>
          </div>
        </li>
      </ul>
      <div v-else-if="loading" class="muted version-loading">加载中…</div>
      <EmptyState
        v-else
        title="还没有版本记录"
        description="保存正文后会生成第一个版本。"
      />
    </template>
    <EmptyState
      v-else
      title="当前来源不提供版本时间线"
      description="Nacos 与内置来源没有版本行，因此不展示版本与回滚入口。"
    />
  </PanelCard>

  <Teleport to="body">
    <div v-if="viewTarget" class="modal-mask" @click.self="closeView">
      <div
        ref="viewPanel"
        class="modal version-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="version-modal-title"
        tabindex="-1"
      >
        <div class="modal-head">
          <h3 id="version-modal-title">
            v{{ viewTarget.versionNo }} ·
            {{ viewTarget.current ? '当前版本' : '历史版本' }}
          </h3>
          <button class="subtle sm" type="button" @click="closeView">关闭</button>
        </div>
        <div class="modal-body">
          <p class="version-modal-meta">
            {{ viewTarget.changeNote || '未填写变更说明' }} · {{ authorName(viewTarget.createdBy) }} ·
            {{ formatTimestamp(viewTarget.createdAt) }} · {{ formatCount(viewTarget.contentLength) }} 字符
          </p>
          <div v-if="viewLoading" class="muted">加载中…</div>
          <div v-else-if="viewError" class="inline-banner" role="alert">
            <span>{{ viewError }}</span>
            <button class="subtle sm" @click="openVersion(viewTarget)">重试</button>
          </div>
          <pre v-else class="trace-pre version-pre">{{ viewTarget.content || '（空）' }}</pre>
        </div>
        <div class="modal-foot">
          <button class="ghost" type="button" @click="closeView">关闭</button>
          <button
            v-if="!viewTarget.current"
            class="primary"
            type="button"
            :disabled="!writable || rollbackBusy"
            :title="writable ? '' : '当前存储只读'"
            @click="askRollback(viewTarget)"
          >
            回滚到此版本
          </button>
        </div>
      </div>
    </div>
  </Teleport>

  <ConfirmDialog
    :open="Boolean(rollbackTarget)"
    :title="rollbackTarget ? `回滚到 v${rollbackTarget.versionNo}` : '回滚版本'"
    :confirm-text="rollbackTarget ? `回滚到 v${rollbackTarget.versionNo}` : '回滚'"
    :busy="rollbackBusy"
    @update:open="rollbackTarget = null"
    @confirm="confirmRollback"
  >
    <p>
      将追加一条新版本（v{{ currentVersion + 1 }}），把正文与参考文件清单恢复成 v{{
        rollbackTarget?.versionNo
      }}
      的快照。
    </p>
    <p>不会改动：说明、执行开关。</p>
  </ConfirmDialog>
</template>

<style scoped>
.version-tip {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--muted);
}
.version-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
.version-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.version-row:last-child {
  border-bottom: 0;
}
.version-row.current {
  background: var(--surface-2);
}
.version-no {
  flex: none;
  width: 44px;
  color: var(--text-soft);
  font-size: 12.5px;
}
.version-main {
  flex: 1;
  min-width: 0;
}
.version-note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}
.version-meta {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--muted);
}
.version-actions {
  display: inline-flex;
  gap: 6px;
  flex: none;
}
.version-loading {
  padding: 16px;
  font-size: 12.5px;
}
.version-modal {
  width: min(860px, 96vw);
}
.version-modal-meta {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
.version-pre {
  margin: 0;
  max-height: 62vh;
  overflow: auto;
}
.inline-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 12.5px;
}
</style>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import { api } from '../../../api/http';
import { toast } from '../../../stores/toast';
import { formatSize, formatTimestamp } from '../../../utils/format';
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue';
import { dataStatusMeta } from './rules';

const DEFAULT_DECLARED = 2 * 1024 * 1024 * 1024;
const POLL_INTERVAL = 5000;

const props = defineProps({
  skillId: { type: String, required: true },
  needsData: { type: Boolean, default: null },
  declaredMaxBytes: { type: Number, default: 0 },
  observedAt: { type: Number, default: 0 },
  sandboxEnabled: { type: Boolean, default: true },
});

const state = ref(null);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const logOpen = ref(false);
const pending = ref('');
let pollTimer = null;

const status = computed(() => state.value?.status || 'UNKNOWN');
const effectiveNeedsData = computed(() => {
  if (props.needsData !== null && props.needsData !== undefined) return props.needsData;
  if (state.value?.requiresData !== undefined) return Boolean(state.value.requiresData);
  return null;
});
const meta = computed(() =>
  dataStatusMeta({
    needsData: effectiveNeedsData.value,
    dataState: { status: status.value, message: state.value?.message || '' },
    sandboxEnabled: props.sandboxEnabled,
  })
);
const tone = computed(
  () => ({ ok: 'ok', warn: 'warn', danger: 'bad', neutral: 'dim', dim: 'dim' }[meta.value.tone] || 'dim')
);
const requiresData = computed(() => effectiveNeedsData.value === true);
const showUsage = computed(() => effectiveNeedsData.value === true);
const capBytes = computed(
  () => Number(props.declaredMaxBytes || state.value?.declaredMaxBytes || DEFAULT_DECLARED)
);
const usageBytes = computed(() => Number(state.value?.sizeBytes || 0));
const usagePercent = computed(() =>
  capBytes.value > 0 ? Math.min(100, Math.round((usageBytes.value / capBytes.value) * 100)) : 0
);
const lastRunAt = computed(() => state.value?.finishedAt || state.value?.startedAt || 0);
const observedAtValue = computed(
  () => Number(state.value?.observedAt || props.observedAt || 0)
);
const logLines = computed(() => {
  const text = (state.value?.logTail || '').trim();
  if (!text) return [];
  return text.split(/\r?\n/).filter((line) => line.trim()).slice(-20);
});
const initLabel = computed(() => {
  if (busy.value) return '提交中…';
  if (meta.value.key === 'ready') return '重建数据';
  if (meta.value.key === 'attention') return '重新初始化';
  return '初始化数据';
});
const canInit = computed(
  () =>
    props.sandboxEnabled &&
    requiresData.value &&
    !busy.value &&
    status.value !== 'INITIALIZING'
);
const canRemove = computed(
  () =>
    props.sandboxEnabled &&
    !busy.value &&
    status.value !== 'UNKNOWN' &&
    status.value !== 'INITIALIZING'
);

function stopPolling() {
  clearTimeout(pollTimer);
  pollTimer = null;
}

function shouldPoll() {
  return props.sandboxEnabled && (status.value === 'INITIALIZING' || status.value === 'BUSY');
}

async function load(silent = false) {
  if (!silent) loading.value = true;
  error.value = '';
  try {
    state.value = await api(`/api/admin/skills/${encodeURIComponent(props.skillId)}/data`);
    stopPolling();
    if (shouldPoll()) pollTimer = setTimeout(() => load(true), POLL_INTERVAL);
  } catch (caught) {
    error.value = caught.message;
    stopPolling();
  } finally {
    loading.value = false;
  }
}

function askInit() {
  if (!canInit.value) return;
  pending.value = status.value === 'READY' ? 'init-rebuild' : 'init';
}

function askRemove() {
  if (!canRemove.value) return;
  pending.value = 'remove';
}

async function confirmPending() {
  if (!pending.value || busy.value) return;
  const action = pending.value;
  busy.value = true;
  try {
    if (action === 'remove') {
      state.value = await api(`/api/admin/skills/${encodeURIComponent(props.skillId)}/data`, {
        method: 'DELETE',
      });
      logOpen.value = false;
      toast('技能数据已删除', 'success');
    } else {
      state.value = await api(`/api/admin/skills/${encodeURIComponent(props.skillId)}/data/init`, {
        method: 'POST',
        body: { overwrite: action === 'init-rebuild' },
      });
      toast('已提交初始化，完成后自动刷新', 'success');
    }
    pending.value = '';
    stopPolling();
    if (shouldPoll()) pollTimer = setTimeout(() => load(true), POLL_INTERVAL);
  } catch (caught) {
    toast(caught.message, 'error');
  } finally {
    busy.value = false;
  }
}

watch(
  () => props.skillId,
  () => load()
);

load();
onUnmounted(stopPolling);

const confirmCopy = computed(() => {
  if (pending.value === 'remove') {
    return {
      title: '删除技能数据',
      confirmText: '删除数据',
      danger: true,
      body: `将清空 ${props.skillId} 的 data/ 工作区，技能正文与文件包不受影响；下次执行脚本时会重新初始化。`,
    };
  }
  if (pending.value === 'init-rebuild') {
    return {
      title: '重建技能数据',
      confirmText: '清空并重建',
      danger: true,
      body: '当前数据已就绪，重建会先清空 data/ 再执行初始化脚本，已有数据不可恢复。',
    };
  }
  return {
    title: '初始化技能数据',
    confirmText: '开始初始化',
    danger: false,
    body: `将在独立初始化容器中执行 ${state.value?.setupPath || '初始化脚本'}，生成的 data/ 会持久保留。`,
  };
});
</script>

<template>
  <div class="data-panel">
    <div v-if="!sandboxEnabled" class="sandbox-note" role="status">
      沙箱未启用，数据查询与初始化不可用（检查 PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL）。
    </div>

    <div v-if="error" class="inline-banner" role="alert">
      <span>{{ error }}</span>
      <button class="subtle sm" @click="load()">重试</button>
    </div>

    <div v-if="loading && !state" class="data-card muted">加载中…</div>

    <template v-else-if="state">
      <div class="data-card">
        <div class="data-status">
          <span class="status" :class="tone">
            <span class="dot" :class="tone"></span>{{ meta.label }}
          </span>
          <span v-if="state.message" class="data-message">{{ state.message }}</span>
        </div>
        <dl class="data-facts">
          <div class="fact-usage">
            <dt>数据大小</dt>
            <dd>
              {{ showUsage ? formatSize(usageBytes) : '—' }}
              <span v-if="showUsage" class="muted">/ {{ formatSize(capBytes) }}</span>
            </dd>
            <div v-if="showUsage" class="usage-bar" role="presentation">
              <i :style="{ width: `${usagePercent}%` }" />
            </div>
            <p v-if="showUsage" class="fact-note">声明值，非沙箱配额</p>
          </div>
          <div>
            <dt>初始化脚本</dt>
            <dd class="mono">{{ state.setupPath || '—' }}</dd>
          </div>
          <div>
            <dt>上次同步</dt>
            <dd>{{ observedAtValue ? formatTimestamp(observedAtValue) : '—' }}</dd>
          </div>
          <div>
            <dt>最后初始化</dt>
            <dd>{{ lastRunAt ? formatTimestamp(lastRunAt) : '—' }}</dd>
          </div>
          <div>
            <dt>退出码</dt>
            <dd>{{ state.exitCode === null || state.exitCode === undefined ? '—' : state.exitCode }}</dd>
          </div>
        </dl>
        <div class="data-actions">
          <button
            class="primary sm"
            :disabled="!canInit"
            :title="sandboxEnabled ? '' : '沙箱未启用'"
            @click="askInit"
          >
            {{ initLabel }}
          </button>
          <button
            class="subtle sm"
            :disabled="!canRemove"
            :title="sandboxEnabled ? '' : '沙箱未启用'"
            @click="askRemove"
          >
            删除数据
          </button>
          <button class="subtle sm" :disabled="loading" @click="load()">刷新状态</button>
        </div>
      </div>

      <p v-if="effectiveNeedsData === false" class="file-hint warn">
        该技能未声明 <code>setup</code> 或 <code>setup.sh</code>，无需初始化数据。
      </p>
      <p v-else-if="effectiveNeedsData === null" class="file-hint warn">
        当前来源不提供数据状态，无法判断是否需要初始化。
      </p>
      <p v-else class="file-hint">
        setup 在独立初始化容器中执行（允许联网）；生成的 <code>data/</code> 会持久保留，并在脚本运行时只读挂载到容器内
        <code>/data</code>。
      </p>

      <div v-if="logLines.length" class="log-block">
        <button class="log-toggle" type="button" :aria-expanded="logOpen" @click="logOpen = !logOpen">
          <span>{{ logOpen ? '收起' : '展开' }}初始化容器日志（尾部 {{ logLines.length }} 行）</span>
          <span class="log-caret" :class="{ open: logOpen }">›</span>
        </button>
        <pre v-if="logOpen" class="trace-pre log-pre">{{ logLines.join('\n') }}</pre>
      </div>
    </template>

    <ConfirmDialog
      :open="Boolean(pending)"
      :title="confirmCopy.title"
      :confirm-text="confirmCopy.confirmText"
      :danger="confirmCopy.danger"
      :busy="busy"
      @update:open="pending = ''"
      @confirm="confirmPending"
    >
      <p>{{ confirmCopy.body }}</p>
    </ConfirmDialog>
  </div>
</template>

<style scoped>
.data-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.data-card {
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
}
.sandbox-note {
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 12.5px;
  color: var(--muted);
}
.data-status {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.data-message {
  color: var(--muted);
  font-size: 12px;
}
.data-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px 20px;
  margin: 14px 0 0;
}
.data-facts > div {
  min-width: 0;
}
.data-facts dt {
  color: var(--muted);
  font-size: 11.5px;
}
.data-facts dd {
  margin: 2px 0 0;
  font-size: 13px;
  word-break: break-all;
}
.fact-usage {
  min-width: 180px;
}
.usage-bar {
  height: 5px;
  margin-top: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--surface-3);
}
.usage-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--primary);
}
.fact-note {
  margin: 4px 0 0;
  font-size: 10.5px;
  color: var(--faint);
}
.data-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.inline-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 13px;
}
.log-block {
  margin-top: 4px;
}
.log-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-2);
  color: var(--text-soft);
  font-size: 12px;
  cursor: pointer;
}
.log-caret {
  transition: transform 120ms ease;
}
.log-caret.open {
  transform: rotate(90deg);
}
.log-pre {
  margin: 8px 0 0;
  max-height: 320px;
}
</style>

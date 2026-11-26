<script setup>
import { computed } from 'vue';
import PanelCard from '../../../../components/ui/PanelCard.vue';
import EmptyState from '../../../../components/ui/EmptyState.vue';
import AgentAvatars from '../AgentAvatars.vue';
import SkillStatusPill from '../SkillStatusPill.vue';
import { displayUser, formatRelative } from '../rules';
import { formatSize } from '../../../../utils/format';

const props = defineProps({
  overview: { type: Object, default: null },
  detail: { type: Object, default: null },
  users: { type: Object, default: () => ({}) },
  bindings: { type: Array, default: () => [] },
  sandboxEnabled: { type: Boolean, default: true },
});
const emit = defineEmits(['go', 'edit']);

const DEFAULT_DECLARED = 2 * 1024 * 1024 * 1024;
const STATUS_TEXT = {
  READY: '数据就绪',
  INITIALIZING: '初始化中',
  BUSY: '初始化中',
  FAILED: '初始化失败',
  UNAVAILABLE: '沙箱不可用',
  INVALID: '请求无效',
  UNKNOWN: '未初始化',
};

const isDb = computed(() => props.detail?.source === 'db');
const health = computed(() => props.overview?.health || {});
const dataHealth = computed(() => health.value.data || {});
const scriptHealth = computed(() => health.value.scripts || {});
const bindingHealth = computed(() => health.value.bindings || {});
const checklist = computed(() => props.overview?.checklist || []);
const timeline = computed(() => props.overview?.timeline || []);

// 非 db 来源不提供数据状态，任何「无需数据」推断都会误导（规格 §9.3-8）。
const needsData = computed(() =>
  isDb.value ? dataHealth.value.needsData ?? null : null
);
const dataState = computed(() => ({
  status: dataHealth.value.status,
  message: dataHealth.value.message,
}));
const declaredMax = computed(
  () => Number(dataHealth.value.declaredMaxBytes || DEFAULT_DECLARED)
);
const usageBytes = computed(() => Number(dataHealth.value.sizeBytes || 0));
const usagePercent = computed(() =>
  Math.min(100, Math.round((usageBytes.value / declaredMax.value) * 100))
);
const lastInit = computed(
  () => Number(dataHealth.value.finishedAt || dataHealth.value.startedAt || 0)
);
const initDuration = computed(() => {
  const started = Number(dataHealth.value.startedAt || 0);
  const finished = Number(dataHealth.value.finishedAt || 0);
  if (!started || !finished || finished < started) return '';
  const millis = finished - started;
  if (millis < 1000) return `${millis} 毫秒`;
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)} 秒`;
  return `${Math.round(millis / 60_000)} 分钟`;
});
const canRebuild = computed(
  () => props.sandboxEnabled && needsData.value !== false && needsData.value !== null
);

const checklistDone = computed(() => checklist.value.filter((item) => item.ok).length);
const checklistTotal = computed(() => checklist.value.length || 4);

function actionLabel(action) {
  return { body: '去编辑', files: '去文件', data: '去数据', bindings: '去绑定' }[action] || '去处理';
}

function runAction(item) {
  if (item.action === 'body') emit('edit');
  else emit('go', item.action);
}

function checkDetail(item) {
  // 非 db 来源没有可靠的文件数与数据态，避免用 0 或 UNKNOWN 误导（规格 §9.3-8）。
  if (!isDb.value && (item.key === 'files' || item.key === 'data')) return '—';
  if (item.key === 'data' && STATUS_TEXT[item.detail]) return STATUS_TEXT[item.detail];
  return item.detail;
}

function authorName(username) {
  return displayUser(username, props.users) || '系统导入';
}

function relative(ts) {
  return ts ? formatRelative(ts) : '—';
}
</script>

<template>
  <div class="wb-overview">
    <PanelCard title="健康">
      <div class="skill-health-grid">
        <article class="health-card">
          <div class="health-head">
            <span class="health-label">数据</span>
            <SkillStatusPill
              :needs-data="needsData"
              :data-state="dataState"
              :sandbox-enabled="sandboxEnabled"
            />
          </div>
          <template v-if="needsData === false">
            <p class="health-note">该技能未声明初始化脚本，无需数据。</p>
          </template>
          <template v-else-if="needsData === null">
            <p class="health-note">当前来源不提供数据状态。</p>
          </template>
          <template v-else>
            <p class="health-usage">
              <strong>{{ formatSize(usageBytes) }}</strong>
              <span>/ {{ formatSize(declaredMax) }}</span>
            </p>
            <div class="usage-bar" role="presentation">
              <i :style="{ width: `${usagePercent}%` }" />
            </div>
            <p class="health-meta">
              上次初始化 {{ lastInit ? formatRelative(lastInit) : '—' }}
              <template v-if="initDuration"> · 用时 {{ initDuration }}</template>
            </p>
          </template>
          <button
            class="link-btn"
            type="button"
            :disabled="!canRebuild"
            :title="canRebuild ? '' : '当前不可重建数据'"
            @click="emit('go', 'data')"
          >
            重建数据
          </button>
        </article>

        <article class="health-card">
          <div class="health-head">
            <span class="health-label">脚本</span>
            <span class="health-pill" :class="scriptHealth.executionEnabled ? 'ok' : 'off'">
              {{ scriptHealth.executionEnabled ? '执行已开启' : '执行已关闭' }}
            </span>
          </div>
          <p class="health-usage">
            <strong>{{ isDb ? scriptHealth.count || 0 : '—' }}</strong>
            <span v-if="isDb"> 个脚本</span>
          </p>
          <p class="health-meta mono" :title="scriptHealth.setupCommand || ''">
            setup：{{ scriptHealth.setupCommand || '—' }}
          </p>
          <p class="health-hint">执行仍需在对话中确认</p>
        </article>

        <article class="health-card">
          <div class="health-head">
            <span class="health-label">绑定</span>
            <span class="health-pill off">{{ bindingHealth.count || 0 }} 个</span>
          </div>
          <div v-if="bindings.length" class="health-agents">
            <AgentAvatars :agents="bindings" />
            <span class="health-names">{{ bindings.map((item) => item.name).join('、') }}</span>
          </div>
          <p v-else class="health-note">还没有智能体引用。</p>
          <p class="health-hint">删除前需先解绑</p>
          <button class="link-btn" type="button" @click="emit('go', 'bindings')">查看绑定</button>
        </article>
      </div>
    </PanelCard>

    <PanelCard title="下一步">
      <template #hint>
        <span class="hint">完成 {{ checklistDone }} / {{ checklistTotal }}</span>
      </template>
      <ul class="check-list">
        <li v-for="item in checklist" :key="item.key" :class="{ done: item.ok }">
          <span class="check-mark" :class="item.ok ? 'ok' : 'todo'" aria-hidden="true">
            {{ item.ok ? '✓' : '○' }}
          </span>
          <div class="check-text">
            <strong>{{ item.label }}</strong>
            <span>{{ checkDetail(item) }}</span>
          </div>
          <button
            v-if="!item.ok"
            class="link-btn"
            type="button"
            @click="runAction(item)"
          >
            {{ actionLabel(item.action) }}
          </button>
          <span v-else class="check-done">已完成</span>
        </li>
      </ul>
    </PanelCard>

    <PanelCard title="最近变更">
      <template #actions>
        <button class="link-btn" type="button" @click="emit('go', 'versions')">全部版本 →</button>
      </template>
      <ul v-if="timeline.length" class="timeline">
        <li v-for="item in timeline" :key="item.versionNo">
          <span class="timeline-no mono">v{{ item.versionNo }}</span>
          <div class="timeline-main">
            <div class="timeline-note">{{ item.changeNote || '未填写变更说明' }}</div>
            <div class="timeline-meta">
              {{ authorName(item.createdBy) }} · {{ relative(item.createdAt) }}
            </div>
          </div>
          <span v-if="item.current" class="tag tag--accent">当前</span>
        </li>
      </ul>
      <EmptyState
        v-else
        :title="isDb ? '还没有版本记录' : '当前来源不提供版本时间线'"
        :description="isDb ? '保存正文后会生成第一个版本。' : 'Nacos 与内置来源没有版本行。'"
      />
    </PanelCard>
  </div>
</template>

<style scoped>
.wb-overview {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.health-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--surface-2);
}
.health-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 24px;
}
.health-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-soft);
}
.health-pill {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}
.health-pill.ok {
  background: var(--st-ok-soft, #e8f5ee);
  color: var(--st-ok, #18794e);
}
.health-pill.off {
  background: var(--surface-3);
  color: var(--muted);
}
.health-usage {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
.health-usage strong {
  font-size: 15px;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}
.usage-bar {
  height: 5px;
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
.health-meta {
  margin: 0;
  font-size: 11.5px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.health-note {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
.health-hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--faint);
}
.health-agents {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.health-names {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-soft);
}
.health-card .link-btn {
  align-self: flex-start;
  margin-top: 2px;
}
.check-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.check-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--border);
}
.check-list li:last-child {
  border-bottom: 0;
}
.check-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex: none;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
}
.check-mark.ok {
  background: var(--st-ok-soft, #e8f5ee);
  color: var(--st-ok, #18794e);
}
.check-mark.todo {
  background: var(--surface-3);
  color: var(--faint);
}
.check-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}
.check-text strong {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
}
.check-text span {
  font-size: 11.5px;
  color: var(--muted);
}
.check-done {
  font-size: 11.5px;
  color: var(--faint);
  white-space: nowrap;
}
.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
}
.timeline li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 2px;
  border-bottom: 1px solid var(--border);
}
.timeline li:last-child {
  border-bottom: 0;
}
.timeline-no {
  flex: none;
  width: 40px;
  color: var(--text-soft);
  font-size: 12px;
}
.timeline-main {
  flex: 1;
  min-width: 0;
}
.timeline-note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}
.timeline-meta {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--muted);
}
</style>

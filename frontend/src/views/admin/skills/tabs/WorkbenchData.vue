<script setup>
import { computed } from 'vue';
import PanelCard from '../../../../components/ui/PanelCard.vue';
import SkillDataPanel from '../SkillDataPanel.vue';
import { formatSize, formatTimestamp } from '../../../../utils/format';

const DEFAULT_DECLARED = 2 * 1024 * 1024 * 1024;

const props = defineProps({
  skillId: { type: String, required: true },
  source: { type: String, default: '' },
  needsData: { type: Boolean, default: null },
  declaredMaxBytes: { type: Number, default: 0 },
  observedAt: { type: Number, default: 0 },
  sandboxEnabled: { type: Boolean, default: true },
});

const declaredText = computed(() =>
  formatSize(Number(props.declaredMaxBytes || DEFAULT_DECLARED))
);
const observedText = computed(() =>
  props.observedAt ? formatTimestamp(props.observedAt) : '—'
);
</script>

<template>
  <PanelCard title="数据">
    <template #hint>
      <span v-if="source === 'db'" class="hint">
        声明上限 {{ declaredText }} · 上次同步 {{ observedText }}
      </span>
    </template>
    <div v-if="source !== 'db'" class="data-unknown">
      <strong>当前来源不提供数据状态</strong>
      <p>数据初始化、用量与执行日志只在 db 源可用；Nacos 与内置技能不记录数据状态。</p>
    </div>
    <SkillDataPanel
      v-else
      :skill-id="skillId"
      :needs-data="needsData"
      :declared-max-bytes="declaredMaxBytes"
      :observed-at="observedAt"
      :sandbox-enabled="sandboxEnabled"
    />
  </PanelCard>
</template>

<style scoped>
.data-unknown {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
  border: 1px dashed var(--border-strong);
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 12.5px;
  color: var(--muted);
}
.data-unknown strong {
  font-size: 13px;
  color: var(--text-soft);
}
.data-unknown p {
  margin: 0;
  line-height: 1.6;
}
</style>

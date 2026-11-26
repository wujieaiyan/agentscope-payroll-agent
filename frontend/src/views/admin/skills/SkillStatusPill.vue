<script setup>
import { computed } from 'vue';
import { dataStatusMeta } from './rules';

const props = defineProps({
  needsData: { type: Boolean, default: null },
  dataState: { type: Object, default: null },
  sandboxEnabled: { type: Boolean, default: true },
});

const meta = computed(() =>
  dataStatusMeta({
    needsData: props.needsData,
    dataState: props.dataState,
    sandboxEnabled: props.sandboxEnabled,
  })
);
</script>

<template>
  <span class="status-pill" :class="`status-pill--${meta.tone}`" :title="meta.tooltip">
    <i class="status-dot" aria-hidden="true" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  white-space: nowrap;
}
.status-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: currentColor;
}
.status-pill--ok {
  background: var(--st-ok-soft, #e8f5ee);
  color: var(--st-ok, #18794e);
}
.status-pill--warn {
  background: var(--st-warn-soft, #fff6e3);
  color: var(--st-warn, #9a6700);
}
.status-pill--danger {
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
}
.status-pill--neutral {
  background: var(--surface-3, #f2f4f7);
  color: var(--text-soft, #4b5465);
}
.status-pill--dim {
  background: var(--surface-3, #f2f4f7);
  color: var(--muted, #6b7484);
}
</style>

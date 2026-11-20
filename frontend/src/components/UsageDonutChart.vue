<script setup>
import { computed } from 'vue';
import { compactNumber, fullNumber, percent } from '../utils/usageFormat';

const props = defineProps({
  slices: { type: Array, default: () => [] },
  unit: { type: String, default: 'tokens' },
});

const COLORS = ['#2d8cf0', '#19be6b', '#ff9900', '#7c4dff', '#ed4014', '#00b8d9', '#8e44ad', '#95a5a6'];

const chart = computed(() => {
  const total = props.slices.reduce((sum, item) => sum + Number(item.total_tokens || 0), 0);
  const radius = 52;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;
  const segments = props.slices.map((item, index) => {
    const value = Number(item.total_tokens || 0);
    const length = total ? (value / total) * circumference : 0;
    const segment = {
      key: item.key,
      label: item.label || item.key,
      color: COLORS[index % COLORS.length],
      dash: `${length} ${circumference - length}`,
      offset: -offset,
      value,
      percent: percent(value, total),
      calls: item.calls,
    };
    offset += length;
    return segment;
  });
  return { total, radius, circumference, segments };
});
</script>

<template>
  <div class="donut">
    <svg v-if="chart.total" viewBox="0 0 140 140" class="donut-svg" role="img" aria-label="模型用量占比">
      <circle cx="70" cy="70" :r="chart.radius" class="track" />
      <circle
        v-for="segment in chart.segments"
        :key="segment.key"
        cx="70"
        cy="70"
        :r="chart.radius"
        :stroke="segment.color"
        :stroke-dasharray="segment.dash"
        :stroke-dashoffset="segment.offset"
        class="segment"
      >
        <title>{{ segment.label }} · {{ fullNumber(segment.value) }} {{ unit }}（{{ segment.percent }}）</title>
      </circle>
    </svg>
    <div v-else class="donut-empty">—</div>
    <div class="donut-center">
      <strong>{{ compactNumber(chart.total) }}</strong>
      <small>{{ unit }}</small>
    </div>
  </div>
  <ul v-if="chart.segments.length" class="donut-legend">
    <li v-for="segment in chart.segments" :key="segment.key">
      <span class="swatch" :style="{ background: segment.color }"></span>
      <span class="name" :title="segment.label">{{ segment.label }}</span>
      <span class="pct">{{ segment.percent }}</span>
      <span class="value">{{ compactNumber(segment.value) }}</span>
    </li>
  </ul>
  <div v-else class="empty">暂无模型分布</div>
</template>

<style scoped>
.donut { position: relative; width: 160px; height: 160px; margin: 0 auto; }
.donut-svg { width: 100%; height: 100%; transform: rotate(-90deg); }
.track { fill: none; stroke: var(--surface-3); stroke-width: 18; }
.segment { fill: none; stroke-width: 18; transition: stroke-dasharray 0.3s ease; }
.donut-empty { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.donut-center strong { font-size: 18px; color: var(--text); }
.donut-center small { font-size: 11px; color: var(--muted); }
.donut-legend { list-style: none; margin: 14px 0 0; padding: 0; display: flex; flex-direction: column; gap: 7px; }
.donut-legend li { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.swatch { width: 9px; height: 9px; border-radius: 50%; flex: none; }
.name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-soft); }
.pct { color: var(--muted); font-variant-numeric: tabular-nums; }
.value { color: var(--text); font-variant-numeric: tabular-nums; min-width: 56px; text-align: right; }
</style>

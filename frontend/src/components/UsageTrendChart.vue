<script setup>
import { computed } from 'vue';
import { compactNumber, formatBucket, fullNumber } from '../utils/usageFormat';

const props = defineProps({
  points: { type: Array, default: () => [] },
  granularity: { type: String, default: 'hour' },
});

const WIDTH = 760;
const HEIGHT = 248;
const PAD = { top: 18, right: 18, bottom: 30, left: 52 };

const chart = computed(() => {
  const rows = props.points || [];
  const innerW = WIDTH - PAD.left - PAD.right;
  const innerH = HEIGHT - PAD.top - PAD.bottom;
  const baseY = PAD.top + innerH;
  const max = Math.max(1, ...rows.map((row) => Number(row.total_tokens || 0)));
  const step = rows.length > 1 ? innerW / (rows.length - 1) : 0;
  const x = (index) => (rows.length > 1 ? PAD.left + index * step : PAD.left + innerW / 2);
  const y = (value) => baseY - (Number(value || 0) / max) * innerH;

  const points = rows.map((row, index) => {
    const prompt = Number(row.prompt_tokens || 0);
    const total = Number(row.total_tokens || 0);
    return {
      x: x(index),
      promptY: y(prompt),
      totalY: y(total),
      prompt,
      completion: Number(row.completion_tokens || 0),
      calls: Number(row.calls || 0),
      failures: Number(row.failures || 0),
      label: formatBucket(row.bucket, props.granularity),
    };
  });

  const promptPath = points.length
    ? `M ${points[0].x} ${baseY} ` +
      points.map((p) => `L ${p.x} ${p.promptY}`).join(' ') +
      ` L ${points[points.length - 1].x} ${baseY} Z`
    : '';
  const completionPath = points.length
    ? `M ${points[0].x} ${points[0].promptY} ` +
      points.map((p) => `L ${p.x} ${p.totalY}`).join(' ') +
      ' ' +
      [...points]
        .reverse()
        .map((p) => `L ${p.x} ${p.promptY}`)
        .join(' ') +
      ' Z'
    : '';
  const promptLine = points.length
    ? points.map((p, i) => `${i ? 'L' : 'M'} ${p.x} ${p.promptY}`).join(' ')
    : '';
  const totalLine = points.length
    ? points.map((p, i) => `${i ? 'L' : 'M'} ${p.x} ${p.totalY}`).join(' ')
    : '';

  const ticks = [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
    y: baseY - ratio * innerH,
    label: compactNumber(max * ratio),
  }));

  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const xLabels = points
    .map((p, i) => ({ x: p.x, label: p.label, show: i % labelEvery === 0 || i === points.length - 1 }))
    .filter((item) => item.show);

  return {
    baseY,
    innerW,
    points,
    promptPath,
    completionPath,
    promptLine,
    totalLine,
    ticks,
    xLabels,
  };
});
</script>

<template>
  <div class="trend">
    <div class="legend">
      <span class="dot prompt"></span><span>提示 Token</span>
      <span class="dot completion"></span><span>生成 Token</span>
      <span class="dot total"></span><span>合计</span>
    </div>
    <svg v-if="points.length" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" class="trend-svg" role="img" aria-label="Token 趋势">
      <g>
        <line
          v-for="tick in chart.ticks"
          :key="tick.y"
          :x1="PAD.left"
          :x2="WIDTH - PAD.right"
          :y1="tick.y"
          :y2="tick.y"
          class="grid-line"
        />
        <text
          v-for="tick in chart.ticks"
          :key="`label-${tick.y}`"
          :x="PAD.left - 8"
          :y="tick.y + 4"
          class="axis-text"
          text-anchor="end"
        >
          {{ tick.label }}
        </text>
      </g>
      <path :d="chart.promptPath" class="area-prompt" />
      <path :d="chart.completionPath" class="area-completion" />
      <path :d="chart.promptLine" class="line-prompt" />
      <path :d="chart.totalLine" class="line-total" />
      <g v-for="point in chart.points" :key="point.x">
        <circle :cx="point.x" :cy="point.totalY" r="10" class="hit">
          <title>
            {{ point.label }} · 合计 {{ fullNumber(point.prompt + point.completion) }} tokens（提示
            {{ fullNumber(point.prompt) }} / 生成 {{ fullNumber(point.completion) }}）· 调用
            {{ point.calls }} 次<template v-if="point.failures"> · 失败 {{ point.failures }} 次</template>
          </title>
        </circle>
        <circle :cx="point.x" :cy="point.totalY" r="2.5" class="node-total" />
      </g>
      <text
        v-for="item in chart.xLabels"
        :key="`x-${item.x}`"
        :x="item.x"
        :y="HEIGHT - 8"
        class="axis-text"
        text-anchor="middle"
      >
        {{ item.label }}
      </text>
    </svg>
    <div v-else class="empty">当前时间窗没有模型调用</div>
  </div>
</template>

<style scoped>
.trend { display: flex; flex-direction: column; gap: 8px; }
.legend { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
.legend .dot { width: 8px; height: 8px; border-radius: 50%; margin-left: 10px; }
.legend .dot:first-child { margin-left: 0; }
.dot.prompt { background: #2d8cf0; }
.dot.completion { background: #19be6b; }
.dot.total { background: #ff9900; }
.trend-svg { width: 100%; height: auto; display: block; }
.grid-line { stroke: var(--border); stroke-width: 1; stroke-dasharray: 3 4; }
.axis-text { font-size: 10.5px; fill: var(--faint); }
.area-prompt { fill: rgba(45, 140, 240, 0.18); }
.area-completion { fill: rgba(25, 190, 107, 0.16); }
.line-prompt { fill: none; stroke: #2d8cf0; stroke-width: 1.8; }
.line-total { fill: none; stroke: #ff9900; stroke-width: 1.4; stroke-dasharray: 5 4; }
.hit { fill: transparent; }
.node-total { fill: #fff; stroke: #ff9900; stroke-width: 1.6; }
.hit:hover + .node-total { fill: #ff9900; }
</style>

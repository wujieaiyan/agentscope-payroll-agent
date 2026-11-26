<script setup>
import { computed } from 'vue';
import { diffLines } from 'diff';
import { diffSummary } from './rules';

const MAX_ROWS = 1500;

const props = defineProps({
  before: { type: String, default: '' },
  after: { type: String, default: '' },
  label: { type: String, default: '基线' },
});

const changes = computed(() => diffLines(String(props.before || ''), String(props.after || '')));
const summary = computed(() => diffSummary(changes.value));

function toRows(parts) {
  const rows = [];
  for (const part of parts) {
    const lines = String(part.value || '').split('\n');
    if (lines[lines.length - 1] === '') lines.pop();
    const type = part.added ? 'add' : part.removed ? 'del' : 'ctx';
    for (const line of lines) rows.push({ type, text: line });
  }
  return rows;
}

const rows = computed(() => toRows(changes.value));
const shown = computed(() => rows.value.slice(0, MAX_ROWS));
const truncated = computed(() => Math.max(0, rows.value.length - shown.value.length));
</script>

<template>
  <div class="diff-view" role="region" :aria-label="`与${label}的差异`">
    <header class="diff-head">
      <span class="diff-label">与 {{ label }} 的差异</span>
      <span v-if="summary.added || summary.removed" class="diff-stat">
        <span class="add">+{{ summary.added }}</span>
        <span class="del">-{{ summary.removed }}</span>
      </span>
      <span v-else class="muted">与基线一致</span>
    </header>
    <div class="diff-body">
      <div v-for="(row, index) in shown" :key="index" class="diff-row" :class="row.type">
        <span class="diff-sign" aria-hidden="true">{{ row.type === 'add' ? '+' : row.type === 'del' ? '-' : ' ' }}</span>
        <span class="diff-text">{{ row.text || ' ' }}</span>
      </div>
      <p v-if="truncated" class="diff-more muted">其余 {{ truncated }} 行未展开。</p>
    </div>
  </div>
</template>

<style scoped>
.diff-view {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.diff-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  color: var(--muted);
}
.diff-label {
  font-weight: 600;
  color: var(--text-soft);
}
.diff-stat {
  display: inline-flex;
  gap: 8px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}
.diff-stat .add {
  color: var(--st-ok);
}
.diff-stat .del {
  color: var(--st-bad);
}
.diff-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 6px 0;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  line-height: 1.6;
}
.diff-row {
  display: flex;
  gap: 8px;
  padding: 0 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.diff-row.add {
  background: #e9f7ef;
  color: #17603b;
}
.diff-row.del {
  background: #fdecec;
  color: #8f2424;
}
.diff-sign {
  flex: none;
  width: 10px;
  text-align: center;
  user-select: none;
}
.diff-text {
  min-width: 0;
}
.diff-more {
  margin: 8px 14px;
  font-size: 12px;
}
.muted {
  color: var(--muted);
}
</style>

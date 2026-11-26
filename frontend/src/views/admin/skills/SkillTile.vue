<script setup>
import { computed } from 'vue';

const props = defineProps({
  id: { type: String, default: '' },
  description: { type: String, default: '' },
  size: { type: String, default: 'md' },
});

const initial = computed(() => {
  const description = String(props.description || '').trim();
  if (description) return description[0];
  const id = String(props.id || '').trim();
  return id ? id[0].toUpperCase() : '?';
});

const tone = computed(() => {
  const source = String(props.id || '');
  let hash = 0;
  for (let index = 0; index < source.length; index += 1) {
    hash = (hash * 31 + source.charCodeAt(index)) >>> 0;
  }
  return `t${(hash % 4) + 1}`;
});
</script>

<template>
  <span class="skill-tile" :class="[tone, size === 'lg' ? 'skill-tile--lg' : '']" aria-hidden="true">
    {{ initial }}
  </span>
</template>

<style scoped>
.skill-tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
  user-select: none;
}
.skill-tile--lg {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  font-size: 18px;
}
.skill-tile.t1 {
  background: #edf3ff;
  color: #2457c4;
}
.skill-tile.t2 {
  background: #e8f5ee;
  color: #18794e;
}
.skill-tile.t3 {
  background: #fff6e3;
  color: #9a6700;
}
.skill-tile.t4 {
  background: #f3edff;
  color: #6b3fc4;
}
</style>

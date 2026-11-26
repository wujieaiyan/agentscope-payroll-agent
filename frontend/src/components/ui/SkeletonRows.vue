<script setup>
defineProps({
  rows: { type: Number, default: 3 },
  columns: {
    type: Array,
    default: () => ['30%', '10%', '12%', '14%', '8%'],
  },
});
</script>

<template>
  <div class="skeleton-rows" role="status" aria-label="加载中">
    <div v-for="row in rows" :key="row" class="skeleton-row">
      <span v-for="(width, index) in columns" :key="index" class="skeleton" :style="{ width }" />
    </div>
  </div>
</template>

<style scoped>
.skeleton-rows {
  display: flex;
  flex-direction: column;
}
.skeleton-row {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 52px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border, #e6e8ee);
}
.skeleton-row:last-child {
  border-bottom: none;
}
.skeleton {
  height: 12px;
}
</style>

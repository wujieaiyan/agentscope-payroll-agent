<template>
  <article class="series-card" @click="$emit('open', series.id)">
    <div class="card-ribbon" :class="strategyClass">
      <span class="strategy-name">
        <i class="strategy-dot" />
        {{ indexStrategyMap[series.indexStrategy] || series.indexStrategy }}
      </span>
      <el-tag size="small" effect="plain" :type="status.type">{{ status.label }}</el-tag>
    </div>

    <div class="card-body">
      <div class="card-title-row">
        <h3>{{ series.name }}</h3>
        <el-tag v-if="series.role" size="small" effect="plain" :type="roleInfo.type">
          {{ roleInfo.label }}
        </el-tag>
      </div>

      <div class="card-meta">
        <span><el-icon><OfficeBuilding /></el-icon>{{ series.deptName || '未分配部门' }}</span>
        <span><el-icon><User /></el-icon>{{ series.authorName || '未知作者' }}</span>
      </div>

      <p class="card-desc">{{ series.description || '暂未填写知识库简介。' }}</p>

      <div class="card-stats">
        <div class="stat">
          <strong>{{ series.docCount }}</strong>
          <span>文档</span>
        </div>
        <div class="stat">
          <strong>{{ series.readyDocCount }}</strong>
          <span>已就绪</span>
        </div>
        <div class="stat">
          <strong>{{ series.chunkCount }}</strong>
          <span>切片</span>
        </div>
        <div v-if="series.failedDocCount > 0" class="stat failed">
          <strong>{{ series.failedDocCount }}</strong>
          <span>失败</span>
        </div>
      </div>
    </div>

    <div class="card-foot">
      <span class="updated"><el-icon><Clock /></el-icon>更新于 {{ formatTime(series.updatedAt) }}</span>
      <span class="open-link">进入 <el-icon><ArrowRight /></el-icon></span>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { OfficeBuilding, User, Clock, ArrowRight } from '@element-plus/icons-vue'
import { formatTime } from '../utils/format'
import { indexStrategyMap, roleMap, seriesStatusMap, statusOf } from '../utils/status'

const props = defineProps({
  series: { type: Object, required: true }
})

defineEmits(['open'])

const status = computed(() => statusOf(seriesStatusMap, props.series.displayStatus || props.series.status))
const roleInfo = computed(() => roleMap[props.series.role] || { label: props.series.role, type: 'info' })
const strategyClass = computed(() => {
  if (props.series.indexStrategy === 'VECTOR') return 'vector'
  if (props.series.indexStrategy === 'GRAPH') return 'graph'
  return 'hybrid'
})
</script>

<style scoped>
.series-card {
  background: var(--kb-surface);
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
  display: flex;
  flex-direction: column;
  min-height: 286px;
}

.series-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--kb-shadow-lift);
  border-color: var(--kb-line-strong);
}

.card-ribbon {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px 0;
  font-size: 10px;
  letter-spacing: 1.2px;
  text-transform: uppercase;
  color: var(--kb-muted);
}

.strategy-name {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.strategy-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--kb-accent);
}

.card-ribbon.vector .strategy-dot {
  background: #5b6f96;
}

.card-ribbon.graph .strategy-dot {
  background: #7b6a96;
}

.card-ribbon :deep(.el-tag) {
  font-size: 10px;
  letter-spacing: 0;
  text-transform: none;
  background: transparent;
}

.card-body {
  flex: 1;
  padding: 12px 20px 14px;
}

.card-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.card-title-row h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.3px;
  color: var(--kb-ink);
}

.card-title-row :deep(.el-tag) {
  flex-shrink: 0;
  margin-top: 2px;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--kb-muted);
}

.card-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.card-meta .el-icon {
  font-size: 12px;
  color: var(--kb-faint);
}

.card-desc {
  margin: 14px 0 16px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--kb-ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 45px;
}

.card-stats {
  display: flex;
  padding: 10px 0;
  border-radius: 7px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-line-soft);
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 13px;
  border-left: 1px solid var(--kb-line-soft);
}

.stat:first-child {
  border-left: none;
}

.stat strong {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.3px;
  font-variant-numeric: tabular-nums;
  color: var(--kb-ink);
}

.stat span {
  font-size: 10px;
  color: var(--kb-muted);
}

.stat.failed strong {
  color: var(--kb-danger);
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 11px 20px;
  border-top: 1px solid var(--kb-line-soft);
  font-size: 10.5px;
  color: var(--kb-faint);
}

.updated {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.open-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--kb-primary);
  font-weight: 500;
  opacity: 0;
  transform: translateX(-4px);
  transition: all 0.2s ease;
}

.series-card:hover .open-link {
  opacity: 1;
  transform: translateX(0);
}
</style>

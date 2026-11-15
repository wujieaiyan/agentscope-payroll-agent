<template>
  <div class="overview">
    <div class="overview-grid">
      <div class="panel">
        <div class="panel-head">
          <div>
            <h3 class="section-title">配置快照</h3>
            <p class="section-subtitle">当前知识库使用的解析、分块与检索参数</p>
          </div>
          <el-button v-if="canManage" text type="primary" @click="$emit('jump', 'settings')">调整配置</el-button>
        </div>

        <div v-loading="configLoading" class="config-grid">
          <div class="config-card">
            <span class="config-label">解析引擎</span>
            <strong>{{ parseSummary }}</strong>
            <p>{{ parseDetail }}</p>
          </div>
          <div class="config-card">
            <span class="config-label">分块策略</span>
            <strong>{{ chunkSummary }}</strong>
            <p>{{ chunkDetail }}</p>
          </div>
          <div class="config-card">
            <span class="config-label">检索与精排</span>
            <strong>{{ retrievalSummary }}</strong>
            <p>{{ retrievalDetail }}</p>
          </div>
        </div>
      </div>

      <div class="panel side-panel">
        <div class="panel-head">
          <div>
            <h3 class="section-title">文档状态</h3>
            <p class="section-subtitle">入库处理进度分布</p>
          </div>
          <el-button text type="primary" @click="$emit('jump', 'documents')">管理文档</el-button>
        </div>
        <div v-loading="summaryLoading" class="status-list">
          <div v-for="item in statusRows" :key="item.key" class="status-row">
            <div class="status-name">
              <el-tag size="small" :type="item.type" effect="plain" round>{{ item.label }}</el-tag>
              <span class="count">{{ item.count }}</span>
            </div>
            <div class="status-track">
              <div class="status-fill" :class="item.key.toLowerCase()" :style="{ width: item.percent + '%' }" />
            </div>
          </div>
          <el-empty v-if="!summaryLoading && !totalDocs" description="尚未上传文档" :image-size="70" />
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <div>
          <h3 class="section-title">最近任务</h3>
          <p class="section-subtitle">解析、切片与索引执行的最近记录</p>
        </div>
        <el-button text type="primary" @click="$emit('jump', 'tasks')">进入任务中心</el-button>
      </div>

      <el-table v-loading="taskLoading" :data="recentTasks" size="small" empty-text="暂无任务记录">
        <el-table-column prop="id" label="任务" width="90">
          <template #default="{ row }">
            <span class="mono">#{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ taskTypeMap[row.type] || row.type }}</template>
        </el-table-column>
        <el-table-column prop="documentName" label="文档" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusOf(taskStatusMap, row.status).type" effect="plain">
              {{ statusOf(taskStatusMap, row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="150">
          <template #default="{ row }">
            <el-progress :percentage="row.progress" :stroke-width="6" :show-text="false" />
            <span class="progress-text">{{ row.stage || '-' }} · {{ row.progress }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ formatDuration(row.startedAt, row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="130">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { configApi, documentApi, taskApi } from '../../api'
import { formatDuration, formatTime } from '../../utils/format'
import {
  canAdmin,
  chunkStrategyMap,
  docStatusMap,
  parseStrategyMap,
  statusOf,
  taskStatusMap,
  taskTypeMap
} from '../../utils/status'

const props = defineProps({
  series: { type: Object, required: true },
  configNo: { type: Number, default: null },
  role: { type: String, default: 'READER' }
})

defineEmits(['jump'])

const canManage = computed(() => canAdmin(props.role))

const config = ref(null)
const configLoading = ref(false)
const summary = ref({})
const summaryLoading = ref(false)
const recentTasks = ref([])
const taskLoading = ref(false)

const parseSummary = computed(() => {
  if (!config.value) return '未配置'
  const enabled = Object.entries(config.value.parse?.perType || {})
    .filter(([, value]) => value?.enabled)
    .map(([key]) => key)
  return enabled.length ? enabled.join(' / ') : '未启用文档类型'
})

const parseDetail = computed(() => {
  const perType = config.value?.parse?.perType || {}
  const strategies = [...new Set(Object.values(perType).map((item) => parseStrategyMap[item?.strategy] || item?.strategy))]
  return strategies.length ? `解析策略：${strategies.join('、')}` : '演示环境默认使用本地文字提取'
})

const chunkSummary = computed(() => {
  const strategy = config.value?.chunk?.strategy
  return chunkStrategyMap[strategy] || '默认切分'
})

const chunkDetail = computed(() => {
  const chunk = config.value?.chunk
  if (!chunk) return '-'
  if (chunk.strategy === 'PARENT_CHILD') {
    return `父 ${chunk.parentChild?.parentTokens} / 子 ${chunk.parentChild?.childTokens} tokens`
  }
  return `最大 ${chunk.common?.maxTokens} tokens · 重叠 ${chunk.common?.overlapTokens}`
})

const retrievalSummary = computed(() => {
  const retrieval = config.value?.retrieval
  if (!retrieval) return '-'
  const mode = retrieval.hybridEnabled ? '混合检索' : '向量检索'
  return `${mode} · Top${retrieval.recallTopK}`
})

const retrievalDetail = computed(() => {
  const retrieval = config.value?.retrieval
  if (!retrieval) return '-'
  return `精排 ${retrieval.rerankEnabled ? '开启' : '关闭'} · 阈值 ${Number(retrieval.similarityThreshold).toFixed(2)}`
})

const totalDocs = computed(() => summary.value.total || 0)

const statusRows = computed(() => {
  const mapping = [
    { key: 'READY', label: docStatusMap.READY.label, type: 'success' },
    { key: 'PENDING', label: docStatusMap.PENDING.label, type: 'info' },
    { key: 'PARSING', label: docStatusMap.PARSING.label, type: 'warning' },
    { key: 'CHUNKING', label: docStatusMap.CHUNKING.label, type: 'warning' },
    { key: 'INDEXING', label: docStatusMap.INDEXING.label, type: 'warning' },
    { key: 'FAILED', label: docStatusMap.FAILED.label, type: 'danger' },
    { key: 'DISABLED', label: docStatusMap.DISABLED.label, type: 'info' }
  ]
  return mapping
    .map((item) => ({
      ...item,
      count: Number(summary.value[item.key] || 0),
      percent: totalDocs.value ? Math.round((Number(summary.value[item.key] || 0) / totalDocs.value) * 100) : 0
    }))
    .filter((item) => item.count > 0)
})

async function loadConfig() {
  configLoading.value = true
  try {
    config.value = await configApi.current(props.series.id)
  } catch (error) {
    config.value = null
  } finally {
    configLoading.value = false
  }
}

async function loadSummary() {
  summaryLoading.value = true
  try {
    summary.value = await documentApi.statusSummary(props.series.id)
  } catch (error) {
    summary.value = {}
  } finally {
    summaryLoading.value = false
  }
}

async function loadTasks() {
  taskLoading.value = true
  try {
    const data = await taskApi.list(props.series.id, { page: 1, size: 5 })
    recentTasks.value = data.items || []
  } catch (error) {
    recentTasks.value = []
  } finally {
    taskLoading.value = false
  }
}

function loadAll() {
  loadConfig()
  loadSummary()
  loadTasks()
}

watch(() => props.series.id, loadAll)
onMounted(loadAll)
</script>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.75fr) minmax(280px, 1fr);
  gap: 18px;
}

.panel {
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  padding: 20px;
  background: var(--kb-surface);
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.config-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.config-card {
  padding: 14px;
  border-radius: 7px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-line-soft);
}

.config-label {
  font-size: 10px;
  letter-spacing: 1px;
  color: var(--kb-muted);
}

.config-card strong {
  display: block;
  margin: 8px 0 5px;
  font-size: 14px;
  font-weight: 600;
  color: var(--kb-ink);
}

.config-card p {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--kb-muted);
}

.status-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

.status-name {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.status-name .count {
  font-size: 12px;
  font-weight: 600;
  color: var(--kb-ink-soft);
  font-variant-numeric: tabular-nums;
}

.status-track {
  height: 5px;
  border-radius: 5px;
  background: var(--kb-surface-3);
  overflow: hidden;
}

.status-fill {
  height: 100%;
  border-radius: 5px;
  background: #8ab5a7;
  transition: width 0.3s ease;
}

.status-fill.ready {
  background: var(--kb-success);
}

.status-fill.parsing,
.status-fill.chunking,
.status-fill.indexing {
  background: var(--kb-warning);
}

.status-fill.failed {
  background: var(--kb-danger);
}

.progress-text {
  display: block;
  margin-top: 3px;
  font-size: 10px;
  color: var(--kb-muted);
}

@media (max-width: 1080px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .config-grid {
    grid-template-columns: 1fr;
  }
}
</style>

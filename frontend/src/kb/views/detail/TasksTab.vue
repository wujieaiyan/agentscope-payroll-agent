<template>
  <div class="tasks-tab">
    <div class="task-toolbar">
      <div>
        <h3 class="section-title">任务中心</h3>
        <p class="section-subtitle">文档解析、切片、索引与失败重建任务，运行中的任务会自动刷新。</p>
      </div>
      <div class="toolbar-actions">
        <el-select v-model="filters.status" placeholder="全部状态" clearable class="filter" @change="reload">
          <el-option v-for="(item, key) in taskStatusMap" :key="key" :label="item.label" :value="key" />
        </el-select>
        <el-select v-model="filters.type" placeholder="全部类型" clearable class="filter" @change="reload">
          <el-option v-for="(label, key) in taskTypeMap" :key="key" :label="label" :value="key" />
        </el-select>
        <el-button :icon="Refresh" circle plain :loading="loading" @click="loadTasks" />
      </div>
    </div>

    <el-table v-loading="loading" :data="tasks" size="default" empty-text="暂无任务记录">
      <el-table-column prop="id" label="任务" width="90">
        <template #default="{ row }"><span class="mono">#{{ row.id }}</span></template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ taskTypeMap[row.type] || row.type }}</template>
      </el-table-column>
      <el-table-column prop="documentName" label="文档" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">{{ row.documentName || (row.type === 'REBUILD' || row.type === 'FULL_REBUILD' ? '批量重建' : '-') }}</template>
      </el-table-column>
      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <el-tag size="small" :type="statusOf(taskStatusMap, row.status).type" effect="plain">
            {{ statusOf(taskStatusMap, row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="190">
        <template #default="{ row }">
          <el-progress
            :percentage="row.progress"
            :stroke-width="6"
            :status="row.status === 'FAILED' ? 'exception' : row.status === 'SUCCEEDED' ? 'success' : ''"
          />
          <span class="stage-text">{{ row.stage || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="配置版本" width="100" align="center">
        <template #default="{ row }">
          <span class="mono muted">{{ row.configVersionId ? `#${row.configVersionId}` : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">{{ formatDuration(row.startedAt, row.finishedAt) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="130">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button text type="primary" size="small" @click="openDetail(row)">日志</el-button>
            <el-button
              v-if="canMaintain && canCancel(row)"
              text
              type="warning"
              size="small"
              @click="cancelTask(row)"
            >
              取消
            </el-button>
            <el-button
              v-if="canMaintain && canRetry(row)"
              text
              size="small"
              @click="retryTask(row)"
            >
              重试
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        background
        @current-change="loadTasks"
      />
    </div>

    <el-drawer v-model="detailVisible" :title="`任务 #${detail?.task?.id || ''}`" size="600px">
      <div v-if="detail" class="task-detail">
        <div class="detail-head">
          <el-tag :type="statusOf(taskStatusMap, detail.task.status).type" effect="plain">
            {{ statusOf(taskStatusMap, detail.task.status).label }}
          </el-tag>
          <span class="muted">{{ taskTypeMap[detail.task.type] || detail.task.type }}</span>
          <span v-if="detail.task.errorCode" class="error-code">{{ detail.task.errorCode }}</span>
        </div>
        <div class="detail-grid">
          <div><span>文档</span><strong>{{ detail.task.documentName || '-' }}</strong></div>
          <div><span>操作人</span><strong>{{ detail.task.operatorName || '-' }}</strong></div>
          <div><span>开始时间</span><strong>{{ formatFullTime(detail.task.startedAt) }}</strong></div>
          <div><span>结束时间</span><strong>{{ formatFullTime(detail.task.finishedAt) }}</strong></div>
          <div><span>配置版本</span><strong class="mono">{{ detail.task.configVersionId ? `#${detail.task.configVersionId}` : '-' }}</strong></div>
          <div><span>重试次数</span><strong>{{ detail.task.retryCount }}</strong></div>
        </div>
        <el-alert
          v-if="detail.task.errorMessage"
          type="error"
          :closable="false"
          show-icon
          :title="detail.task.errorMessage"
          class="task-error"
        />

        <h4 class="log-title">执行日志</h4>
        <div class="log-timeline">
          <div v-for="log in detail.logs" :key="log.id" class="log-row" :class="log.level.toLowerCase()">
            <span class="log-time">{{ formatTime(log.createdAt) }}</span>
            <el-tag size="small" effect="plain" :type="log.level === 'ERROR' ? 'danger' : 'info'">
              {{ log.stage }}
            </el-tag>
            <span class="log-message">{{ log.message }}</span>
            <span v-if="log.costMs" class="log-cost">{{ log.costMs }}ms</span>
          </div>
          <el-empty v-if="!detail.logs?.length" description="暂无日志" :image-size="70" />
        </div>
      </div>
      <div v-else v-loading="true" class="detail-loading" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { taskApi } from '../../api'
import { formatDuration, formatFullTime, formatTime } from '../../utils/format'
import { statusOf, taskStatusMap, taskTypeMap } from '../../utils/status'

const props = defineProps({
  series: { type: Object, required: true },
  role: { type: String, default: 'READER' }
})

const emit = defineEmits(['changed'])

const canMaintain = computed(() => ['EDITOR', 'ADMIN', 'OWNER'].includes(props.role))

const loading = ref(false)
const tasks = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const filters = reactive({ status: null, type: null })

const detailVisible = ref(false)
const detail = ref(null)

let pollTimer = null

function canCancel(task) {
  return ['QUEUED', 'RUNNING'].includes(task.status)
}

function canRetry(task) {
  return ['FAILED', 'CANCELED'].includes(task.status)
}

async function loadTasks() {
  loading.value = true
  try {
    const data = await taskApi.list(props.series.id, {
      status: filters.status || undefined,
      type: filters.type || undefined,
      page: page.value,
      size: pageSize
    })
    tasks.value = data.items || []
    total.value = data.total || 0
    schedulePolling()
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  loadTasks()
}

function schedulePolling() {
  const active = tasks.value.some((task) => canCancel(task))
  if (active && !pollTimer) {
    pollTimer = window.setInterval(async () => {
      if (loading.value) return
      const data = await taskApi.list(props.series.id, {
        status: filters.status || undefined,
        type: filters.type || undefined,
        page: page.value,
        size: pageSize
      })
      tasks.value = data.items || []
      total.value = data.total || 0
      if (!tasks.value.some((task) => canCancel(task))) {
        stopPolling()
        emit('changed')
      }
      if (detailVisible.value && detail.value?.task && canCancel(detail.value.task)) {
        detail.value = await taskApi.detail(props.series.id, detail.value.task.id)
      }
    }, 3000)
  } else if (!active) {
    stopPolling()
  }
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function openDetail(row) {
  detail.value = null
  detailVisible.value = true
  detail.value = await taskApi.detail(props.series.id, row.id)
}

async function retryTask(row) {
  const result = await taskApi.retry(props.series.id, row.id)
  ElMessage.success(`已创建重试任务 #${result.id}`)
  await loadTasks()
}

async function cancelTask(row) {
  await taskApi.cancel(props.series.id, row.id)
  ElMessage.success('已提交取消请求')
  await loadTasks()
}

watch(
  () => props.series.id,
  () => {
    page.value = 1
    loadTasks()
  }
)

onMounted(loadTasks)
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.tasks-tab {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.filter {
  width: 130px;
}

.stage-text {
  display: block;
  margin-top: 3px;
  font-size: 10px;
  color: var(--kb-muted);
}

.row-actions {
  display: flex;
  gap: 2px;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.task-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.error-code {
  font-size: 10px;
  color: var(--kb-danger);
  background: var(--kb-danger-soft);
  padding: 2px 7px;
  border-radius: 4px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
  border-radius: 7px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-line-soft);
}

.detail-grid div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.detail-grid span {
  font-size: 10px;
  color: var(--kb-muted);
}

.detail-grid strong {
  font-size: 12px;
  font-weight: 550;
}

.task-error {
  margin-top: -4px;
}

.log-title {
  margin: 4px 0 0;
  font-size: 13px;
}

.log-timeline {
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--kb-line);
  padding-left: 14px;
  margin-left: 4px;
}

.log-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
  font-size: 11px;
}

.log-row::before {
  content: '';
  position: absolute;
  left: -19px;
  top: 14px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #8ab5a7;
}

.log-row.error::before {
  background: var(--kb-danger);
}

.log-time {
  color: var(--kb-muted);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
  min-width: 84px;
}

.log-message {
  flex: 1;
  min-width: 120px;
  line-height: 1.6;
  color: var(--kb-ink-soft);
}

.log-cost {
  font-size: 10px;
  color: var(--kb-muted);
}

.detail-loading {
  min-height: 200px;
}
</style>

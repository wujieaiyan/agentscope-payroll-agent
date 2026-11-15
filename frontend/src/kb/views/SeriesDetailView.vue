<template>
  <div v-loading="loading" class="page-shell detail-page kb-app">
    <template v-if="series">
      <div class="detail-hero">
        <div class="hero-main">
          <div class="hero-tags">
            <el-tag size="small" :type="statusInfo.type" effect="plain" round>{{ statusInfo.label }}</el-tag>
            <el-tag size="small" :type="roleInfo.type" effect="light" round>{{ roleInfo.label }}</el-tag>
            <el-tag size="small" effect="plain" round class="strategy-tag">
              {{ indexStrategyMap[series.indexStrategy] || series.indexStrategy }}
            </el-tag>
          </div>
          <h1 class="hero-title">{{ series.name }}</h1>
          <p class="hero-desc">{{ series.description || '暂未填写知识库简介。' }}</p>
          <div class="hero-meta">
            <span><el-icon><OfficeBuilding /></el-icon>{{ series.deptName || '未分配部门' }}</span>
            <span><el-icon><User /></el-icon>{{ series.authorName || '未知作者' }}</span>
            <span><el-icon><Clock /></el-icon>更新于 {{ formatTime(series.updatedAt) }}</span>
            <span class="mono">配置版本 v{{ configNo || '-' }}</span>
          </div>
        </div>
        <div class="hero-actions">
          <el-button :icon="ChatDotRound" @click="openChat">用此知识库问答</el-button>
          <el-button
            v-if="canMaintain"
            type="primary"
            :icon="UploadFilled"
            @click="goTab('documents')"
          >
            导入文档
          </el-button>
          <el-dropdown v-if="canAdmin" trigger="click" @command="handleCommand">
            <el-button :icon="MoreFilled" circle />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="members">成员权限</el-dropdown-item>
                <el-dropdown-item command="settings">知识库设置</el-dropdown-item>
                <el-dropdown-item divided command="disable" v-if="series.status === 'ACTIVE'">
                  停用知识库
                </el-dropdown-item>
                <el-dropdown-item command="enable" v-else>启用知识库</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <div class="stat-strip surface">
        <div class="stat-cell">
          <span>文档总数</span>
          <strong>{{ series.docCount }}</strong>
        </div>
        <div class="stat-cell">
          <span>已就绪</span>
          <strong class="ok">{{ series.readyDocCount }}</strong>
        </div>
        <div class="stat-cell">
          <span>切片总数</span>
          <strong>{{ series.chunkCount }}</strong>
        </div>
        <div class="stat-cell">
          <span>失败文档</span>
          <strong :class="{ bad: series.failedDocCount > 0 }">{{ series.failedDocCount }}</strong>
        </div>
        <div class="stat-cell stat-wide">
          <span>当前索引策略</span>
          <strong class="strategy">{{ indexStrategyMap[series.indexStrategy] || series.indexStrategy }}</strong>
        </div>
      </div>

      <div class="detail-tabs surface">
        <el-tabs v-model="activeTab" class="detail-tabbar" @tab-change="syncTabQuery">
          <el-tab-pane label="概览" name="overview" />
          <el-tab-pane label="知识管理" name="documents" />
          <el-tab-pane label="目录管理" name="directories" />
          <el-tab-pane label="标签管理" name="tags" />
          <el-tab-pane label="任务中心" name="tasks" />
          <el-tab-pane label="检索测试" name="retrieval" />
          <el-tab-pane v-if="canAdmin" label="成员权限" name="members" />
          <el-tab-pane v-if="canAdmin" label="设置" name="settings" />
        </el-tabs>

        <div class="tab-body">
          <OverviewTab
            v-if="activeTab === 'overview'"
            :series="series"
            :config-no="configNo"
            :role="role"
            @jump="goTab"
          />
          <DocumentsTab
            v-else-if="activeTab === 'documents'"
            :series="series"
            :role="role"
            @changed="reloadSeries"
            @jump-tasks="goTab('tasks')"
          />
          <DirectoriesTab
            v-else-if="activeTab === 'directories'"
            :series="series"
            :role="role"
            @changed="reloadSeries"
            @jump-documents="goTab('documents')"
          />
          <TagsTab v-else-if="activeTab === 'tags'" :series="series" :role="role" />
          <TasksTab
            v-else-if="activeTab === 'tasks'"
            :series="series"
            :role="role"
            @changed="reloadSeries"
          />
          <RetrievalTab v-else-if="activeTab === 'retrieval'" :series="series" />
          <MembersTab v-else-if="activeTab === 'members' && canAdmin" :series="series" />
          <SettingsTab
            v-else-if="activeTab === 'settings' && canAdmin"
            :series="series"
            :role="role"
            @changed="reloadSeries"
          />
        </div>
      </div>
    </template>

    <el-empty v-else-if="!loading" description="知识库不存在或无权访问">
      <el-button type="primary" @click="router.push('/kb?scope=accessible')">返回列表</el-button>
    </el-empty>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Clock,
  MoreFilled,
  OfficeBuilding,
  UploadFilled,
  User
} from '@element-plus/icons-vue'
import { configApi, seriesApi } from '../api'
import { formatTime } from '../utils/format'
import { canAdmin as hasAdmin, canMaintain as hasMaintain, indexStrategyMap, roleMap, seriesStatusMap, statusOf } from '../utils/status'
import OverviewTab from './detail/OverviewTab.vue'
import DocumentsTab from './detail/DocumentsTab.vue'
import DirectoriesTab from './detail/DirectoriesTab.vue'
import TagsTab from './detail/TagsTab.vue'
import TasksTab from './detail/TasksTab.vue'
import RetrievalTab from './detail/RetrievalTab.vue'
import MembersTab from './detail/MembersTab.vue'
import SettingsTab from './detail/SettingsTab.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const series = ref(null)
const configNo = ref(null)

const role = computed(() => series.value?.role || 'READER')
const canMaintain = computed(() => hasMaintain(role.value))
const canAdmin = computed(() => hasAdmin(role.value))
const statusInfo = computed(() =>
  statusOf(seriesStatusMap, series.value?.displayStatus || series.value?.status)
)
const roleInfo = computed(() => roleMap[role.value] || { label: role.value, type: 'info' })

const allowedTabs = computed(() => {
  const base = ['overview', 'documents', 'directories', 'tags', 'tasks', 'retrieval']
  return canAdmin.value ? [...base, 'members', 'settings'] : base
})

const activeTab = ref(allowedTabs.value.includes(route.query.tab) ? route.query.tab : 'overview')

async function loadSeries() {
  loading.value = true
  try {
    series.value = await seriesApi.detail(route.params.id)
    const queryTab = typeof route.query.tab === 'string' ? route.query.tab : null
    if (queryTab && allowedTabs.value.includes(queryTab)) {
      activeTab.value = queryTab
    }
    try {
      const config = await configApi.current(route.params.id)
      configNo.value = config?.versionNo || null
    } catch (error) {
      configNo.value = null
    }
  } catch (error) {
    series.value = null
  } finally {
    loading.value = false
  }
}

async function reloadSeries() {
  try {
    series.value = await seriesApi.detail(route.params.id)
    const config = await configApi.current(route.params.id)
    configNo.value = config?.versionNo || null
  } catch (error) {
    // keep current snapshot
  }
}

function goTab(tab) {
  if (!allowedTabs.value.includes(tab)) return
  activeTab.value = tab
  syncTabQuery(tab)
}

function syncTabQuery(tab) {
  const target = typeof tab === 'string' ? tab : activeTab.value
  router.replace({ query: { ...route.query, tab: target === 'overview' ? undefined : target } })
}

function openChat() {
  router.push({ path: '/chat', query: { seriesId: series.value.id } })
}

async function handleCommand(command) {
  if (command === 'members' || command === 'settings') {
    goTab(command)
    return
  }
  if (command === 'disable') {
    try {
      await ElMessageBox.confirm('停用后文档处理与检索将暂停，确认停用？', '停用知识库', {
        confirmButtonText: '停用',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await seriesApi.disable(series.value.id)
      ElMessage.success('知识库已停用')
      await loadSeries()
    } catch (error) {
      // canceled or handled
    }
  }
  if (command === 'enable') {
    try {
      await ElMessageBox.confirm('启用后可以继续导入文档并进行检索。', '启用知识库', {
        confirmButtonText: '启用',
        cancelButtonText: '取消'
      })
      await seriesApi.enable(series.value.id)
      ElMessage.success('知识库已启用')
      await loadSeries()
    } catch (error) {
      // canceled or handled
    }
  }
}

onMounted(loadSeries)
</script>

<style scoped>
.detail-page {
  max-width: 1440px;
}

.detail-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  gap: 28px;
  padding: 28px 30px 26px 34px;
  border-radius: 12px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-line);
  color: var(--kb-ink);
  box-shadow: var(--kb-shadow);
}

.detail-hero::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 3px;
  height: 100%;
  background: var(--kb-accent);
  opacity: 0.7;
}

.hero-main {
  min-width: 0;
}

.hero-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.hero-tags :deep(.el-tag) {
  background: var(--kb-surface-2);
}

.hero-tags :deep(.strategy-tag) {
  background: var(--kb-accent-soft);
  border-color: #e6d7bd;
  color: #8c6132;
}

.hero-title {
  margin: 14px 0 8px;
  font-family: var(--kb-serif);
  font-size: 29px;
  font-weight: 600;
  letter-spacing: -0.3px;
  line-height: 1.3;
}

.hero-desc {
  margin: 0;
  max-width: 760px;
  font-size: 13px;
  line-height: 1.85;
  color: var(--kb-ink-soft);
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 16px;
  font-size: 11px;
  color: var(--kb-muted);
}

.hero-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.hero-meta .mono {
  color: var(--kb-faint);
}

.hero-actions {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-shrink: 0;
}

.hero-actions :deep(.el-button:not(.el-button--primary)) {
  background: var(--kb-surface);
  border-color: var(--kb-line-strong);
  color: var(--kb-ink-soft);
}

.hero-actions :deep(.el-button:not(.el-button--primary):hover) {
  background: var(--kb-surface-2);
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.stat-strip {
  display: flex;
  flex-wrap: wrap;
  margin: 16px 0;
  padding: 0;
}

.stat-cell {
  flex: 1;
  min-width: 120px;
  padding: 17px 22px;
  border-right: 1px solid var(--kb-line);
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.stat-cell:last-child {
  border-right: none;
}

.stat-cell span {
  font-size: 10.5px;
  letter-spacing: 0.3px;
  color: var(--kb-muted);
}

.stat-cell strong {
  font-size: 23px;
  font-weight: 600;
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
}

.stat-cell strong.ok {
  color: var(--kb-success);
}

.stat-cell strong.bad {
  color: var(--kb-danger);
}

.stat-cell strong.strategy {
  font-size: 15px;
  letter-spacing: 0;
  color: var(--kb-primary);
}

.stat-wide {
  flex: 1.4;
}

.detail-tabs {
  overflow: hidden;
}

.detail-tabbar {
  padding: 6px 24px 0;
}

.detail-tabbar :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.detail-tabbar :deep(.el-tabs__item) {
  height: 48px;
  font-size: 13px;
}

.tab-body {
  padding: 22px 24px 26px;
}

@media (max-width: 900px) {
  .detail-hero {
    flex-direction: column;
    padding: 20px;
  }

  .hero-title {
    font-size: 22px;
  }

  .stat-cell {
    flex: 1 0 40%;
    border-right: none;
    padding: 6px 12px;
  }

  .tab-body {
    padding: 16px;
  }
}
</style>

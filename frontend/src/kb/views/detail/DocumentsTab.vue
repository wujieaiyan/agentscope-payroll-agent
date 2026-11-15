<template>
  <div class="documents-tab">
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        class="search"
        placeholder="搜索文档名称"
        clearable
        :prefix-icon="Search"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-tree-select
        v-model="filters.directoryId"
        class="dir-select"
        :data="directoryOptions"
        placeholder="全部目录"
        clearable
        check-strictly
        :render-after-expand="false"
        @change="reload"
      />
      <el-select v-model="filters.status" class="status-select" placeholder="全部状态" clearable @change="reload">
        <el-option label="已就绪" value="READY" />
        <el-option label="处理中" value="PROCESSING" />
        <el-option label="失败" value="FAILED" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <div class="toolbar-right">
        <span class="count">共 {{ total }} 个文档</span>
        <el-button :icon="Refresh" circle plain :loading="loading" @click="reload" />
        <el-button v-if="canMaintain" type="primary" :icon="UploadFilled" @click="uploadVisible = true">
          上传文档
        </el-button>
      </div>
    </div>

    <div v-if="canMaintain && selectedRows.length" class="batch-bar">
      <span>已选择 <strong>{{ selectedRows.length }}</strong> 个文档</span>
      <div class="batch-actions">
        <el-button size="small" @click="openBatch('MOVE')">移动到目录</el-button>
        <el-button size="small" @click="openBatch('SET_TAGS')">设置标签</el-button>
        <el-button size="small" @click="runBatch('ENABLE')">启用</el-button>
        <el-button size="small" @click="runBatch('DISABLE')">停用</el-button>
        <el-button size="small" type="danger" plain @click="runBatch('DELETE')">删除</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="documents"
      row-key="id"
      size="default"
      empty-text="暂无文档，先上传一份 PDF、Word、Excel 或 Markdown 文件"
      @selection-change="selectedRows = $event"
    >
      <el-table-column v-if="canMaintain" type="selection" width="42" />
      <el-table-column label="文档" min-width="240">
        <template #default="{ row }">
          <div class="doc-name-cell">
            <span class="ext-badge" :class="extClass(row.ext)">{{ row.ext }}</span>
            <div class="doc-name-text">
              <strong>{{ row.name }}</strong>
              <span class="doc-sub">
                {{ row.directoryName || '根目录' }} · {{ formatSize(row.sizeBytes) }}
                <template v-if="row.pageCount"> · {{ row.pageCount }} 页</template>
              </span>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <div class="status-cell">
            <el-tag size="small" :type="docStatusOf(row.status).type" effect="plain">
              {{ docStatusOf(row.status).label }}
            </el-tag>
            <span v-if="isProcessing(row)" class="processing-text">
              <el-icon class="is-loading"><Loading /></el-icon>
              {{ taskStatusMap[row.latestTaskStatus] || '处理中' }}
            </span>
            <el-tooltip v-else-if="row.status === 'FAILED'" :content="row.errorMessage || '处理失败'" placement="top">
              <span class="error-text">查看原因</span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="切片" width="80" align="center">
        <template #default="{ row }">
          <el-button text type="primary" :disabled="!row.chunkCount" @click="openChunks(row)">
            {{ row.chunkCount || 0 }}
          </el-button>
        </template>
      </el-table-column>

      <el-table-column label="标签" min-width="150">
        <template #default="{ row }">
          <div class="tag-list">
            <el-tag
              v-for="tag in row.tags"
              :key="tag.id"
              size="small"
              effect="plain"
              :style="tagStyle(tag)"
            >
              {{ tag.name }}
            </el-tag>
            <span v-if="!row.tags?.length" class="muted">-</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="uploadedByName" label="上传人" width="110" show-overflow-tooltip />
      <el-table-column label="更新时间" width="130">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>

      <el-table-column label="操作" width="176" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button text type="primary" size="small" @click="openChunks(row)">切片</el-button>
            <el-button text size="small" @click="download(row)">下载</el-button>
            <el-dropdown v-if="canMaintain" trigger="click" @command="(command) => handleRowCommand(command, row)">
              <el-button text size="small">
                更多<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">重命名 / 移动</el-dropdown-item>
                  <el-dropdown-item command="tags">设置标签</el-dropdown-item>
                  <el-dropdown-item command="reparse" :disabled="row.status === 'DISABLED'">重新解析</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'DISABLED'" command="enable">启用</el-dropdown-item>
                  <el-dropdown-item v-else command="disable">停用</el-dropdown-item>
                  <el-dropdown-item divided command="delete">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
        @current-change="loadDocuments"
      />
    </div>

    <!-- Upload -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="580px" @closed="resetUpload">
      <div class="upload-tip">
        支持 PDF、Word(docx)、Excel(xlsx/xls)、Markdown(md / markdown)，单文件不超过 100 MB，单次最多 50 个。
      </div>
      <el-form label-position="top">
        <el-form-item label="目标目录">
          <el-tree-select
            v-model="uploadForm.directoryId"
            :data="directoryOptions"
            placeholder="默认为根目录"
            clearable
            check-strictly
            :render-after-expand="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="重复文件处理">
          <el-radio-group v-model="uploadForm.dedupeAction">
            <el-radio value="SKIP">跳过并提示</el-radio>
            <el-radio value="OVERWRITE">覆盖旧文档</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-upload
        ref="uploadRef"
        v-model:file-list="uploadFiles"
        class="uploader"
        drag
        multiple
        :auto-upload="false"
        :limit="50"
        :on-exceed="onExceed"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择文件</em></div>
      </el-upload>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!uploadFiles.length" @click="submitUpload">
          上传并入库
        </el-button>
      </template>
    </el-dialog>

    <!-- Chunks -->
    <el-drawer v-model="chunkVisible" :title="`切片预览 · ${chunkDoc?.name || ''}`" size="620px">
      <div v-loading="chunkLoading" class="chunk-drawer">
        <div v-if="!chunkLoading && !chunks.length" class="chunk-empty">
          <el-empty description="该文档尚未生成切片" :image-size="90" />
        </div>
        <div v-for="chunk in chunks" :key="chunk.id" class="chunk-item">
          <div class="chunk-head">
            <span class="chunk-seq mono">#{{ chunk.seq }}</span>
            <el-tag size="small" effect="plain">{{ chunk.level }}</el-tag>
            <span class="chunk-meta">{{ chunk.tokenCount }} tokens</span>
            <span v-if="chunk.pageNo" class="chunk-meta">第 {{ chunk.pageNo }} 页</span>
          </div>
          <div v-if="chunk.titlePath" class="chunk-path">{{ chunk.titlePath }}</div>
          <pre class="chunk-content">{{ chunk.content }}</pre>
        </div>
        <div v-if="chunkTotal > chunkPageSize" class="chunk-pager">
          <el-pagination
            v-model:current-page="chunkPage"
            :page-size="chunkPageSize"
            :total="chunkTotal"
            layout="prev, pager, next"
            small
            background
            @current-change="loadChunks"
          />
        </div>
      </div>
    </el-drawer>

    <!-- Edit / move -->
    <el-dialog v-model="editVisible" title="编辑文档" width="460px">
      <el-form label-position="top">
        <el-form-item label="文档名称">
          <el-input v-model="editForm.name" maxlength="255" />
        </el-form-item>
        <el-form-item label="所属目录">
          <el-tree-select
            v-model="editForm.directoryId"
            :data="directoryOptions"
            placeholder="选择目录"
            check-strictly
            :render-after-expand="false"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- Tags -->
    <el-dialog v-model="tagVisible" :title="`设置标签 · ${tagDoc?.name || ''}`" width="460px">
      <el-checkbox-group v-model="tagSelection" class="tag-checkboxes">
        <el-checkbox v-for="tag in tags" :key="tag.id" :value="tag.id" border>
          {{ tag.name }}
        </el-checkbox>
      </el-checkbox-group>
      <el-empty v-if="!tags.length" description="尚未创建标签，可先在标签管理中创建" :image-size="80" />
      <template #footer>
        <el-button @click="tagVisible = false">取消</el-button>
        <el-button type="primary" :loading="tagSaving" @click="saveTags">保存</el-button>
      </template>
    </el-dialog>

    <!-- Batch target dialog -->
    <el-dialog v-model="batchVisible" :title="batchMode === 'MOVE' ? '批量移动' : '批量设置标签'" width="460px">
      <el-form v-if="batchMode === 'MOVE'" label-position="top">
        <el-form-item label="目标目录">
          <el-tree-select
            v-model="batchDirectoryId"
            :data="directoryOptions"
            placeholder="选择目录"
            check-strictly
            :render-after-expand="false"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <el-checkbox-group v-else v-model="batchTagIds" class="tag-checkboxes">
        <el-checkbox v-for="tag in tags" :key="tag.id" :value="tag.id" border>
          {{ tag.name }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batching" @click="submitBatch">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Loading,
  Refresh,
  Search,
  UploadFilled
} from '@element-plus/icons-vue'
import { directoryApi, documentApi, tagApi } from '../../api'
import { formatSize, formatTime } from '../../utils/format'
import { downloadDocument } from '../../utils/download'
import { docStatusMap, statusOf, taskStatusMap } from '../../utils/status'

const props = defineProps({
  series: { type: Object, required: true },
  role: { type: String, default: 'READER' }
})

const emit = defineEmits(['changed', 'jump-tasks'])

const canMaintain = computed(() => ['EDITOR', 'ADMIN', 'OWNER'].includes(props.role))

const loading = ref(false)
const documents = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const selectedRows = ref([])
const directories = ref([])
const tags = ref([])
const filters = reactive({ keyword: '', directoryId: null, status: null })

const directoryOptions = computed(() => {
  const mapNode = (node) => ({
    value: node.id,
    label: node.name,
    children: (node.children || []).map(mapNode)
  })
  return directories.value.map(mapNode)
})

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadRef = ref(null)
const uploadFiles = ref([])
const uploadForm = reactive({ directoryId: null, dedupeAction: 'SKIP' })

const chunkVisible = ref(false)
const chunkLoading = ref(false)
const chunkDoc = ref(null)
const chunks = ref([])
const chunkTotal = ref(0)
const chunkPage = ref(1)
const chunkPageSize = 10

const editVisible = ref(false)
const editSaving = ref(false)
const editDoc = ref(null)
const editForm = reactive({ name: '', directoryId: null })

const tagVisible = ref(false)
const tagSaving = ref(false)
const tagDoc = ref(null)
const tagSelection = ref([])

const batchVisible = ref(false)
const batching = ref(false)
const batchMode = ref('MOVE')
const batchDirectoryId = ref(null)
const batchTagIds = ref([])

let pollTimer = null

function docStatusOf(value) {
  return statusOf(docStatusMap, value)
}

function extClass(ext) {
  const value = (ext || '').toLowerCase()
  if (value === 'pdf') return 'pdf'
  if (value === 'docx' || value === 'doc') return 'doc'
  if (value === 'xlsx' || value === 'xls') return 'xls'
  return 'md'
}

function tagStyle(tag) {
  return {
    color: tag.color || '#1f6f5c',
    borderColor: `${tag.color || '#1f6f5c'}55`,
    background: `${tag.color || '#1f6f5c'}12`
  }
}

function isProcessing(row) {
  return ['PENDING', 'PARSING', 'CHUNKING', 'INDEXING'].includes(row.status)
}

async function loadDirectories() {
  try {
    directories.value = await directoryApi.tree(props.series.id)
  } catch (error) {
    directories.value = []
  }
}

async function loadTags() {
  try {
    tags.value = await tagApi.list(props.series.id)
  } catch (error) {
    tags.value = []
  }
}

async function fetchDocuments() {
  const params = {
    keyword: filters.keyword || undefined,
    directoryId: filters.directoryId || undefined,
    page: page.value,
    size: pageSize
  }
  if (filters.status === 'PROCESSING') {
    const statuses = ['PENDING', 'PARSING', 'CHUNKING', 'INDEXING']
    const results = await Promise.all(
      statuses.map((status) =>
        documentApi.list(props.series.id, { ...params, status, page: 1, size: 100 })
      )
    )
    const merged = results
      .flatMap((result) => result.items || [])
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
    return {
      items: merged.slice((page.value - 1) * pageSize, page.value * pageSize),
      total: merged.length
    }
  }
  return documentApi.list(props.series.id, { ...params, status: filters.status || undefined })
}

async function loadDocuments() {
  loading.value = true
  try {
    const data = await fetchDocuments()
    documents.value = data.items || []
    total.value = data.total || 0
    schedulePolling()
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  loadDocuments()
}

function schedulePolling() {
  const active = documents.value.some((doc) => isProcessing(doc))
  if (active && !pollTimer) {
    pollTimer = window.setInterval(async () => {
      if (loading.value) return
      const data = await fetchDocuments()
      documents.value = data.items || []
      total.value = data.total || 0
      if (!documents.value.some((doc) => isProcessing(doc))) {
        stopPolling()
        emit('changed')
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

function resetUpload() {
  uploadFiles.value = []
  uploadForm.dedupeAction = 'SKIP'
  uploadForm.directoryId = null
}

function onExceed() {
  ElMessage.warning('单次最多上传 50 个文件')
}

async function submitUpload() {
  if (!uploadFiles.value.length) return
  uploading.value = true
  try {
    const formData = new FormData()
    uploadFiles.value.forEach((file) => {
      const raw = file.raw || file
      formData.append('files', raw)
    })
    if (uploadForm.directoryId) {
      formData.append('directoryId', uploadForm.directoryId)
    }
    formData.append('dedupeAction', uploadForm.dedupeAction)
    const result = await documentApi.upload(props.series.id, formData)
    const success = result.items?.length || 0
    const failed = result.failedItems || []
    if (success) {
      ElMessage.success(`已提交 ${success} 个文档，正在后台解析入库`)
    }
    if (failed.length) {
      const detail = failed.map((item) => `${item.name}：${item.message}`).join('\n')
      await ElMessageBox.alert(detail, `有 ${failed.length} 个文件未上传`, {
        confirmButtonText: '知道了',
        type: 'warning'
      })
    }
    uploadVisible.value = false
    resetUpload()
    await loadDocuments()
    emit('changed')
  } finally {
    uploading.value = false
  }
}

async function openChunks(row) {
  chunkDoc.value = row
  chunkPage.value = 1
  chunkVisible.value = true
  await loadChunks()
}

async function loadChunks() {
  if (!chunkDoc.value) return
  chunkLoading.value = true
  try {
    const data = await documentApi.chunks(props.series.id, chunkDoc.value.id, {
      page: chunkPage.value,
      size: chunkPageSize
    })
    chunks.value = data.items || []
    chunkTotal.value = data.total || 0
  } finally {
    chunkLoading.value = false
  }
}

function openEdit(row) {
  editDoc.value = row
  editForm.name = row.name
  editForm.directoryId = row.directoryId
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.name.trim()) {
    ElMessage.warning('请输入文档名称')
    return
  }
  editSaving.value = true
  try {
    await documentApi.update(props.series.id, editDoc.value.id, {
      name: editForm.name.trim(),
      directoryId: editForm.directoryId
    })
    ElMessage.success('文档已更新')
    editVisible.value = false
    await loadDocuments()
  } finally {
    editSaving.value = false
  }
}

function openTagDialog(row) {
  tagDoc.value = row
  tagSelection.value = (row.tags || []).map((tag) => tag.id)
  tagVisible.value = true
}

async function saveTags() {
  tagSaving.value = true
  try {
    await documentApi.setTags(props.series.id, tagDoc.value.id, tagSelection.value)
    ElMessage.success('标签已更新')
    tagVisible.value = false
    await Promise.all([loadDocuments(), loadTags()])
  } finally {
    tagSaving.value = false
  }
}

async function download(row) {
  try {
    await downloadDocument(props.series.id, row.id, row.name)
  } catch (error) {
    ElMessage.error('下载失败')
  }
}

async function handleRowCommand(command, row) {
  if (command === 'edit') {
    openEdit(row)
    return
  }
  if (command === 'tags') {
    openTagDialog(row)
    return
  }
  if (command === 'reparse') {
    await documentApi.reparse(props.series.id, row.id)
    ElMessage.success('已提交重新解析任务')
    await loadDocuments()
    emit('jump-tasks')
    return
  }
  if (command === 'disable') {
    await documentApi.disable(props.series.id, row.id)
    ElMessage.success('文档已停用')
    await loadDocuments()
    emit('changed')
    return
  }
  if (command === 'enable') {
    await documentApi.enable(props.series.id, row.id)
    ElMessage.success('文档已启用')
    await loadDocuments()
    emit('changed')
    return
  }
  if (command === 'delete') {
    try {
      await ElMessageBox.confirm(`确认删除文档「${row.name}」？删除后切片会被清理。`, '删除文档', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await documentApi.remove(props.series.id, row.id)
      ElMessage.success('文档已删除')
      await loadDocuments()
      emit('changed')
    } catch (error) {
      // canceled
    }
  }
}

function openBatch(mode) {
  batchMode.value = mode
  batchDirectoryId.value = null
  batchTagIds.value = []
  batchVisible.value = true
}

async function submitBatch() {
  if (batchMode.value === 'MOVE' && !batchDirectoryId.value) {
    ElMessage.warning('请选择目标目录')
    return
  }
  batching.value = true
  try {
    const result = await documentApi.batch(props.series.id, {
      action: batchMode.value,
      documentIds: selectedRows.value.map((row) => row.id),
      directoryId: batchMode.value === 'MOVE' ? batchDirectoryId.value : undefined,
      tagIds: batchMode.value === 'SET_TAGS' ? batchTagIds.value : undefined
    })
    ElMessage.success(`操作完成：成功 ${result.successCount} 个，失败 ${result.failedCount} 个`)
    batchVisible.value = false
    await loadDocuments()
    emit('changed')
  } finally {
    batching.value = false
  }
}

async function runBatch(action) {
  const labelMap = { DELETE: '删除', ENABLE: '启用', DISABLE: '停用' }
  try {
    await ElMessageBox.confirm(
      `确认批量${labelMap[action]} ${selectedRows.value.length} 个文档？`,
      `批量${labelMap[action]}`,
      { confirmButtonText: labelMap[action], cancelButtonText: '取消', type: 'warning' }
    )
    const result = await documentApi.batch(props.series.id, {
      action,
      documentIds: selectedRows.value.map((row) => row.id)
    })
    ElMessage.success(`操作完成：成功 ${result.successCount} 个，失败 ${result.failedCount} 个`)
    await loadDocuments()
    emit('changed')
  } catch (error) {
    // canceled
  }
}

watch(
  () => props.series.id,
  () => {
    page.value = 1
    loadDirectories()
    loadTags()
    loadDocuments()
  }
)

onMounted(() => {
  loadDirectories()
  loadTags()
  loadDocuments()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.documents-tab {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.search {
  width: 260px;
}

.dir-select {
  width: 190px;
}

.status-select {
  width: 130px;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.count {
  font-size: 12px;
  color: var(--kb-muted);
}

.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-radius: 7px;
  border: 1px solid #d5e3dc;
  background: var(--kb-primary-tint);
  font-size: 12px;
  color: var(--kb-primary-ink, #225647);
}

.batch-actions {
  display: flex;
  gap: 6px;
}

.doc-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.ext-badge {
  width: 34px;
  height: 34px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.4px;
  flex-shrink: 0;
  text-transform: uppercase;
}

.ext-badge.pdf {
  background: #f8ecea;
  color: #a94b42;
}

.ext-badge.doc {
  background: #edf1f5;
  color: #496785;
}

.ext-badge.xls {
  background: #eaf2ed;
  color: #3d7357;
}

.ext-badge.md {
  background: #f1eef5;
  color: #6d6088;
}

.doc-name-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.doc-name-text strong {
  font-size: 13px;
  font-weight: 560;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-sub {
  font-size: 10px;
  color: var(--kb-muted);
}

.status-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.processing-text {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--kb-warning);
}

.error-text {
  font-size: 10px;
  color: var(--kb-danger);
  cursor: help;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.upload-tip {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-line-soft);
  color: var(--kb-muted);
  font-size: 11px;
  line-height: 1.7;
}

.uploader {
  margin-top: 4px;
}

.upload-icon {
  font-size: 42px;
  color: #9dbfb2;
  margin-top: 18px;
}

.chunk-drawer {
  min-height: 200px;
}

.chunk-item {
  padding: 14px;
  border: 1px solid var(--kb-line);
  border-radius: 7px;
  margin-bottom: 12px;
  background: var(--kb-surface-2);
}

.chunk-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.chunk-seq {
  font-size: 11px;
  color: var(--kb-primary);
  font-weight: 600;
}

.chunk-meta {
  font-size: 10px;
  color: var(--kb-muted);
}

.chunk-path {
  font-size: 11px;
  color: var(--kb-muted);
  margin-bottom: 8px;
}

.chunk-content {
  margin: 0;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.8;
  color: var(--kb-ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}

.chunk-pager {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.chunk-empty {
  padding: 30px 0;
}

.tag-checkboxes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-checkboxes :deep(.el-checkbox) {
  margin-right: 0;
}

@media (max-width: 900px) {
  .search,
  .dir-select,
  .status-select {
    width: 100%;
  }

  .toolbar-right {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>

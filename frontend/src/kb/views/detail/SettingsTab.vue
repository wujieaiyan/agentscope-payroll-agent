<template>
  <div class="settings-tab">
    <div class="settings-grid">
      <div class="settings-main">
        <section class="settings-section">
          <div class="section-head">
            <div>
              <h3 class="section-title">基础信息</h3>
              <p class="section-subtitle">修改知识库名称、所属部门与简介，不影响已有配置版本。</p>
            </div>
            <el-button type="primary" :loading="savingInfo" @click="saveInfo">保存信息</el-button>
          </div>
          <el-form label-position="top" class="info-form">
            <el-form-item label="知识库主题" required>
              <el-input v-model="info.name" maxlength="50" show-word-limit />
            </el-form-item>
            <el-form-item label="所属部门" required>
              <el-select v-model="info.deptId" style="width: 100%">
                <el-option v-for="dept in departments" :key="dept.id" :label="dept.name" :value="dept.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="知识库简介" class="span-2">
              <el-input v-model="info.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
            </el-form-item>
          </el-form>
        </section>

        <section class="settings-section">
          <div class="section-head">
            <div>
              <h3 class="section-title">索引与解析配置</h3>
              <p class="section-subtitle">保存后生成新的配置版本，后续上传的文档将使用新版本。</p>
            </div>
            <el-tag v-if="currentVersionNo" size="small" effect="plain">当前 v{{ currentVersionNo }}</el-tag>
          </div>

          <el-form label-position="top" class="config-form">
            <el-form-item label="索引策略">
              <el-radio-group v-model="config.indexStrategy">
                <el-radio-button value="HYBRID">混合检索</el-radio-button>
                <el-radio-button value="VECTOR">向量检索</el-radio-button>
                <el-radio-button value="GRAPH" disabled>图谱增强</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="支持的文档类型">
              <el-checkbox-group v-model="config.parse.docTypes">
                <el-checkbox v-for="type in docTypes" :key="type.key" :value="type.key">
                  {{ type.label }}
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>

            <el-form-item label="各类型解析策略">
              <div class="parse-rows">
                <div v-for="type in enabledTypes" :key="type.key" class="parse-row">
                  <span class="parse-label">{{ type.label }}</span>
                  <el-select v-model="config.parse.perType[type.key].strategy" size="small" style="width: 190px">
                    <el-option label="文字提取" value="NATIVE" />
                    <el-option label="自动判断" value="AUTO" :disabled="type.key !== 'PDF'" />
                    <el-option label="OCR 识别（未接入）" value="OCR" disabled />
                  </el-select>
                </div>
              </div>
            </el-form-item>

            <el-form-item label="分块策略">
              <el-select v-model="config.chunk.strategy" style="width: 240px">
                <el-option v-for="(label, key) in chunkStrategyMap" :key="key" :label="label" :value="key" />
              </el-select>
            </el-form-item>

            <div class="field-row">
              <el-form-item label="最大切片（tokens）">
                <el-input-number v-model="config.chunk.common.maxTokens" :min="64" :max="4096" :step="64" />
              </el-form-item>
              <el-form-item label="重叠（tokens）">
                <el-input-number
                  v-model="config.chunk.common.overlapTokens"
                  :min="0"
                  :max="Math.floor(config.chunk.common.maxTokens / 2)"
                  :step="16"
                />
              </el-form-item>
              <el-form-item label="最小切片（tokens）">
                <el-input-number
                  v-model="config.chunk.common.minTokens"
                  :min="0"
                  :max="config.chunk.common.maxTokens"
                  :step="10"
                />
              </el-form-item>
            </div>

            <div v-if="config.chunk.strategy === 'PARENT_CHILD'" class="field-row">
              <el-form-item label="父切片（tokens）">
                <el-input-number v-model="config.chunk.parentChild.parentTokens" :min="256" :max="4096" :step="128" />
              </el-form-item>
              <el-form-item label="子切片（tokens）">
                <el-input-number v-model="config.chunk.parentChild.childTokens" :min="64" :max="2048" :step="64" />
              </el-form-item>
              <el-form-item label="子切片重叠">
                <el-input-number v-model="config.chunk.parentChild.childOverlap" :min="0" :max="256" :step="16" />
              </el-form-item>
            </div>

            <el-form-item v-if="config.chunk.strategy === 'CUSTOM'" label="自定义分隔符（正则）">
              <el-input v-model="config.chunk.custom.separator" style="max-width: 320px" />
            </el-form-item>

            <el-form-item v-if="config.chunk.strategy === 'TITLE_ENHANCED'" label="标题模板">
              <el-input v-model="config.chunk.titleEnhanced.template" style="max-width: 420px" />
            </el-form-item>
          </el-form>
        </section>

        <section class="settings-section">
          <div class="section-head">
            <div>
              <h3 class="section-title">高级检索</h3>
              <p class="section-subtitle">配置召回、精排、相似度阈值与引用溯源。</p>
            </div>
          </div>

          <el-form label-position="top" class="config-form">
            <div class="switch-row">
              <label class="switch-item">
                <el-switch v-model="config.retrieval.hybridEnabled" />
                <span><strong>混合检索</strong><small>向量语义 + BM25 关键词加权融合</small></span>
              </label>
              <label class="switch-item">
                <el-switch v-model="config.retrieval.rerankEnabled" />
                <span><strong>精排重排序</strong><small>本地启发式覆盖度精排</small></span>
              </label>
              <label class="switch-item">
                <el-switch v-model="config.retrieval.citation.enabled" />
                <span><strong>引用溯源</strong><small>返回文档、页码与标题路径</small></span>
              </label>
            </div>

            <div class="field-row">
              <el-form-item :label="`向量权重：${Math.round(config.retrieval.vectorWeight * 100)}%`">
                <el-slider
                  v-model="config.retrieval.vectorWeight"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  :disabled="!config.retrieval.hybridEnabled"
                />
              </el-form-item>
              <el-form-item :label="`相似度阈值：${Number(config.retrieval.similarityThreshold).toFixed(2)}`">
                <el-slider v-model="config.retrieval.similarityThreshold" :min="0" :max="1" :step="0.01" />
              </el-form-item>
            </div>

            <div class="field-row">
              <el-form-item label="TopK 召回">
                <el-input-number v-model="config.retrieval.recallTopK" :min="1" :max="100" />
              </el-form-item>
              <el-form-item label="精排返回数">
                <el-input-number v-model="config.retrieval.rerankTopN" :min="1" :max="20" />
              </el-form-item>
              <el-form-item label="引用粒度">
                <el-select v-model="config.retrieval.citation.granularity" style="width: 150px">
                  <el-option label="段落级" value="PARAGRAPH" />
                  <el-option label="句子级" value="SENTENCE" />
                  <el-option label="切片级" value="CHUNK" />
                </el-select>
              </el-form-item>
            </div>
          </el-form>
        </section>

        <section class="save-bar">
          <el-input v-model="changeNote" placeholder="变更说明（可选），例如：调整切片长度并开启精排" maxlength="100" />
          <el-select v-model="rebuildScope" style="width: 190px">
            <el-option label="保存后不重建" value="NONE" />
            <el-option label="重建失败文档" value="FAILED" />
            <el-option label="全量重建所有文档" value="FULL" />
          </el-select>
          <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存并生成新版本</el-button>
        </section>
      </div>

      <aside class="version-panel">
        <div class="version-head">
          <h3 class="section-title">配置版本</h3>
          <span class="muted">{{ versions.length }} 个版本</span>
        </div>
        <div v-loading="versionsLoading" class="version-list">
          <button
            v-for="version in versions"
            :key="version.versionId"
            type="button"
            class="version-item"
            :class="{ active: version.versionId === formVersionId }"
            @click="loadVersion(version)"
          >
            <div class="version-top">
              <strong>v{{ version.versionNo }}</strong>
              <el-tag v-if="version.versionId === series.configVersionId" size="small" type="success" effect="plain">
                生效中
              </el-tag>
            </div>
            <p class="version-meta">
              {{ indexStrategyMap[version.indexStrategy] || version.indexStrategy }} ·
              {{ chunkStrategyMap[version.chunk?.strategy] || version.chunk?.strategy }}
            </p>
            <p class="version-note">{{ version.changeNote || '未填写变更说明' }}</p>
            <span class="version-time">{{ formatTime(version.createdAt) }}</span>
          </button>
          <el-empty v-if="!versionsLoading && !versions.length" description="暂无版本" :image-size="70" />
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configApi, orgApi, seriesApi } from '../../api'
import { formatTime } from '../../utils/format'
import { chunkStrategyMap, indexStrategyMap } from '../../utils/status'

const props = defineProps({
  series: { type: Object, required: true },
  role: { type: String, default: 'ADMIN' }
})

const emit = defineEmits(['changed'])

const docTypes = [
  { key: 'PDF', label: 'PDF' },
  { key: 'DOCX', label: 'WORD' },
  { key: 'XLSX', label: 'EXCEL' },
  { key: 'MD', label: 'MARKDOWN' }
]

const savingInfo = ref(false)
const savingConfig = ref(false)
const versionsLoading = ref(false)
const departments = ref([])
const versions = ref([])
const formVersionId = ref(null)
const currentVersionNo = ref(null)
const changeNote = ref('')
const rebuildScope = ref('NONE')

const info = reactive({
  name: props.series.name,
  deptId: props.series.deptId,
  description: props.series.description || ''
})

function blankConfig() {
  return {
    indexStrategy: props.series.indexStrategy || 'HYBRID',
    parse: {
      docTypes: ['PDF', 'DOCX', 'XLSX', 'MD'],
      perType: {
        PDF: { enabled: true, strategy: 'AUTO' },
        DOCX: { enabled: true, strategy: 'NATIVE' },
        XLSX: { enabled: true, strategy: 'NATIVE' },
        MD: { enabled: true, strategy: 'NATIVE' }
      },
      ocr: { languages: ['zh', 'en'], minConfidence: 0.6, keepLayout: true },
      options: { tableStructure: true, filterHeaderFooter: true, ocrEmbeddedImages: false }
    },
    chunk: {
      strategy: 'DEFAULT',
      common: {
        maxTokens: 512,
        overlapTokens: 64,
        minTokens: 50,
        keepTableWhole: true,
        tokenizer: 'cl100k_base'
      },
      byLine: { maxTokens: 512, dropEmpty: true, mergeShortLines: true },
      parentChild: { parentTokens: 1024, childTokens: 256, childOverlap: 32, maxChildrenPerParent: 8 },
      titleEnhanced: { template: '{doc} > {h1} > {h2}', keepTitlePath: true },
      custom: { separator: '\\n\\n', keepSeparator: true }
    },
    retrieval: {
      hybridEnabled: true,
      vectorWeight: 0.7,
      rerankEnabled: true,
      rerankModel: 'local-hash-rerank',
      citation: { enabled: true, granularity: 'PARAGRAPH' },
      recallTopK: 20,
      rerankTopN: 5,
      similarityThreshold: 0.3,
      noAnswerStrategy: 'EMPTY'
    }
  }
}

const config = reactive(blankConfig())

const enabledTypes = computed(() =>
  docTypes.filter((type) => config.parse.docTypes.includes(type.key))
)

function deepMerge(target, source) {
  if (!source || typeof source !== 'object') return target
  Object.keys(source).forEach((key) => {
    const value = source[key]
    if (Array.isArray(value)) {
      target[key] = [...value]
    } else if (value && typeof value === 'object') {
      if (!target[key] || typeof target[key] !== 'object') target[key] = {}
      deepMerge(target[key], value)
    } else if (value !== undefined) {
      target[key] = value
    }
  })
  return target
}

function applyConfig(source) {
  const base = blankConfig()
  base.indexStrategy = source.indexStrategy || base.indexStrategy
  deepMerge(base.parse, source.parse)
  deepMerge(base.chunk, source.chunk)
  deepMerge(base.retrieval, source.retrieval)
  Object.keys(config).forEach((key) => delete config[key])
  Object.assign(config, base)
}

async function loadInfo() {
  info.name = props.series.name
  info.deptId = props.series.deptId
  info.description = props.series.description || ''
}

async function loadDepartments() {
  try {
    departments.value = await orgApi.departments()
  } catch (error) {
    departments.value = []
  }
}

async function loadConfig() {
  try {
    const data = await configApi.current(props.series.id)
    applyConfig(data)
    formVersionId.value = data.versionId
    currentVersionNo.value = data.versionNo
  } catch (error) {
    // defaults remain
  }
}

async function loadVersions() {
  versionsLoading.value = true
  try {
    versions.value = await configApi.versions(props.series.id)
  } catch (error) {
    versions.value = []
  } finally {
    versionsLoading.value = false
  }
}

function loadVersion(version) {
  applyConfig(version)
  formVersionId.value = version.versionId
  ElMessage.info(`已载入 v${version.versionNo} 的配置，保存后将生成新版本`)
}

async function saveInfo() {
  if (!info.name.trim() || info.name.trim().length < 2) {
    ElMessage.warning('名称需为 2-50 个字符')
    return
  }
  savingInfo.value = true
  try {
    await seriesApi.update(props.series.id, {
      name: info.name.trim(),
      deptId: info.deptId,
      description: info.description,
      rowVersion: props.series.rowVersion
    })
    ElMessage.success('基础信息已保存')
    emit('changed')
  } finally {
    savingInfo.value = false
  }
}

function validateConfig() {
  if (!config.parse.docTypes.length) {
    ElMessage.warning('至少保留一种文档类型')
    return false
  }
  const common = config.chunk.common
  if (common.overlapTokens > Math.floor(common.maxTokens / 2)) {
    ElMessage.warning('重叠长度不能超过最大切片长度的一半')
    return false
  }
  if (common.minTokens > common.maxTokens) {
    ElMessage.warning('最小切片长度不能大于最大切片长度')
    return false
  }
  if (config.chunk.strategy === 'PARENT_CHILD') {
    const pc = config.chunk.parentChild
    if (pc.childTokens >= pc.parentTokens) {
      ElMessage.warning('子切片长度必须小于父切片长度')
      return false
    }
  }
  if (config.chunk.strategy === 'CUSTOM') {
    try {
      // eslint-disable-next-line no-new
      new RegExp(config.chunk.custom.separator)
    } catch (error) {
      ElMessage.warning('自定义分隔符不是合法的正则表达式')
      return false
    }
  }
  if (config.retrieval.rerankTopN > config.retrieval.recallTopK) {
    ElMessage.warning('精排返回数不能大于 TopK 召回数')
    return false
  }
  return true
}

async function saveConfig() {
  if (!validateConfig()) return
  if (rebuildScope.value !== 'NONE') {
    try {
      await ElMessageBox.confirm(
        rebuildScope.value === 'FULL'
          ? '全量重建会重新处理所有文档，可能耗时较长，确认继续？'
          : '将重新处理所有失败状态的文档，确认继续？',
        '确认重建',
        { confirmButtonText: '保存并重建', cancelButtonText: '取消', type: 'warning' }
      )
    } catch (error) {
      return
    }
  }
  savingConfig.value = true
  try {
    const payload = {
      indexStrategy: config.indexStrategy,
      parse: config.parse,
      chunk: config.chunk,
      retrieval: config.retrieval,
      changeNote: changeNote.value || null
    }
    if (rebuildScope.value !== 'NONE') {
      payload.rebuild = { scope: rebuildScope.value }
    }
    const result = await configApi.save(props.series.id, payload)
    ElMessage.success(`已生成配置版本 v${result.versionNo}`)
    changeNote.value = ''
    rebuildScope.value = 'NONE'
    await Promise.all([loadConfig(), loadVersions()])
    emit('changed')
  } finally {
    savingConfig.value = false
  }
}

watch(
  () => props.series.id,
  () => {
    loadInfo()
    loadConfig()
    loadVersions()
  }
)

onMounted(() => {
  loadInfo()
  loadDepartments()
  loadConfig()
  loadVersions()
})
</script>

<style scoped>
.settings-tab {
  max-width: 1280px;
}

.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 18px;
}

.settings-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.settings-section {
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  padding: 20px;
  background: var(--kb-surface);
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.info-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.span-2 {
  grid-column: span 2;
}

.config-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.parse-rows {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 22px;
}

.parse-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.parse-label {
  width: 74px;
  font-size: 12px;
  color: var(--kb-ink-soft);
}

.field-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0 28px;
}

.switch-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.switch-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border-radius: 7px;
  border: 1px solid var(--kb-line);
  background: var(--kb-surface-2);
  cursor: pointer;
}

.switch-item span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.switch-item strong {
  font-size: 12px;
}

.switch-item small {
  font-size: 10px;
  color: var(--kb-muted);
}

.save-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-radius: 8px;
  border: 1px solid #d5e3dc;
  background: var(--kb-primary-tint);
}

.save-bar :deep(.el-input) {
  flex: 1;
}

.version-panel {
  align-self: flex-start;
  position: sticky;
  top: 80px;
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  padding: 16px;
  background: var(--kb-surface);
}

.version-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 12px;
}

.version-head .muted {
  font-size: 11px;
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 640px;
  overflow-y: auto;
}

.version-item {
  text-align: left;
  border: 1px solid var(--kb-line);
  border-radius: 7px;
  padding: 11px 12px;
  background: var(--kb-surface-2);
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.version-item:hover {
  border-color: var(--kb-line-strong);
}

.version-item.active {
  border-color: var(--kb-primary);
  background: var(--kb-primary-tint);
}

.version-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.version-top strong {
  font-size: 13px;
}

.version-meta {
  margin: 6px 0 3px;
  font-size: 10px;
  color: var(--kb-ink-soft);
}

.version-note {
  margin: 0;
  font-size: 10px;
  color: var(--kb-muted);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.version-time {
  display: block;
  margin-top: 6px;
  font-size: 9px;
  color: var(--kb-faint);
}

@media (max-width: 1080px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }

  .version-panel {
    position: static;
  }

  .switch-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .info-form {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }

  .save-bar {
    flex-wrap: wrap;
  }
}
</style>

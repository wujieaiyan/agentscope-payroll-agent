<template>
  <div class="retrieval-tab">
    <div class="search-panel">
      <div class="search-head">
        <div>
          <h3 class="section-title">检索测试</h3>
          <p class="section-subtitle">输入问题验证召回效果，结果优先来自 Elasticsearch 向量与 BM25 混合检索，服务不可用时自动回退本地索引；本页不调用生成式模型。</p>
        </div>
        <div class="search-config">
          <el-tag size="small" effect="plain">{{ indexStrategy }}</el-tag>
          <span class="config-text">Top {{ form.topK }} · 阈值 {{ Number(form.threshold).toFixed(2) }}</span>
        </div>
      </div>

      <div class="query-row">
        <el-input
          v-model="form.query"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          resize="none"
          placeholder="例如：绩效工资 A 档发多少？"
          @keydown.enter.exact.prevent="search"
        />
        <el-button class="search-btn" type="primary" :icon="Search" :loading="loading" @click="search">
          检索
        </el-button>
      </div>

      <div class="options-row">
        <el-checkbox v-model="form.hybrid">混合检索（向量 + BM25）</el-checkbox>
        <el-checkbox v-model="form.rerank">启用本地精排</el-checkbox>
        <div class="option-item">
          <span>TopK</span>
          <el-input-number v-model="form.topK" :min="1" :max="20" size="small" />
        </div>
        <div class="option-item">
          <span>阈值</span>
          <el-input-number v-model="form.threshold" :min="0" :max="1" :step="0.01" size="small" />
        </div>
      </div>

      <div class="quick-row">
        <span class="quick-label">示例问题</span>
        <button v-for="item in samples" :key="item" type="button" class="quick-chip" @click="useSample(item)">
          {{ item }}
        </button>
      </div>
    </div>

    <div v-if="response" class="result-panel">
      <div class="result-summary">
        <div class="summary-item">
          <span>候选切片</span>
          <strong>{{ response.totalCandidates }}</strong>
        </div>
        <div class="summary-item">
          <span>返回结果</span>
          <strong>{{ response.returned }}</strong>
        </div>
        <div class="summary-item">
          <span>耗时</span>
          <strong>{{ response.tookMs }} ms</strong>
        </div>
        <div class="summary-item">
          <span>索引策略</span>
          <strong>{{ indexStrategyMap[response.indexStrategy] || response.indexStrategy }}</strong>
        </div>
        <div class="summary-item">
          <span>检索引擎</span>
          <strong>{{ response.retrievalEngine === 'ELASTICSEARCH' ? 'Elasticsearch' : '本地 H2' }}</strong>
        </div>
        <div class="summary-item">
          <span>模式</span>
          <strong>{{ response.hybrid ? '混合' : '向量' }} · {{ response.rerank ? '精排' : '不精排' }}</strong>
        </div>
      </div>

      <el-alert
        v-if="response.noAnswer"
        type="info"
        :closable="false"
        show-icon
        title="未找到达到相似度阈值的内容"
        description="可以尝试降低阈值、切换为混合检索，或确认文档已经解析完成。"
      />

      <div v-else class="citation-list">
        <div v-for="(item, index) in response.citations" :key="item.chunkId" class="citation-card">
          <div class="citation-head">
            <div class="citation-rank">{{ index + 1 }}</div>
            <div class="citation-title">
              <strong>{{ item.documentName || '未知文档' }}</strong>
              <span class="citation-path">
                <template v-if="item.titlePath">{{ item.titlePath }}</template>
                <template v-else>切片 #{{ item.seq }}</template>
                <template v-if="item.pageNo"> · 第 {{ item.pageNo }} 页</template>
              </span>
            </div>
            <div class="score-wrap">
              <div class="score-bar">
                <div class="score-fill" :style="{ width: `${Math.round(item.score * 100)}%` }" />
              </div>
              <span class="score-text">{{ item.score.toFixed(3) }}</span>
            </div>
          </div>
          <p class="citation-content">{{ item.content }}</p>
          <div class="citation-foot">
            <span class="muted">{{ item.chunkLevel }} 切片 · {{ item.documentExt }}</span>
            <el-button
              v-if="item.parentContent"
              text
              type="primary"
              size="small"
              @click="toggleParent(item.chunkId)"
            >
              {{ expandedParents.includes(item.chunkId) ? '收起上下文' : '查看父切片上下文' }}
            </el-button>
          </div>
          <div v-if="expandedParents.includes(item.chunkId) && item.parentContent" class="parent-content">
            <span class="parent-label">父切片</span>
            <pre>{{ item.parentContent }}</pre>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="empty-panel">
      <el-icon class="empty-icon"><DataAnalysis /></el-icon>
      <h4>输入问题开始检索</h4>
      <p>检索只读取已就绪文档的 ACTIVE 切片，父子切分会使用子切片召回并附带父级上下文；Elasticsearch 不可用时自动使用本地索引。</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Search } from '@element-plus/icons-vue'
import { configApi, retrievalApi } from '../../api'
import { indexStrategyMap } from '../../utils/status'

const props = defineProps({
  series: { type: Object, required: true }
})

const samples = [
  '绩效工资 A 档发多少？',
  '年假如何计算？',
  '报销流程需要哪些材料？'
]

const loading = ref(false)
const response = ref(null)
const expandedParents = ref([])
const config = ref(null)

const form = reactive({
  query: '',
  topK: 5,
  threshold: 0.3,
  hybrid: true,
  rerank: true
})

const indexStrategy = computed(() => {
  const strategy = config.value?.indexStrategy || props.series.indexStrategy || 'HYBRID'
  return indexStrategyMap[strategy] || strategy
})

async function loadConfig() {
  try {
    config.value = await configApi.current(props.series.id)
    const retrieval = config.value?.retrieval
    if (retrieval) {
      form.topK = Math.min(20, retrieval.rerankTopN || 5)
      form.threshold = retrieval.similarityThreshold ?? 0.3
      form.hybrid = retrieval.hybridEnabled !== false
      form.rerank = retrieval.rerankEnabled !== false
    }
  } catch (error) {
    config.value = null
  }
}

function useSample(value) {
  form.query = value
  search()
}

async function search() {
  if (!form.query.trim()) {
    ElMessage.warning('请输入检索问题')
    return
  }
  loading.value = true
  expandedParents.value = []
  try {
    response.value = await retrievalApi.test(props.series.id, {
      query: form.query.trim(),
      topK: form.topK,
      threshold: form.threshold,
      hybrid: form.hybrid,
      rerank: form.rerank,
      generate: false
    })
  } finally {
    loading.value = false
  }
}

function toggleParent(chunkId) {
  const index = expandedParents.value.indexOf(chunkId)
  if (index >= 0) {
    expandedParents.value.splice(index, 1)
  } else {
    expandedParents.value.push(chunkId)
  }
}

watch(() => props.series.id, () => {
  response.value = null
  loadConfig()
})

onMounted(loadConfig)
</script>

<style scoped>
.retrieval-tab {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.search-panel {
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  padding: 20px;
  background: var(--kb-surface);
}

.search-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.search-config {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.config-text {
  font-size: 11px;
  color: var(--kb-muted);
}

.query-row {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.query-row :deep(.el-textarea) {
  flex: 1;
}

.search-btn {
  width: 100px;
  height: auto;
}

.options-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 12px;
}

.option-item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--kb-ink-soft);
}

.quick-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--kb-line);
}

.quick-label {
  font-size: 11px;
  color: var(--kb-muted);
}

.quick-chip {
  border: 1px solid var(--kb-line);
  background: var(--kb-surface-2);
  color: var(--kb-primary);
  font-size: 11px;
  padding: 5px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.quick-chip:hover {
  background: var(--kb-primary-tint);
  border-color: #bfd5cb;
}

.result-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.result-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 16px 18px;
  border-radius: 9px;
  background: #1a211c;
  color: #ece9df;
}

.summary-item {
  flex: 1;
  min-width: 110px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 10px;
  border-right: 1px solid rgba(255, 255, 255, 0.09);
}

.summary-item:last-child {
  border-right: none;
}

.summary-item span {
  font-size: 10px;
  letter-spacing: 0.4px;
  color: rgba(236, 233, 223, 0.45);
}

.summary-item strong {
  font-size: 14px;
  font-weight: 500;
}

.citation-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.citation-card {
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  padding: 16px;
  background: var(--kb-surface);
  transition: border-color 0.16s ease;
}

.citation-card:hover {
  border-color: var(--kb-line-strong);
}

.citation-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.citation-rank {
  width: 26px;
  height: 26px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.citation-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.citation-title strong {
  font-size: 13px;
  font-weight: 600;
}

.citation-path {
  font-size: 11px;
  color: var(--kb-muted);
}

.score-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.score-bar {
  width: 80px;
  height: 4px;
  border-radius: 4px;
  background: var(--kb-surface-3);
  overflow: hidden;
}

.score-fill {
  height: 100%;
  border-radius: 4px;
  background: var(--kb-primary);
}

.score-text {
  font-size: 11px;
  color: var(--kb-primary);
  font-weight: 600;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.citation-content {
  margin: 12px 0 10px;
  font-size: 12px;
  line-height: 1.85;
  color: var(--kb-ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
}

.citation-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 10px;
}

.parent-content {
  margin-top: 10px;
  padding: 12px;
  border-radius: 6px;
  background: var(--kb-surface-2);
  border: 1px dashed var(--kb-line-strong);
}

.parent-label {
  font-size: 10px;
  color: var(--kb-accent);
  letter-spacing: 1px;
}

.parent-content pre {
  margin: 7px 0 0;
  font-family: inherit;
  font-size: 11px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--kb-ink-soft);
  max-height: 260px;
  overflow: auto;
}

.empty-panel {
  padding: 54px 20px;
  text-align: center;
  border: 1px dashed var(--kb-line-strong);
  border-radius: 9px;
  background: var(--kb-surface-2);
}

.empty-icon {
  font-size: 38px;
  color: #a8c6bb;
}

.empty-panel h4 {
  margin: 12px 0 6px;
  font-size: 14px;
}

.empty-panel p {
  margin: 0;
  font-size: 11px;
  color: var(--kb-muted);
}

@media (max-width: 900px) {
  .query-row {
    flex-direction: column;
  }

  .search-btn {
    width: 100%;
  }

  .summary-item {
    flex: 1 0 40%;
    border-right: none;
    padding: 6px 8px;
  }
}
</style>

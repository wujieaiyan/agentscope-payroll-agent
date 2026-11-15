<template>
  <div class="page-shell wizard-page kb-app">
    <div class="page-head">
      <div>
        <h1 class="page-title">新建知识库</h1>
        <p class="page-subtitle">按步骤完成基础信息、解析引擎、分块策略与高级检索配置。</p>
      </div>
      <el-button :icon="ArrowLeft" @click="router.back()">返回列表</el-button>
    </div>

    <div class="wizard-shell surface">
      <div class="wizard-head">
        <el-steps :active="step" finish-status="success" align-center>
          <el-step title="基础信息" description="主题与索引策略" />
          <el-step title="解析引擎" description="文档类型与解析方式" />
          <el-step title="分块设置" description="切片策略与参数" />
          <el-step title="高级设置" description="检索、精排与引用" />
        </el-steps>
      </div>

      <div class="wizard-body">
        <!-- Step 1 -->
        <div v-if="step === 0" class="step-panel">
          <div class="panel-heading">
            <h2>基础信息</h2>
            <p>知识库将展示在卡片列表中，名称、部门与简介会作为主要识别信息。</p>
          </div>

          <el-form label-position="top" class="form-grid">
            <el-form-item label="知识库主题" required class="span-2">
              <el-input v-model="form.name" maxlength="50" show-word-limit placeholder="例如：薪酬绩效制度知识库" />
            </el-form-item>
            <el-form-item label="所属部门" required>
              <el-select v-model="form.deptId" placeholder="请选择部门" style="width: 100%">
                <el-option v-for="dept in departments" :key="dept.id" :label="dept.name" :value="dept.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="索引策略">
              <el-select v-model="form.indexStrategy" style="width: 100%">
                <el-option label="混合检索（向量 + BM25，推荐）" value="HYBRID" />
                <el-option label="向量检索（RAG）" value="VECTOR" />
                <el-option label="图谱增强（暂未开放）" value="GRAPH" disabled />
              </el-select>
            </el-form-item>
            <el-form-item label="知识库简介" class="span-2">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="4"
                maxlength="200"
                show-word-limit
                placeholder="简要说明知识库涵盖的制度、业务范围或使用场景"
              />
            </el-form-item>
          </el-form>

          <div class="strategy-cards">
            <div
              v-for="option in indexStrategies"
              :key="option.value"
              class="strategy-card"
              :class="{ selected: form.indexStrategy === option.value, disabled: option.disabled }"
              @click="!option.disabled && (form.indexStrategy = option.value)"
            >
              <div class="strategy-icon">
                <el-icon><component :is="option.icon" /></el-icon>
              </div>
              <div>
                <strong>{{ option.label }}</strong>
                <p>{{ option.description }}</p>
              </div>
              <el-icon v-if="form.indexStrategy === option.value" class="check"><CircleCheckFilled /></el-icon>
            </div>
          </div>
        </div>

        <!-- Step 2 -->
        <div v-else-if="step === 1" class="step-panel">
          <div class="panel-heading">
            <h2>解析引擎配置</h2>
            <p>选择需要支持的文档类型，并为每种类型指定文字提取或 OCR 解析策略。</p>
          </div>

          <div class="doc-type-grid">
            <div
              v-for="type in docTypes"
              :key="type.key"
              class="doc-type-card"
              :class="{ selected: form.parse.docTypes.includes(type.key) }"
              @click="toggleDocType(type.key)"
            >
              <div class="type-icon" :class="type.key.toLowerCase()">
                <el-icon><component :is="type.icon" /></el-icon>
              </div>
              <div class="type-info">
                <strong>{{ type.label }}</strong>
                <span>{{ type.hint }}</span>
              </div>
              <el-icon v-if="form.parse.docTypes.includes(type.key)" class="check"><CircleCheckFilled /></el-icon>
            </div>
          </div>

          <div class="sub-card">
            <div class="sub-card-head">
              <div>
                <h3>解析方式</h3>
                <p>文字提取适用于可复制文本；OCR 适用于图片或扫描件。</p>
              </div>
              <el-tag type="warning" effect="plain" size="small">演示环境未接入 OCR</el-tag>
            </div>
            <el-table :data="docTypeRows" size="small">
              <el-table-column prop="label" label="文档类型" width="160" />
              <el-table-column label="解析策略">
                <template #default="{ row }">
                  <el-select
                    v-model="form.parse.perType[row.key].strategy"
                    size="small"
                    style="width: 220px"
                    @change="form.parse.perType[row.key].enabled = true"
                  >
                    <el-option label="文字提取" value="NATIVE" />
                    <el-option label="自动判断（PDF）" value="AUTO" :disabled="row.key !== 'PDF'" />
                    <el-option label="OCR 识别（未接入）" value="OCR" disabled />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="说明" min-width="220">
                <template #default="{ row }">
                  <span class="muted small">{{ parseHint(row.key) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="sub-grid">
            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>解析选项</h3>
                  <p>控制表格、页眉页脚与图片文字的提取行为。</p>
                </div>
              </div>
              <div class="switch-list">
                <label>
                  <el-switch v-model="form.parse.options.tableStructure" />
                  <span><strong>保留表格结构</strong><small>将表格按行转换为文本</small></span>
                </label>
                <label>
                  <el-switch v-model="form.parse.options.filterHeaderFooter" />
                  <span><strong>过滤页眉页脚</strong><small>移除 PDF 中重复出现的页眉页脚与页码</small></span>
                </label>
                <label>
                  <el-switch v-model="form.parse.options.ocrEmbeddedImages" />
                  <span><strong>提取文档内图片文字</strong><small>需要 OCR 引擎支持，演示环境仅保留配置</small></span>
                </label>
              </div>
            </div>

            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>OCR 参数</h3>
                  <p>演示环境不会调用 OCR，仅作为配置快照保存。</p>
                </div>
              </div>
              <el-form label-position="top">
                <el-form-item label="识别语言">
                  <el-checkbox-group v-model="form.parse.ocr.languages">
                    <el-checkbox value="zh">简体中文</el-checkbox>
                    <el-checkbox value="en">英文</el-checkbox>
                  </el-checkbox-group>
                </el-form-item>
                <el-form-item label="最低置信度">
                  <el-slider v-model="form.parse.ocr.minConfidence" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
                <el-form-item label="保留版面布局">
                  <el-switch v-model="form.parse.ocr.keepLayout" />
                </el-form-item>
              </el-form>
            </div>
          </div>
        </div>

        <!-- Step 3 -->
        <div v-else-if="step === 2" class="step-panel">
          <div class="panel-heading">
            <h2>分块设置</h2>
            <p>切片质量直接影响召回效果。默认使用规则切分，也可以选择更贴合文档结构的策略。</p>
          </div>

          <div class="chunk-strategies">
            <div
              v-for="option in chunkStrategies"
              :key="option.value"
              class="chunk-card"
              :class="{ selected: form.chunk.strategy === option.value }"
              @click="form.chunk.strategy = option.value"
            >
              <div class="chunk-card-head">
                <strong>{{ option.label }}</strong>
                <el-icon v-if="form.chunk.strategy === option.value" class="check"><CircleCheckFilled /></el-icon>
              </div>
              <p>{{ option.description }}</p>
              <span class="chunk-scene">{{ option.scene }}</span>
            </div>
          </div>

          <div class="sub-grid">
            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>通用参数</h3>
                  <p>所有策略共享的长度与重叠控制。</p>
                </div>
              </div>
              <el-form label-position="top">
                <el-form-item label="最大切片长度（tokens）">
                  <el-input-number v-model="form.chunk.common.maxTokens" :min="64" :max="4096" :step="64" />
                </el-form-item>
                <el-form-item label="相邻切片重叠（tokens）">
                  <el-input-number
                    v-model="form.chunk.common.overlapTokens"
                    :min="0"
                    :max="Math.floor(form.chunk.common.maxTokens / 2)"
                    :step="16"
                  />
                </el-form-item>
                <el-form-item label="最小切片长度（tokens）">
                  <el-input-number
                    v-model="form.chunk.common.minTokens"
                    :min="0"
                    :max="form.chunk.common.maxTokens"
                    :step="10"
                  />
                </el-form-item>
                <el-form-item label="保持表格完整">
                  <el-switch v-model="form.chunk.common.keepTableWhole" />
                </el-form-item>
              </el-form>
            </div>

            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>{{ chunkStrategyTitle }}参数</h3>
                  <p>{{ chunkStrategyHint }}</p>
                </div>
              </div>

              <el-form v-if="form.chunk.strategy === 'BY_LINE'" label-position="top">
                <el-form-item label="每行切片上限（tokens）">
                  <el-input-number v-model="form.chunk.byLine.maxTokens" :min="64" :max="4096" :step="64" />
                </el-form-item>
                <el-form-item label="合并过短的行">
                  <el-switch v-model="form.chunk.byLine.mergeShortLines" />
                </el-form-item>
                <el-form-item label="丢弃空行">
                  <el-switch v-model="form.chunk.byLine.dropEmpty" />
                </el-form-item>
              </el-form>

              <el-form v-else-if="form.chunk.strategy === 'PARENT_CHILD'" label-position="top">
                <el-form-item label="父切片长度（tokens）">
                  <el-input-number v-model="form.chunk.parentChild.parentTokens" :min="256" :max="4096" :step="128" />
                </el-form-item>
                <el-form-item label="子切片长度（tokens）">
                  <el-input-number v-model="form.chunk.parentChild.childTokens" :min="64" :max="2048" :step="64" />
                </el-form-item>
                <el-form-item label="子切片重叠（tokens）">
                  <el-input-number v-model="form.chunk.parentChild.childOverlap" :min="0" :max="256" :step="16" />
                </el-form-item>
                <el-form-item label="每个父切片的子切片上限">
                  <el-input-number v-model="form.chunk.parentChild.maxChildrenPerParent" :min="1" :max="32" />
                </el-form-item>
              </el-form>

              <el-form v-else-if="form.chunk.strategy === 'TITLE_ENHANCED'" label-position="top">
                <el-form-item label="标题模板">
                  <el-input v-model="form.chunk.titleEnhanced.template" placeholder="{doc} > {h1} > {h2}" />
                </el-form-item>
                <el-form-item label="保留标题路径">
                  <el-switch v-model="form.chunk.titleEnhanced.keepTitlePath" />
                </el-form-item>
                <div class="form-tip">
                  支持变量：<code>{doc}</code> 文档名、<code>{h1}</code> 一级标题、<code>{h2}</code> 二级标题。
                </div>
              </el-form>

              <el-form v-else-if="form.chunk.strategy === 'CUSTOM'" label-position="top">
                <el-form-item label="自定义分隔符（正则）">
                  <el-input v-model="form.chunk.custom.separator" placeholder="\\n\\n" />
                </el-form-item>
                <el-form-item label="保留分隔符">
                  <el-switch v-model="form.chunk.custom.keepSeparator" />
                </el-form-item>
                <div class="form-tip">示例：<code>\n\n</code> 按空行切分，<code>第.+?条</code> 按条款切分。</div>
              </el-form>

              <div v-else class="preview-note">
                <el-icon><InfoFilled /></el-icon>
                <p>默认切分基于段落与句子边界，按最大长度装箱，并保留指定重叠长度，适用于大多数制度、手册类文档。</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Step 4 -->
        <div v-else class="step-panel">
          <div class="panel-heading">
            <h2>高级设置</h2>
            <p>配置召回、精排、相似度阈值与引用溯源策略，可在知识库创建后继续调整并生成新版本。</p>
          </div>

          <div class="advanced-grid">
            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>混合检索</h3>
                  <p>向量语义召回与 BM25 关键词召回加权融合。</p>
                </div>
                <el-switch v-model="form.retrieval.hybridEnabled" />
              </div>
              <el-form label-position="top">
                <el-form-item :label="`向量权重：${Math.round(form.retrieval.vectorWeight * 100)}%`">
                  <el-slider
                    v-model="form.retrieval.vectorWeight"
                    :min="0"
                    :max="1"
                    :step="0.05"
                    :disabled="!form.retrieval.hybridEnabled"
                  />
                </el-form-item>
                <el-form-item label="TopK 召回数量">
                  <el-input-number v-model="form.retrieval.recallTopK" :min="1" :max="100" />
                </el-form-item>
              </el-form>
            </div>

            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>精排与阈值</h3>
                  <p>对召回结果二次排序，并过滤低相关片段。</p>
                </div>
                <el-switch v-model="form.retrieval.rerankEnabled" />
              </div>
              <el-form label-position="top">
                <el-form-item label="精排返回数量">
                  <el-input-number
                    v-model="form.retrieval.rerankTopN"
                    :min="1"
                    :max="Math.min(20, form.retrieval.recallTopK)"
                  />
                </el-form-item>
                <el-form-item :label="`相似度阈值：${form.retrieval.similarityThreshold.toFixed(2)}`">
                  <el-slider v-model="form.retrieval.similarityThreshold" :min="0" :max="1" :step="0.01" />
                </el-form-item>
              </el-form>
            </div>

            <div class="sub-card">
              <div class="sub-card-head">
                <div>
                  <h3>引用溯源</h3>
                  <p>回答中附带文档、页码与切片位置，便于核验。</p>
                </div>
                <el-switch v-model="form.retrieval.citation.enabled" />
              </div>
              <el-form label-position="top">
                <el-form-item label="引用粒度">
                  <el-select v-model="form.retrieval.citation.granularity" style="width: 100%">
                    <el-option label="段落级" value="PARAGRAPH" />
                    <el-option label="句子级" value="SENTENCE" />
                    <el-option label="切片级" value="CHUNK" />
                  </el-select>
                </el-form-item>
                <el-form-item label="无答案策略">
                  <el-select v-model="form.retrieval.noAnswerStrategy" style="width: 100%">
                    <el-option label="返回空结果" value="EMPTY" />
                    <el-option label="提示未找到" value="SUGGEST" />
                  </el-select>
                </el-form-item>
              </el-form>
            </div>

            <div class="summary-card">
              <h3>配置摘要</h3>
              <div class="summary-row"><span>知识库</span><strong>{{ form.name || '未命名' }}</strong></div>
              <div class="summary-row"><span>部门</span><strong>{{ deptName || '-' }}</strong></div>
              <div class="summary-row"><span>索引策略</span><strong>{{ indexStrategyMap[form.indexStrategy] }}</strong></div>
              <div class="summary-row">
                <span>文档类型</span>
                <strong>{{ form.parse.docTypes.map((t) => docLabel(t)).join(' / ') }}</strong>
              </div>
              <div class="summary-row"><span>切片策略</span><strong>{{ chunkStrategyMap[form.chunk.strategy] }}</strong></div>
              <div class="summary-row">
                <span>检索</span>
                <strong>
                  {{ form.retrieval.hybridEnabled ? '混合检索' : '向量检索' }} · Top{{ form.retrieval.recallTopK }}
                </strong>
              </div>
              <div class="summary-row">
                <span>精排</span>
                <strong>{{ form.retrieval.rerankEnabled ? `开启 · Top${form.retrieval.rerankTopN}` : '关闭' }}</strong>
              </div>
              <div class="summary-row">
                <span>引用溯源</span>
                <strong>{{ form.retrieval.citation.enabled ? '开启' : '关闭' }}</strong>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="wizard-foot">
        <span class="step-hint">步骤 {{ step + 1 }} / 4</span>
        <div class="foot-actions">
          <el-button v-if="step > 0" @click="step--">上一步</el-button>
          <el-button v-if="step < 3" type="primary" @click="next">下一步</el-button>
          <el-button v-else type="primary" :loading="submitting" @click="submit">创建知识库</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  CircleCheckFilled,
  Document,
  Files,
  Grid,
  DataLine,
  Cpu,
  Reading,
  List,
  Connection,
  InfoFilled
} from '@element-plus/icons-vue'
import { orgApi, seriesApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { chunkStrategyMap, indexStrategyMap } from '../utils/status'

const router = useRouter()
const auth = useAuthStore()

const step = ref(0)
const submitting = ref(false)
const departments = ref([])

const docTypes = [
  { key: 'PDF', label: 'PDF', hint: '制度、报告、扫描件', icon: Document },
  { key: 'DOCX', label: 'WORD', hint: 'Word 文档（docx）', icon: Files },
  { key: 'XLSX', label: 'EXCEL', hint: '表格（xlsx / xls）', icon: Grid },
  { key: 'MD', label: 'MARKDOWN', hint: 'Markdown 知识文档', icon: Reading }
]

const indexStrategies = [
  {
    value: 'HYBRID',
    label: '混合检索',
    description: '向量语义 + BM25 关键词融合，兼顾语义与精确匹配。',
    icon: Connection
  },
  {
    value: 'VECTOR',
    label: '向量检索（RAG）',
    description: '基于文本向量的语义召回，适合自然语言提问。',
    icon: DataLine
  },
  {
    value: 'GRAPH',
    label: '图谱增强',
    description: '实体与关系增强检索，当前版本暂未开放。',
    icon: Cpu,
    disabled: true
  }
]

const chunkStrategies = [
  { value: 'DEFAULT', label: '默认切分', description: '按段落与句子边界装箱，保留重叠。', scene: '通用制度、手册' },
  { value: 'BY_LINE', label: '按行切分', description: '以行为单位切片，可合并短行。', scene: '表格、清单、日志' },
  { value: 'PARENT_CHILD', label: '父子切分', description: '父块提供上下文，子块用于精确召回。', scene: '长文档、章节问答' },
  { value: 'TITLE_ENHANCED', label: '标题增强', description: '按标题层级组织切片，并拼接标题路径。', scene: '结构化制度、说明书' },
  { value: 'CUSTOM', label: '自定义切分', description: '使用正则分隔符按业务规则切分。', scene: '条款、问答、模板' }
]

const form = reactive({
  name: '',
  deptId: auth.user?.deptId || null,
  description: '',
  indexStrategy: 'HYBRID',
  draft: false,
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
})

const docTypeRows = computed(() =>
  docTypes.filter((type) => form.parse.docTypes.includes(type.key)).map((type) => ({ ...type }))
)

const deptName = computed(() => departments.value.find((d) => d.id === form.deptId)?.name || '')
const chunkStrategyTitle = computed(() => chunkStrategyMap[form.chunk.strategy] || '切片')
const chunkStrategyHint = computed(() => {
  const map = {
    DEFAULT: '默认策略不需要额外参数，使用上方通用参数。',
    BY_LINE: '每一行生成一个切片，适合表格类文本。',
    PARENT_CHILD: '父切片保存上下文，子切片参与召回。',
    TITLE_ENHANCED: '按标题层级分段，并在内容前拼接标题路径。',
    CUSTOM: '使用正则表达式按业务分隔符切分。'
  }
  return map[form.chunk.strategy]
})

function docLabel(key) {
  return docTypes.find((t) => t.key === key)?.label || key
}

function parseHint(key) {
  if (key === 'PDF') {
    return '自动判断：优先提取文本，未识别到文本时提示扫描件（演示环境无 OCR）。'
  }
  if (key === 'XLSX') return '按工作表与行提取单元格文本，保留工作表名称。'
  if (key === 'DOCX') return '提取段落、标题层级与表格内容。'
  return '解析 Markdown 标题、段落、列表与代码块。'
}

function toggleDocType(key) {
  const index = form.parse.docTypes.indexOf(key)
  if (index >= 0) {
    if (form.parse.docTypes.length === 1) {
      ElMessage.warning('至少保留一种文档类型')
      return
    }
    form.parse.docTypes.splice(index, 1)
    form.parse.perType[key].enabled = false
  } else {
    form.parse.docTypes.push(key)
    form.parse.perType[key].enabled = true
  }
}

function validateStep() {
  if (step.value === 0) {
    if (!form.name.trim() || form.name.trim().length < 2) {
      ElMessage.warning('请输入 2-50 个字符的知识库名称')
      return false
    }
    if (!form.deptId) {
      ElMessage.warning('请选择所属部门')
      return false
    }
    return true
  }
  if (step.value === 1) {
    if (!form.parse.docTypes.length) {
      ElMessage.warning('请至少选择一种文档类型')
      return false
    }
    return true
  }
  if (step.value === 2) {
    const common = form.chunk.common
    if (common.maxTokens < 64 || common.maxTokens > 4096) {
      ElMessage.warning('最大切片长度需在 64-4096 tokens 之间')
      return false
    }
    if (common.overlapTokens > Math.floor(common.maxTokens / 2)) {
      ElMessage.warning('重叠长度不能超过最大长度的一半')
      return false
    }
    if (common.minTokens > common.maxTokens) {
      ElMessage.warning('最小长度不能大于最大长度')
      return false
    }
    if (form.chunk.strategy === 'PARENT_CHILD') {
      const pc = form.chunk.parentChild
      if (pc.childTokens >= pc.parentTokens) {
        ElMessage.warning('子切片长度必须小于父切片长度')
        return false
      }
      if (pc.childOverlap >= Math.floor(pc.childTokens / 2)) {
        ElMessage.warning('子切片重叠需小于子切片长度的一半')
        return false
      }
    }
    if (form.chunk.strategy === 'CUSTOM') {
      try {
        // eslint-disable-next-line no-new
        new RegExp(form.chunk.custom.separator)
      } catch (error) {
        ElMessage.warning('自定义分隔符不是合法的正则表达式')
        return false
      }
    }
    return true
  }
  if (form.retrieval.rerankTopN > form.retrieval.recallTopK) {
    ElMessage.warning('精排数量不能大于召回数量')
    return false
  }
  return true
}

function next() {
  if (!validateStep()) return
  step.value += 1
}

async function submit() {
  if (!validateStep()) return
  if (!form.name.trim() || !form.deptId) {
    step.value = 0
    ElMessage.warning('请先完善基础信息')
    return
  }
  submitting.value = true
  try {
    const payload = {
      name: form.name.trim(),
      deptId: form.deptId,
      description: form.description,
      indexStrategy: form.indexStrategy === 'GRAPH' ? 'HYBRID' : form.indexStrategy,
      draft: false,
      config: {
        parse: form.parse,
        chunk: form.chunk,
        retrieval: form.retrieval
      }
    }
    const created = await seriesApi.create(payload)
    ElMessage.success('知识库创建成功')
    router.replace(`/kb/${created.id}`)
  } catch (error) {
    // message handled by http interceptor
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    departments.value = await orgApi.departments()
    if (!form.deptId && departments.value.length) {
      form.deptId = departments.value[0].id
    }
  } catch (error) {
    departments.value = []
  }
})
</script>

<style scoped>
.wizard-page {
  max-width: 1180px;
}

.wizard-head {
  padding: 28px 48px 24px;
  border-bottom: 1px solid var(--kb-line);
  background: var(--kb-surface-2);
  border-radius: var(--kb-radius) var(--kb-radius) 0 0;
}

.wizard-body {
  padding: 30px 48px 26px;
  min-height: 520px;
}

.panel-heading {
  margin-bottom: 24px;
}

.panel-heading h2 {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.3px;
}

.panel-heading p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--kb-muted);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}

.span-2 {
  grid-column: span 2;
}

.strategy-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 10px;
}

.strategy-card {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 16px;
  border-radius: 9px;
  border: 1px solid var(--kb-line);
  background: var(--kb-surface-2);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.strategy-card:hover {
  border-color: var(--kb-line-strong);
}

.strategy-card.selected {
  border-color: var(--kb-primary);
  background: var(--kb-primary-tint);
  box-shadow: inset 0 0 0 1px var(--kb-primary);
}

.strategy-card.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.strategy-icon {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-size: 18px;
  flex-shrink: 0;
}

.strategy-card strong {
  font-size: 13px;
  font-weight: 600;
}

.strategy-card p {
  margin: 5px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--kb-muted);
}

.check {
  position: absolute;
  right: 12px;
  top: 12px;
  color: var(--kb-primary);
  font-size: 17px;
}

.doc-type-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.doc-type-card {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  background: var(--kb-surface-2);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.doc-type-card:hover {
  border-color: var(--kb-line-strong);
}

.doc-type-card.selected {
  border-color: var(--kb-primary);
  background: var(--kb-primary-tint);
  box-shadow: inset 0 0 0 1px var(--kb-primary);
}

.type-icon {
  width: 36px;
  height: 36px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  background: var(--kb-surface-3);
  color: var(--kb-muted);
}

.type-icon.docx {
  background: #edf1f5;
  color: #496785;
}

.type-icon.xlsx {
  background: #eaf2ed;
  color: #3d7357;
}

.type-icon.md {
  background: #f1eef5;
  color: #6d6088;
}

.type-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.type-info strong {
  font-size: 12px;
}

.type-info span {
  font-size: 10px;
  color: var(--kb-muted);
}

.sub-card {
  border: 1px solid var(--kb-line);
  border-radius: 9px;
  padding: 18px;
  background: var(--kb-surface);
}

.sub-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.sub-card-head h3 {
  margin: 0 0 4px;
  font-size: 14px;
}

.sub-card-head p {
  margin: 0;
  font-size: 11px;
  color: var(--kb-muted);
}

.sub-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.switch-list {
  display: grid;
  gap: 14px;
}

.switch-list label {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
}

.switch-list span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.switch-list strong {
  font-size: 12px;
}

.switch-list small {
  font-size: 10px;
  color: var(--kb-muted);
}

.chunk-strategies {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 18px;
}

.chunk-card {
  position: relative;
  padding: 14px;
  border-radius: 9px;
  border: 1px solid var(--kb-line);
  background: var(--kb-surface-2);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.chunk-card:hover {
  border-color: var(--kb-line-strong);
}

.chunk-card.selected {
  border-color: var(--kb-primary);
  background: var(--kb-primary-tint);
  box-shadow: inset 0 0 0 1px var(--kb-primary);
}

.chunk-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chunk-card-head strong {
  font-size: 12px;
}

.chunk-card p {
  margin: 8px 0;
  font-size: 10px;
  line-height: 1.7;
  color: var(--kb-muted);
  min-height: 34px;
}

.chunk-scene {
  font-size: 9px;
  color: var(--kb-primary);
  background: var(--kb-primary-soft);
  padding: 2px 7px;
  border-radius: 3px;
}

.preview-note {
  display: flex;
  gap: 10px;
  padding: 14px;
  border-radius: 7px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-line-soft);
  color: var(--kb-muted);
  font-size: 11px;
  line-height: 1.8;
}

.preview-note p {
  margin: 0;
}

.form-tip {
  font-size: 11px;
  color: var(--kb-muted);
  line-height: 1.8;
}

.form-tip code {
  background: var(--kb-surface-3);
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--kb-ink-soft);
}

.small {
  font-size: 11px;
}

.advanced-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.summary-card {
  grid-column: span 3;
  border-radius: 9px;
  padding: 20px 22px;
  background: #1a211c;
  color: #ece9df;
}

.summary-card h3 {
  margin: 0 0 14px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 2px;
  text-transform: uppercase;
  color: #c8a877;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  font-size: 12px;
}

.summary-row:last-child {
  border-bottom: none;
}

.summary-row span {
  color: rgba(236, 233, 223, 0.5);
}

.summary-row strong {
  font-weight: 500;
  text-align: right;
}

.wizard-foot {
  position: sticky;
  bottom: 0;
  z-index: 6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 48px;
  border-top: 1px solid var(--kb-line);
  background: var(--kb-surface-2);
  border-radius: 0 0 var(--kb-radius) var(--kb-radius);
  box-shadow: 0 -6px 16px rgba(31, 36, 31, 0.04);
}

.step-hint {
  font-size: 11px;
  color: var(--kb-muted);
  letter-spacing: 1px;
}

.foot-actions {
  display: flex;
  gap: 10px;
}

@media (max-width: 1080px) {
  .strategy-cards,
  .doc-type-grid,
  .chunk-strategies,
  .advanced-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary-card {
    grid-column: span 2;
  }
}

@media (max-width: 720px) {
  .wizard-body,
  .wizard-head,
  .wizard-foot {
    padding-left: 20px;
    padding-right: 20px;
  }

  .form-grid,
  .sub-grid,
  .strategy-cards,
  .doc-type-grid,
  .chunk-strategies,
  .advanced-grid {
    grid-template-columns: 1fr;
  }

  .span-2,
  .summary-card {
    grid-column: span 1;
  }
}
</style>

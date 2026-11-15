<template>
  <div class="directories-tab">
    <div class="dir-toolbar">
      <div>
        <h3 class="section-title">目录层级</h3>
        <p class="section-subtitle">目录用于组织文档，最多支持 {{ maxDepth }} 层；根目录不可删除或重命名。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="Refresh" circle plain :loading="loading" @click="loadTree" />
        <el-button v-if="canMaintain" type="primary" :icon="Plus" @click="openCreate(null)">新建根目录</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="tree"
      row-key="id"
      default-expand-all
      :tree-props="{ children: 'children' }"
      size="default"
      empty-text="暂无目录"
    >
      <el-table-column label="目录名称" min-width="300">
        <template #default="{ row }">
          <div class="dir-name">
            <el-icon class="dir-icon"><Folder v-if="!row.root" /><FolderOpened v-else /></el-icon>
            <span>{{ row.name }}</span>
            <el-tag v-if="row.root" size="small" effect="plain" type="info" round>根目录</el-tag>
          </div>
          <div class="dir-path">{{ row.path }}</div>
        </template>
      </el-table-column>
      <el-table-column label="层级" width="90" align="center">
        <template #default="{ row }">{{ row.depth + 1 }}</template>
      </el-table-column>
      <el-table-column label="文档" width="100" align="center">
        <template #default="{ row }">
          <el-button text type="primary" size="small" @click="$emit('jump-documents')">
            {{ row.docCount }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="子目录" width="90" align="center">
        <template #default="{ row }">{{ row.children?.length || 0 }}</template>
      </el-table-column>
      <el-table-column v-if="canMaintain" label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button text type="primary" size="small" @click="openCreate(row)">新建子目录</el-button>
            <template v-if="!row.root">
              <el-button text size="small" @click="openRename(row)">重命名</el-button>
              <el-button text size="small" @click="openMove(row)">移动</el-button>
              <el-button text type="danger" size="small" @click="openDelete(row)">删除</el-button>
            </template>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" :title="createParent ? `在「${createParent.name}」下新建目录` : '新建根目录'" width="440px">
      <el-form label-position="top">
        <el-form-item label="目录名称" required>
          <el-input v-model="createName" maxlength="50" show-word-limit placeholder="例如：薪酬制度 / 2025 年" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renameVisible" title="重命名目录" width="440px">
      <el-form label-position="top">
        <el-form-item label="目录名称" required>
          <el-input v-model="renameName" maxlength="50" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moveVisible" title="移动目录" width="460px">
      <el-form label-position="top">
        <el-form-item label="目标上级目录" required>
          <el-tree-select
            v-model="moveTargetId"
            :data="moveOptions"
            placeholder="选择目标目录"
            check-strictly
            :render-after-expand="false"
            style="width: 100%"
          />
        </el-form-item>
        <div class="form-tip">移动后子目录与文档会保留在当前目录下，跟随目录一起移动。</div>
      </el-form>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitMove">移动</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deleteVisible" :title="`删除目录「${deleteNode?.name || ''}」`" width="500px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="目录非空，删除前需要选择处理方式"
        description="移动到上级会保留文档，仅删除当前目录；级联删除会同时删除所有子目录与文档（文档进入已删除状态）。"
      />
      <el-form label-position="top" class="delete-form">
        <el-form-item label="处理方式">
          <el-radio-group v-model="deleteStrategy">
            <el-radio value="MOVE_TO_PARENT" :disabled="hasChildren">文档移动到上级目录</el-radio>
            <el-radio value="CASCADE">级联删除子目录与文档</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="deleteStrategy === 'CASCADE'" label="输入目录名称确认" required>
          <el-input v-model="deleteConfirmName" :placeholder="deleteNode?.name" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="submitDelete">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Folder, FolderOpened, Plus, Refresh } from '@element-plus/icons-vue'
import { directoryApi } from '../../api'

const props = defineProps({
  series: { type: Object, required: true },
  role: { type: String, default: 'READER' }
})

const emit = defineEmits(['changed', 'jump-documents'])

const canMaintain = computed(() => ['EDITOR', 'ADMIN', 'OWNER'].includes(props.role))
const maxDepth = 5

const loading = ref(false)
const saving = ref(false)
const tree = ref([])

const createVisible = ref(false)
const createParent = ref(null)
const createName = ref('')

const renameVisible = ref(false)
const renameNode = ref(null)
const renameName = ref('')

const moveVisible = ref(false)
const moveNode = ref(null)
const moveTargetId = ref(null)

const deleteVisible = ref(false)
const deleteNode = ref(null)
const deleteStrategy = ref('MOVE_TO_PARENT')
const deleteConfirmName = ref('')

const hasChildren = computed(() => Boolean(deleteNode.value?.children?.length))

const moveOptions = computed(() => {
  const mapNode = (node) => ({
    value: node.id,
    label: `${node.depth > 0 ? '　'.repeat(node.depth) : ''}${node.name}`,
    disabled: node.id === moveNode.value?.id,
    children: (node.children || []).map(mapNode)
  })
  return tree.value.map(mapNode)
})

async function loadTree() {
  loading.value = true
  try {
    tree.value = await directoryApi.tree(props.series.id)
  } finally {
    loading.value = false
  }
}

function openCreate(parent) {
  createParent.value = parent
  createName.value = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createName.value.trim()) {
    ElMessage.warning('请输入目录名称')
    return
  }
  saving.value = true
  try {
    await directoryApi.create(props.series.id, {
      parentId: createParent.value?.id || null,
      name: createName.value.trim()
    })
    ElMessage.success('目录已创建')
    createVisible.value = false
    await loadTree()
  } finally {
    saving.value = false
  }
}

function openRename(node) {
  renameNode.value = node
  renameName.value = node.name
  renameVisible.value = true
}

async function submitRename() {
  if (!renameName.value.trim()) {
    ElMessage.warning('请输入目录名称')
    return
  }
  saving.value = true
  try {
    await directoryApi.rename(props.series.id, renameNode.value.id, renameName.value.trim())
    ElMessage.success('目录已重命名')
    renameVisible.value = false
    await loadTree()
  } finally {
    saving.value = false
  }
}

function openMove(node) {
  moveNode.value = node
  moveTargetId.value = node.parentId
  moveVisible.value = true
}

async function submitMove() {
  if (!moveTargetId.value) {
    ElMessage.warning('请选择目标目录')
    return
  }
  if (moveTargetId.value === moveNode.value.parentId) {
    ElMessage.info('目标目录与当前上级目录相同')
    return
  }
  saving.value = true
  try {
    await directoryApi.move(props.series.id, moveNode.value.id, moveTargetId.value)
    ElMessage.success('目录已移动')
    moveVisible.value = false
    await loadTree()
  } finally {
    saving.value = false
  }
}

async function openDelete(node) {
  const empty = !node.children?.length && !node.docCount
  if (empty) {
    try {
      await ElMessageBox.confirm(`确认删除空目录「${node.name}」？`, '删除目录', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await directoryApi.remove(props.series.id, node.id)
      ElMessage.success('目录已删除')
      await loadTree()
      emit('changed')
    } catch (error) {
      // canceled
    }
    return
  }
  deleteNode.value = node
  deleteStrategy.value = node.children?.length ? 'CASCADE' : 'MOVE_TO_PARENT'
  deleteConfirmName.value = ''
  deleteVisible.value = true
}

async function submitDelete() {
  if (deleteStrategy.value === 'CASCADE' && deleteConfirmName.value !== deleteNode.value.name) {
    ElMessage.warning('请输入完整目录名称以确认级联删除')
    return
  }
  saving.value = true
  try {
    await directoryApi.remove(props.series.id, deleteNode.value.id, {
      strategy: deleteStrategy.value,
      confirmName: deleteStrategy.value === 'CASCADE' ? deleteConfirmName.value : undefined
    })
    ElMessage.success('目录已删除')
    deleteVisible.value = false
    await loadTree()
    emit('changed')
  } finally {
    saving.value = false
  }
}

watch(() => props.series.id, loadTree)
onMounted(loadTree)
</script>

<style scoped>
.directories-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dir-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.dir-name {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}

.dir-icon {
  color: var(--kb-accent);
}

.dir-path {
  margin-top: 2px;
  font-size: 10px;
  color: var(--kb-muted);
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}

.form-tip {
  font-size: 11px;
  color: var(--kb-muted);
  line-height: 1.7;
}

.delete-form {
  margin-top: 16px;
}
</style>

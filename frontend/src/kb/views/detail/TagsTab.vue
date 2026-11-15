<template>
  <div class="tags-tab">
    <div class="tag-toolbar">
      <div>
        <h3 class="section-title">标签管理</h3>
        <p class="section-subtitle">标签用于跨目录归类文档，可在知识管理中为文档设置多个标签。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="Refresh" circle plain :loading="loading" @click="loadTags" />
        <el-button v-if="canMaintain" type="primary" :icon="Plus" @click="openCreate">新建标签</el-button>
      </div>
    </div>

    <div v-loading="loading" class="tag-grid">
      <div v-for="tag in tags" :key="tag.id" class="tag-card">
        <div class="tag-main">
          <span class="tag-dot" :style="{ background: tag.color }" />
          <div>
            <strong>{{ tag.name }}</strong>
            <p>{{ tag.docCount }} 个文档使用</p>
          </div>
        </div>
        <div v-if="canMaintain" class="tag-actions">
          <el-button text size="small" @click="openEdit(tag)">编辑</el-button>
          <el-button text type="danger" size="small" @click="removeTag(tag)">删除</el-button>
        </div>
      </div>

      <el-empty v-if="!loading && !tags.length" description="尚未创建标签" :image-size="90">
        <el-button v-if="canMaintain" type="primary" :icon="Plus" @click="openCreate">创建第一个标签</el-button>
      </el-empty>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑标签' : '新建标签'" width="440px">
      <el-form label-position="top">
        <el-form-item label="标签名称" required>
          <el-input v-model="form.name" maxlength="20" show-word-limit placeholder="例如：薪酬 / 考勤 / 绩效" />
        </el-form-item>
        <el-form-item label="标签颜色">
          <div class="color-row">
            <el-color-picker v-model="form.color" />
            <div class="color-presets">
              <button
                v-for="color in presets"
                :key="color"
                type="button"
                class="color-dot"
                :class="{ active: form.color === color }"
                :style="{ background: color }"
                @click="form.color = color"
              />
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { tagApi } from '../../api'

const props = defineProps({
  series: { type: Object, required: true },
  role: { type: String, default: 'READER' }
})

const canMaintain = computed(() => ['EDITOR', 'ADMIN', 'OWNER'].includes(props.role))

const presets = ['#1f6f5c', '#a9773f', '#b4544a', '#4f6b8a', '#6d6088', '#3d7357', '#8a8578']

const loading = ref(false)
const saving = ref(false)
const tags = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const form = reactive({ name: '', color: '#1f6f5c' })

async function loadTags() {
  loading.value = true
  try {
    tags.value = await tagApi.list(props.series.id)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.color = '#1f6f5c'
  dialogVisible.value = true
}

function openEdit(tag) {
  editing.value = tag
  form.name = tag.name
  form.color = tag.color || '#1f6f5c'
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入标签名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await tagApi.update(props.series.id, editing.value.id, {
        name: form.name.trim(),
        color: form.color
      })
      ElMessage.success('标签已更新')
    } else {
      await tagApi.create(props.series.id, { name: form.name.trim(), color: form.color })
      ElMessage.success('标签已创建')
    }
    dialogVisible.value = false
    await loadTags()
  } finally {
    saving.value = false
  }
}

async function removeTag(tag) {
  try {
    await ElMessageBox.confirm(
      `确认删除标签「${tag.name}」？${tag.docCount ? `该标签已关联 ${tag.docCount} 个文档，关联关系会一并移除。` : ''}`,
      '删除标签',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await tagApi.remove(props.series.id, tag.id)
    ElMessage.success('标签已删除')
    await loadTags()
  } catch (error) {
    // canceled
  }
}

watch(() => props.series.id, loadTags)
onMounted(loadTags)
</script>

<style scoped>
.tags-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tag-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  min-height: 140px;
}

.tag-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
  background: var(--kb-surface);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.tag-card:hover {
  border-color: var(--kb-line-strong);
  box-shadow: var(--kb-shadow);
}

.tag-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.tag-dot {
  width: 12px;
  height: 12px;
  border-radius: 4px;
  flex-shrink: 0;
}

.tag-main strong {
  font-size: 13px;
  font-weight: 600;
}

.tag-main p {
  margin: 3px 0 0;
  font-size: 10px;
  color: var(--kb-muted);
}

.tag-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}

.color-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.color-presets {
  display: flex;
  gap: 8px;
}

.color-dot {
  width: 24px;
  height: 24px;
  border-radius: 5px;
  border: 2px solid transparent;
  cursor: pointer;
  padding: 0;
  transition: transform 0.15s ease;
}

.color-dot:hover {
  transform: scale(1.08);
}

.color-dot.active {
  border-color: var(--kb-ink);
}
</style>

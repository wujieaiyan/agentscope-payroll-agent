<template>
  <div class="members-tab">
    <div class="member-toolbar">
      <div>
        <h3 class="section-title">成员权限</h3>
        <p class="section-subtitle">个人与部门授权取并集；部门成员会自动获得该部门被授予的最高角色。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="Refresh" circle plain :loading="loading" @click="loadMembers" />
        <el-button type="primary" :icon="Plus" @click="openGrant">添加授权</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="members" size="default" empty-text="暂无授权成员">
      <el-table-column label="授权对象" min-width="220">
        <template #default="{ row }">
          <div class="subject-cell">
            <span class="subject-avatar" :class="row.subjectType.toLowerCase()">
              {{ row.subjectType === 'DEPT' ? '部' : (row.subjectName || '?').slice(0, 1) }}
            </span>
            <div>
              <strong>{{ row.subjectName }}</strong>
              <span class="subject-sub">
                {{ row.subjectType === 'DEPT' ? `部门 · ${row.memberCount || 0} 人` : row.account }}
              </span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="deptName" label="所属部门" width="140" />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.subjectType === 'DEPT' ? 'warning' : 'info'">
            {{ row.source }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="170">
        <template #default="{ row }">
          <el-select
            v-if="row.role !== 'OWNER'"
            :model-value="row.role"
            size="small"
            style="width: 120px"
            @change="(role) => changeRole(row, role)"
          >
            <el-option label="只读" value="READER" />
            <el-option label="编辑者" value="EDITOR" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
          <el-tag v-else size="small" type="danger" effect="plain">拥有者</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="授权时间" width="130">
        <template #default="{ row }">{{ formatTime(row.grantedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.role !== 'OWNER'" text type="danger" size="small" @click="removeMember(row)">
            移除
          </el-button>
          <span v-else class="muted small">-</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="grantVisible" title="添加授权" width="480px">
      <el-form label-position="top">
        <el-form-item label="授权对象类型">
          <el-radio-group v-model="grantForm.subjectType">
            <el-radio value="USER">个人</el-radio>
            <el-radio value="DEPT">部门</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="grantForm.subjectType === 'USER'" label="选择用户" required>
          <el-select
            v-model="grantForm.subjectId"
            filterable
            placeholder="按姓名或账号搜索"
            style="width: 100%"
          >
            <el-option
              v-for="user in users"
              :key="user.id"
              :label="`${user.name}（${user.account}）`"
              :value="user.id"
            >
              <span>{{ user.name }}</span>
              <span class="option-sub">{{ user.account }} · {{ user.deptName || '未分配部门' }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item v-else label="选择部门" required>
          <el-select v-model="grantForm.subjectId" placeholder="请选择部门" style="width: 100%">
            <el-option
              v-for="dept in departments"
              :key="dept.id"
              :label="dept.name"
              :value="dept.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="授予角色">
          <el-radio-group v-model="grantForm.role">
            <el-radio-button value="READER">只读</el-radio-button>
            <el-radio-button value="EDITOR">编辑者</el-radio-button>
            <el-radio-button value="ADMIN">管理员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="role-hint">
          <p><strong>只读</strong>：查看文档与检索结果。</p>
          <p><strong>编辑者</strong>：上传、编辑、重建文档，维护目录与标签。</p>
          <p><strong>管理员</strong>：管理成员、配置版本，执行全量重建。</p>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="grantVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitGrant">授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { memberApi } from '../../api'
import { formatTime } from '../../utils/format'

const props = defineProps({
  series: { type: Object, required: true }
})

const loading = ref(false)
const saving = ref(false)
const members = ref([])
const users = ref([])
const departments = ref([])

const grantVisible = ref(false)
const grantForm = reactive({ subjectType: 'USER', subjectId: null, role: 'READER' })

async function loadMembers() {
  loading.value = true
  try {
    members.value = await memberApi.list(props.series.id)
  } finally {
    loading.value = false
  }
}

async function loadOrg() {
  try {
    const candidates = (await memberApi.candidates(props.series.id)) || []
    users.value = candidates
      .filter((item) => item.subjectType === 'USER')
      .map((item) => {
        const [account, deptName] = String(item.extra || '').split(' · ')
        return { id: item.subjectId, name: item.name, account, deptName }
      })
    departments.value = candidates
      .filter((item) => item.subjectType === 'DEPT')
      .map((item) => ({ id: item.subjectId, name: item.name }))
  } catch (error) {
    users.value = []
    departments.value = []
  }
}

function openGrant() {
  grantForm.subjectType = 'USER'
  grantForm.subjectId = null
  grantForm.role = 'READER'
  grantVisible.value = true
}

async function submitGrant() {
  if (!grantForm.subjectId) {
    ElMessage.warning(grantForm.subjectType === 'USER' ? '请选择用户' : '请选择部门')
    return
  }
  saving.value = true
  try {
    members.value = await memberApi.grant(props.series.id, [
      {
        subjectType: grantForm.subjectType,
        subjectId: grantForm.subjectId,
        role: grantForm.role
      }
    ])
    ElMessage.success('授权已保存')
    grantVisible.value = false
  } finally {
    saving.value = false
  }
}

async function changeRole(row, role) {
  try {
    await memberApi.updateRole(props.series.id, row.id, role)
    ElMessage.success('角色已更新')
    await loadMembers()
  } catch (error) {
    await loadMembers()
  }
}

async function removeMember(row) {
  try {
    await ElMessageBox.confirm(
      `确认移除「${row.subjectName}」的访问权限？`,
      '移除授权',
      { confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning' }
    )
    await memberApi.remove(props.series.id, row.id)
    ElMessage.success('授权已移除')
    await loadMembers()
  } catch (error) {
    // canceled
  }
}

watch(() => props.series.id, loadMembers)

onMounted(() => {
  loadMembers()
  loadOrg()
})
</script>

<style scoped>
.members-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.member-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.subject-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.subject-avatar {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.subject-avatar.dept {
  background: var(--kb-accent-soft);
  color: #8c6132;
}

.subject-cell strong {
  display: block;
  font-size: 13px;
  font-weight: 560;
}

.subject-sub {
  display: block;
  margin-top: 2px;
  font-size: 10px;
  color: var(--kb-muted);
}

.option-sub {
  float: right;
  margin-left: 12px;
  font-size: 11px;
  color: var(--kb-muted);
}

.role-hint {
  padding: 12px 14px;
  border-radius: 7px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-line-soft);
  font-size: 11px;
  color: var(--kb-muted);
  line-height: 1.7;
}

.role-hint p {
  margin: 0;
}

.role-hint strong {
  color: var(--kb-ink-soft);
}

.small {
  font-size: 11px;
}
</style>

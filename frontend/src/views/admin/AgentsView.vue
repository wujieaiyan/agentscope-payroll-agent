<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const rows = ref([]);
const meta = ref({ writable: false, location: '-' });
const skillOptions = ref([]);
const active = ref({ agentId: '', name: '', configured: '', available: true });
const keyword = ref('');
const statusFilter = ref('');
const loading = ref(false);
const showForm = ref(false);
const editing = ref(false);
const saving = ref(false);
const form = reactive(blank());

const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  return rows.value.filter((row) => {
    if (statusFilter.value && row.status !== statusFilter.value) return false;
    if (!needle) return true;
    return [row.id, row.name, row.description, ...(row.tags || [])]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle));
  });
});

function blank() {
  return {
    id: '',
    name: '',
    avatar: '',
    description: '',
    tagsText: '',
    skillIds: [],
    visibility: 'PUBLIC',
    status: 'ONLINE',
  };
}

async function load() {
  loading.value = true;
  try {
    const [agents, metaValue, skills, activeValue] = await Promise.all([
      api('/api/admin/agents'),
      api('/api/admin/agents/meta'),
      api('/api/admin/agents/skills'),
      api('/api/admin/agents/active'),
    ]);
    rows.value = agents || [];
    meta.value = metaValue || meta.value;
    skillOptions.value = skills || [];
    active.value = activeValue || active.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function createAgent() {
  editing.value = false;
  Object.assign(form, blank());
  showForm.value = true;
}

function editAgent(row) {
  editing.value = true;
  Object.assign(form, {
    ...blank(),
    ...row,
    tagsText: (row.tags || []).join(', '),
    skillIds: [...(row.skill_ids || row.skillIds || [])],
  });
  showForm.value = true;
}

function toggleSkill(id) {
  const index = form.skillIds.indexOf(id);
  if (index >= 0) form.skillIds.splice(index, 1);
  else form.skillIds.push(id);
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    const body = {
      id: form.id.trim(),
      name: form.name.trim(),
      avatar: form.avatar.trim(),
      description: form.description.trim(),
      tags: form.tagsText
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean),
      skillIds: form.skillIds,
      visibility: form.visibility,
      status: form.status,
    };
    if (editing.value) {
      await api(`/api/admin/agents/${encodeURIComponent(body.id)}`, { method: 'PUT', body });
    } else {
      await api('/api/admin/agents', { method: 'POST', body });
    }
    toast('智能体已保存', 'success');
    showForm.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function remove(row) {
  if (!window.confirm(`删除智能体「${row.name}」？正在被流程引用时请先解除引用。`)) return;
  try {
    await api(`/api/admin/agents/${encodeURIComponent(row.id)}`, { method: 'DELETE' });
    toast('智能体已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function activate(row) {
  if (!window.confirm(`将「${row.name}」设为全局核算智能体？会同时取消全局默认流程。`)) return;
  try {
    await api(`/api/admin/agents/${encodeURIComponent(row.id)}/active`, { method: 'PUT' });
    toast('已设为全局智能体', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function clearActive() {
  try {
    await api('/api/admin/agents/active', { method: 'DELETE' });
    toast('已取消全局智能体，对话回到内置流水线', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>智能体</h2>
        <p>
          专家智能体存于 {{ meta.location || 'Nacos' }}；全局智能体与全局流程互斥，动态路由会优先在在线智能体中挑选。
        </p>
      </div>
      <div class="toolbar">
        <span class="badge" :class="meta.writable ? 'green' : 'orange'">
          {{ meta.writable ? '可写' : '只读（Nacos 未启用）' }}
        </span>
        <input v-model="keyword" placeholder="搜索名称 / 描述 / 标签" style="width: 220px" />
        <select v-model="statusFilter" style="width: 120px">
          <option value="">全部状态</option>
          <option value="ONLINE">在线</option>
          <option value="OFFLINE">离线</option>
        </select>
        <button class="primary" :disabled="!meta.writable" @click="createAgent">新增智能体</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <h3>全局核算智能体</h3>
        <small>当前：{{ active.name || '内置三段流水线' }}</small>
      </div>
      <div class="toolbar">
        <span class="badge" :class="active.available ? 'blue' : 'red'">
          {{ active.agentId || 'pipeline' }}
        </span>
        <span class="hint">配置值：{{ active.configured || '未配置' }}</span>
        <span v-if="!active.available" class="error-text">配置的智能体不可用，已回退到流水线</span>
        <span class="spacer"></span>
        <button class="ghost sm" :disabled="!active.configured" @click="clearActive">取消全局智能体</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>描述</th>
            <th>标签</th>
            <th>技能</th>
            <th>可见性</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in filtered" :key="row.id">
            <td class="mono">{{ row.id }}</td>
            <td>
              <span style="margin-right: 6px">{{ row.avatar || '🤖' }}</span>{{ row.name }}
            </td>
            <td class="muted" style="max-width: 320px">{{ row.description }}</td>
            <td>
              <span v-for="tag in row.tags || []" :key="tag" class="badge">{{ tag }}</span>
            </td>
            <td class="muted">{{ (row.skill_ids || row.skillIds || []).join('、') || '—' }}</td>
            <td>{{ row.visibility }}</td>
            <td>
              <span class="badge" :class="row.status === 'ONLINE' ? 'green' : 'red'">
                {{ row.status === 'ONLINE' ? '在线' : '离线' }}
              </span>
            </td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" :disabled="!meta.writable" @click="editAgent(row)">编辑</button>
                <button
                  class="subtle sm"
                  :disabled="!meta.writable || row.status !== 'ONLINE' || active.agentId === row.id"
                  @click="activate(row)"
                >
                  设为全局
                </button>
                <button class="subtle sm" :disabled="!meta.writable" @click="remove(row)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!filtered.length">
            <td colspan="8" class="empty">{{ loading ? '加载中…' : '没有符合条件的智能体' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal">
        <div class="modal-head">
          <h3>{{ editing ? `编辑智能体 ${form.id}` : '新增智能体' }}</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              ID
              <input v-model="form.id" :disabled="editing" placeholder="payroll-expert" />
            </label>
            <label class="field">
              名称
              <input v-model="form.name" placeholder="薪酬专家" />
            </label>
            <label class="field">
              头像（emoji 或文字）
              <input v-model="form.avatar" placeholder="🧮" />
            </label>
            <label class="field">
              可见性
              <select v-model="form.visibility">
                <option value="PUBLIC">公开</option>
                <option value="PRIVATE">私有</option>
              </select>
            </label>
            <label class="field">
              状态
              <select v-model="form.status">
                <option value="ONLINE">在线</option>
                <option value="OFFLINE">离线</option>
              </select>
            </label>
            <label class="field span-2">
              描述
              <textarea v-model="form.description" rows="3" placeholder="擅长薪酬核算、社保公积金与个税解释"></textarea>
            </label>
            <label class="field span-2">
              标签（逗号分隔）
              <input v-model="form.tagsText" placeholder="薪酬, 核算" />
            </label>
          </div>
          <div>
            <div class="muted" style="margin-bottom: 6px">绑定技能</div>
            <div class="toolbar">
              <button
                v-for="skill in skillOptions"
                :key="skill.id"
                class="chip"
                :class="{ 'badge blue': form.skillIds.includes(skill.id) }"
                @click="toggleSkill(skill.id)"
              >
                {{ skill.id }}
              </button>
              <span v-if="!skillOptions.length" class="muted">暂无可绑定技能</span>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showForm = false">取消</button>
          <button class="primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

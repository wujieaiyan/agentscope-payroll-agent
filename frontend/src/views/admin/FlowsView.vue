<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const data = ref({ flows: [], activeFlowId: '', writable: false, location: '-' });
const agents = ref([]);
const loading = ref(false);
const selected = ref(null);
const showForm = ref(false);
const editing = ref(false);
const saving = ref(false);
const form = reactive({ id: '', name: '', description: '', nodesText: '', edgesText: '' });

const flows = computed(() => data.value.flows || []);

function templateNodes() {
  return JSON.stringify(
    [
      { id: 'start', type: 'START', name: '开始', x: 40, y: 120 },
      { id: 'end', type: 'END', name: '结束', x: 620, y: 120 },
    ],
    null,
    2
  );
}

function templateEdges() {
  return JSON.stringify([{ id: 'e1', source: 'start', target: 'end' }], null, 2);
}

async function load() {
  loading.value = true;
  try {
    data.value = (await api('/api/admin/flows')) || data.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function loadMeta() {
  try {
    const meta = await api('/api/admin/flows/meta');
    agents.value = (meta && meta.agents) || [];
  } catch (error) {
    toast(error.message, 'error');
  }
}

function createFlow() {
  editing.value = false;
  Object.assign(form, {
    id: '',
    name: '',
    description: '',
    nodesText: templateNodes(),
    edgesText: templateEdges(),
  });
  showForm.value = true;
}

function editFlow(flow) {
  editing.value = true;
  Object.assign(form, {
    id: flow.id,
    name: flow.name,
    description: flow.description || '',
    nodesText: JSON.stringify(flow.nodes || [], null, 2),
    edgesText: JSON.stringify(flow.edges || [], null, 2),
  });
  showForm.value = true;
}

async function save() {
  if (saving.value) return;
  let nodes;
  let edges;
  try {
    nodes = JSON.parse(form.nodesText || '[]');
    edges = JSON.parse(form.edgesText || '[]');
  } catch (error) {
    toast('节点或连线不是合法 JSON', 'error');
    return;
  }
  saving.value = true;
  try {
    const body = { name: form.name, description: form.description, nodes, edges };
    if (editing.value) {
      await api(`/api/admin/flows/${encodeURIComponent(form.id)}`, { method: 'PUT', body });
    } else {
      await api('/api/admin/flows', { method: 'POST', body });
    }
    toast('流程已保存', 'success');
    showForm.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function activate(flow) {
  if (!window.confirm(`将「${flow.name}」设为全局默认流程？会取消全局智能体。`)) return;
  try {
    await api(`/api/admin/flows/${encodeURIComponent(flow.id)}/active`, { method: 'PUT' });
    toast('已设为全局默认流程', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function clearActive() {
  try {
    await api('/api/admin/flows/active', { method: 'DELETE' });
    toast('已取消全局默认流程', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function remove(flow) {
  if (!window.confirm(`删除流程「${flow.name}」？`)) return;
  try {
    await api(`/api/admin/flows/${encodeURIComponent(flow.id)}`, { method: 'DELETE' });
    if (selected.value && selected.value.id === flow.id) selected.value = null;
    toast('流程已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

function agentName(id) {
  const found = agents.value.find((agent) => agent.id === id);
  return found ? found.name : id;
}

function nodeTypeLabel(type) {
  return { START: '开始', AGENT: '智能体', CONDITION: '条件', END: '结束' }[type] || type;
}

onMounted(async () => {
  await Promise.all([load(), loadMeta()]);
});
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>流程编排</h2>
        <p>
          流程由 START / AGENT / CONDITION / END 节点组成，可设一个全局默认流程；流程与全局智能体互斥。
        </p>
      </div>
      <div class="toolbar">
        <span class="badge" :class="data.writable ? 'green' : 'orange'">
          {{ data.writable ? '可写' : '只读（Nacos 未启用）' }}
        </span>
        <button class="ghost" :disabled="!data.activeFlowId" @click="clearActive">取消默认流程</button>
        <button class="primary" :disabled="!data.writable" @click="createFlow">新增流程</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>描述</th>
            <th>节点</th>
            <th>连线</th>
            <th>默认</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="flow in flows" :key="flow.id">
            <td class="mono">{{ flow.id }}</td>
            <td>{{ flow.name }}</td>
            <td class="muted" style="max-width: 300px">{{ flow.description }}</td>
            <td>{{ (flow.nodes || []).length }}</td>
            <td>{{ (flow.edges || []).length }}</td>
            <td>
              <span v-if="data.activeFlowId === flow.id" class="badge green">默认</span>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" @click="selected = flow">查看</button>
                <button class="subtle sm" :disabled="!data.writable" @click="editFlow(flow)">编辑</button>
                <button
                  class="subtle sm"
                  :disabled="!data.writable || data.activeFlowId === flow.id"
                  @click="activate(flow)"
                >
                  设为默认
                </button>
                <button class="subtle sm" :disabled="!data.writable" @click="remove(flow)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!flows.length">
            <td colspan="7" class="empty">{{ loading ? '加载中…' : '还没有流程' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="selected" class="drawer-mask" @click.self="selected = null">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>{{ selected.name }} <span class="mono">{{ selected.id }}</span></h3>
          <button class="subtle sm" @click="selected = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div class="card">
            <div class="card-title"><h3>节点</h3><small>{{ (selected.nodes || []).length }} 个</small></div>
            <div class="node-list">
              <div v-for="node in selected.nodes || []" :key="node.id" class="node-item">
                <span class="badge blue">{{ nodeTypeLabel(node.type) }}</span>
                <span class="name">{{ node.name || node.id }}</span>
                <span class="muted">{{ node.type === 'AGENT' ? agentName(node.agentId) : node.id }}</span>
                <span v-if="node.condition" class="badge orange">
                  {{ node.condition }} = {{ node.conditionValue }}
                </span>
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-title"><h3>连线</h3><small>{{ (selected.edges || []).length }} 条</small></div>
            <div class="node-list">
              <div v-for="edge in selected.edges || []" :key="edge.id" class="node-item">
                <span class="name mono">{{ edge.source }} → {{ edge.target }}</span>
                <span v-if="edge.sourceHandle" class="badge">{{ edge.sourceHandle }}</span>
              </div>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width: min(760px, 96vw)">
        <div class="modal-head">
          <h3>{{ editing ? `编辑流程 ${form.id}` : '新增流程' }}</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              名称
              <input v-model="form.name" placeholder="月度核算流程" />
            </label>
            <label class="field">
              描述
              <input v-model="form.description" placeholder="核算 → 复核 → 汇总" />
            </label>
            <label class="field span-2">
              节点（JSON 数组）
              <textarea v-model="form.nodesText" rows="8" class="mono"></textarea>
            </label>
            <label class="field span-2">
              连线（JSON 数组）
              <textarea v-model="form.edgesText" rows="5" class="mono"></textarea>
            </label>
          </div>
          <p class="hint">
            AGENT 节点需要 agentId，可用：{{ agents.map((agent) => agent.id).join('、') || '暂无智能体' }}。
          </p>
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

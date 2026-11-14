<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import {
  Handle,
  MarkerType,
  Position,
  VueFlow,
  applyEdgeChanges,
  applyNodeChanges,
  useVueFlow,
} from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const { screenToFlowCoordinate, fitView, zoomIn, zoomOut } = useVueFlow();

const loading = ref(false);
const saving = ref(false);
const activating = ref(false);
const clearing = ref(false);
const flows = ref([]);
const activeFlowId = ref('');
const meta = ref({ writable: false, location: '', agents: [] });
const graphNodes = ref([]);
const graphEdges = ref([]);
const flow = reactive({ id: null, name: '', description: '' });
const errors = ref([]);

const palette = [
  { type: 'START', label: '开始', icon: '▶' },
  { type: 'AGENT', label: '智能体', icon: '🤖' },
  { type: 'CONDITION', label: '条件', icon: '🔀' },
  { type: 'END', label: '结束', icon: '⏹' },
];

const selectedNode = computed(() => graphNodes.value.find((node) => node.selected) || null);
const selectedEdge = computed(() => graphEdges.value.find((edge) => edge.selected) || null);
const activeText = computed(() => {
  const active = flows.value.find((item) => item.id === activeFlowId.value);
  return active
    ? `全局默认流程：${active.name}（下一轮对话生效）`
    : '全局默认流程：未设置，沿用既有对话链路';
});
const storageText = computed(() =>
  meta.value.writable ? `存储：Nacos ${meta.value.location}` : '存储：代码内置（未启用 Nacos，只读）'
);

async function reload(preferredId) {
  loading.value = true;
  try {
    const [data, metaValue] = await Promise.all([api('/api/admin/flows'), api('/api/admin/flows/meta')]);
    flows.value = (data && data.flows) || [];
    activeFlowId.value = (data && data.activeFlowId) || '';
    meta.value = metaValue || { writable: false, location: '', agents: [] };
    const wanted =
      preferredId || flow.id || activeFlowId.value || (flows.value[0] && flows.value[0].id);
    if (wanted) selectFlow(wanted);
    else newFlow();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function selectFlow(id) {
  const found = flows.value.find((item) => item.id === id);
  if (found) loadGraph(found);
}

function loadGraph(source) {
  Object.assign(flow, { id: source.id, name: source.name || '', description: source.description || '' });
  errors.value = [];
  graphNodes.value = (source.nodes || []).map((node) => ({
    id: node.id,
    type: (node.type || 'AGENT').toLowerCase(),
    position: { x: Number(node.x) || 0, y: Number(node.y) || 0 },
    data: {
      name: node.name || '',
      agentId: node.agentId || '',
      avatar: agentAvatar(node.agentId),
      condition: node.condition || '',
      conditionValue: node.conditionValue || '',
      contextMode: node.contextMode || '',
    },
  }));
  graphEdges.value = (source.edges || []).map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle || undefined,
    label: edge.sourceHandle ? (edge.sourceHandle === 'TRUE' ? '是' : '否') : undefined,
    markerEnd: MarkerType.ArrowClosed,
  }));
  nextTick(() => fitView({ padding: 0.08 }));
}

function newFlow() {
  const agent =
    meta.value.agents.find((item) => item.status === 'ONLINE') || meta.value.agents[0];
  const nodes = [
    { id: 'start', type: 'start', position: { x: 80, y: 200 }, data: { name: '开始' } },
    { id: 'end', type: 'end', position: { x: 820, y: 200 }, data: { name: '结束' } },
  ];
  const edges = [];
  if (agent) {
    nodes.splice(1, 0, {
      id: 'agent-1',
      type: 'agent',
      position: { x: 440, y: 200 },
      data: { name: agent.name, agentId: agent.id, avatar: agent.avatar || '🤖' },
    });
    edges.push(makeEdge('start', 'agent-1', null));
    edges.push(makeEdge('agent-1', 'end', null));
  } else {
    edges.push(makeEdge('start', 'end', null));
  }
  Object.assign(flow, { id: null, name: '新流程', description: '' });
  graphNodes.value = nodes;
  graphEdges.value = edges;
  errors.value = [];
  nextTick(() => fitView({ padding: 0.08 }));
}

function duplicateFlow() {
  const copy = {
    id: null,
    name: `${flow.name || '流程'} 副本`,
    description: flow.description,
    nodes: syncNodes().map((node) => ({ ...node })),
    edges: syncEdges().map((edge) => ({ ...edge })),
  };
  loadGraph(copy);
  flow.id = null;
  toast('已复制为新流程，保存后生效', 'success');
}

async function saveFlow() {
  if (!meta.value.writable) {
    toast('未启用 Nacos，流程只读', 'error');
    return;
  }
  if (!flow.name || !flow.name.trim()) {
    toast('流程名称必填', 'error');
    return;
  }
  const body = {
    name: flow.name,
    description: flow.description,
    nodes: syncNodes(),
    edges: syncEdges(),
  };
  saving.value = true;
  errors.value = [];
  try {
    const saved = flow.id
      ? await api(`/api/admin/flows/${encodeURIComponent(flow.id)}`, { method: 'PUT', body })
      : await api('/api/admin/flows', { method: 'POST', body });
    toast('流程已保存', 'success');
    await reload(saved.id);
  } catch (error) {
    errors.value = String(error.message || error)
      .split('；')
      .filter(Boolean);
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function removeFlow() {
  if (!flow.id || !meta.value.writable) return;
  if (!window.confirm(`删除流程「${flow.name}」？`)) return;
  try {
    await api(`/api/admin/flows/${encodeURIComponent(flow.id)}`, { method: 'DELETE' });
    toast('流程已删除', 'success');
    flow.id = null;
    await reload();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function activateFlow() {
  if (!flow.id || !meta.value.writable) return;
  if (!window.confirm('设为全局默认流程会同时取消默认核算智能体，确认继续？')) return;
  activating.value = true;
  try {
    await api(`/api/admin/flows/${encodeURIComponent(flow.id)}/active`, { method: 'PUT' });
    toast('已设为全局默认流程，并取消默认核算智能体', 'success');
    await reload(flow.id);
  } catch (error) {
    errors.value = String(error.message || error)
      .split('；')
      .filter(Boolean);
    toast(error.message, 'error');
  } finally {
    activating.value = false;
  }
}

async function clearActiveFlow() {
  if (!meta.value.writable || !activeFlowId.value) return;
  if (!window.confirm('取消全局默认流程？下一轮对话将回到核算智能体链路。')) return;
  clearing.value = true;
  try {
    await api('/api/admin/flows/active', { method: 'DELETE' });
    toast('已取消全局默认流程，下一轮对话生效', 'success');
    await reload(flow.id);
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    clearing.value = false;
  }
}

function onDragStart(event, type) {
  if (!meta.value.writable) return;
  event.dataTransfer.setData('application/x-flow-node', type);
  event.dataTransfer.effectAllowed = 'move';
}

function onDrop(event) {
  const type = event.dataTransfer.getData('application/x-flow-node');
  if (!type || !meta.value.writable) return;
  let position = { x: 120, y: 120 };
  if (screenToFlowCoordinate) {
    position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY });
  }
  addNodeAt(type, position);
}

function addNode(type) {
  addNodeAt(type, {
    x: 120 + (graphNodes.value.length % 4) * 220,
    y: 100 + Math.floor(graphNodes.value.length / 4) * 150,
  });
}

function addNodeAt(type, position) {
  if (!meta.value.writable) return;
  const upper = String(type || '').toUpperCase();
  if (upper === 'START' && graphNodes.value.some((node) => node.type === 'start')) {
    toast('流程只能有一个开始节点', 'error');
    return;
  }
  if (upper === 'END' && graphNodes.value.some((node) => node.type === 'end')) {
    toast('流程只能有一个结束节点', 'error');
    return;
  }
  const id =
    upper === 'START'
      ? 'start'
      : upper === 'END'
        ? 'end'
        : `${upper.toLowerCase()}-${Date.now().toString(36)}`;
  if (graphNodes.value.some((node) => node.id === id)) {
    toast('节点 id 重复，请重试', 'error');
    return;
  }
  const agent =
    meta.value.agents.find((item) => item.status === 'ONLINE') || meta.value.agents[0];
  const data =
    {
      START: { name: '开始' },
      END: { name: '结束' },
      AGENT: {
        name: agent ? agent.name : '智能体节点',
        agentId: agent ? agent.id : '',
        avatar: agent ? agent.avatar : '🤖',
      },
      CONDITION: {
        name: '条件判断',
        condition: 'HAS_CALCULATION_TARGETS',
        conditionValue: '',
      },
    }[upper] || { name: '节点' };
  graphNodes.value.push({
    id,
    type: upper.toLowerCase(),
    position: position || { x: 120, y: 120 },
    data,
  });
}

function onNodesChange(changes) {
  graphNodes.value = applyNodeChanges(changes, graphNodes.value);
}

function onEdgesChange(changes) {
  graphEdges.value = applyEdgeChanges(changes, graphEdges.value);
}

function onConnect(connection) {
  if (!meta.value.writable || !connection) return;
  const { source, target } = connection;
  const sourceHandle = connection.sourceHandle || null;
  if (!source || !target || source === target) return;
  const sourceNode = graphNodes.value.find((node) => node.id === source);
  if (sourceNode && sourceNode.type === 'condition' && !sourceHandle) {
    toast('条件节点请从“是/否”锚点连线', 'error');
    return;
  }
  const exists = graphEdges.value.some(
    (edge) =>
      edge.source === source &&
      edge.target === target &&
      (edge.sourceHandle || null) === sourceHandle
  );
  if (exists) {
    toast('该连线已存在', 'error');
    return;
  }
  graphEdges.value.push(makeEdge(source, target, sourceHandle));
}

function makeEdge(source, target, sourceHandle) {
  return {
    id: `e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`,
    source,
    target,
    sourceHandle: sourceHandle || undefined,
    label: sourceHandle ? (sourceHandle === 'TRUE' ? '是' : '否') : undefined,
    markerEnd: MarkerType.ArrowClosed,
  };
}

function deleteSelectedNode() {
  if (!selectedNode.value || !meta.value.writable) return;
  const id = selectedNode.value.id;
  graphNodes.value = graphNodes.value.filter((node) => node.id !== id);
  graphEdges.value = graphEdges.value.filter(
    (edge) => edge.source !== id && edge.target !== id
  );
}

function deleteSelectedEdge() {
  if (!selectedEdge.value || !meta.value.writable) return;
  const id = selectedEdge.value.id;
  graphEdges.value = graphEdges.value.filter((edge) => edge.id !== id);
}

function applyAgentDefaults(node) {
  const agent = meta.value.agents.find((item) => item.id === node.data.agentId);
  if (agent) {
    node.data.name = agent.name;
    node.data.avatar = agent.avatar || '🤖';
  }
}

function agentAvatar(agentId) {
  const agent = meta.value.agents.find((item) => item.id === agentId);
  return agent ? agent.avatar || '🤖' : '🤖';
}

function conditionText(condition) {
  return (
    {
      HAS_CALCULATION_TARGETS: '有核算对象',
      HAS_TOOL_ERROR: '上游工具报错',
      OUTPUT_CONTAINS: '输出包含关键字',
    }[condition] ||
    condition ||
    '未设置条件'
  );
}

function syncNodes() {
  const typeMap = { start: 'START', agent: 'AGENT', condition: 'CONDITION', end: 'END' };
  return graphNodes.value.map((node) => ({
    id: node.id,
    type: typeMap[node.type] || 'AGENT',
    name: (node.data && node.data.name) || '',
    agentId: node.type === 'agent' ? (node.data && node.data.agentId) || '' : '',
    condition: node.type === 'condition' ? (node.data && node.data.condition) || '' : '',
    conditionValue:
      node.type === 'condition' ? (node.data && node.data.conditionValue) || '' : '',
    contextMode:
      node.type === 'agent' ? (node.data && node.data.contextMode) || null : null,
    x: node.position.x,
    y: node.position.y,
  }));
}

function syncEdges() {
  return graphEdges.value.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle || null,
  }));
}

onMounted(() => reload());
</script>

<template>
  <div class="flows-page">
    <aside class="flow-list card">
      <div class="card-title">
        <h3>流程列表</h3>
        <button class="subtle sm" :disabled="loading" @click="reload()">刷新</button>
      </div>
      <button class="primary" :disabled="!meta.writable" @click="newFlow">＋ 新建流程</button>
      <p v-if="loading" class="hint">加载中…</p>
      <div
        v-for="item in flows"
        :key="item.id"
        class="flow-item"
        :class="{ active: item.id === flow.id }"
        @click="selectFlow(item.id)"
      >
        <div class="flow-item-head">
          <strong>{{ item.name }}</strong>
          <span v-if="item.id === activeFlowId" class="badge blue">默认</span>
        </div>
        <p class="hint">{{ (item.nodes || []).length }} 节点 · {{ (item.edges || []).length }} 连线</p>
      </div>
      <p class="hint flow-storage">{{ storageText }}</p>
    </aside>

    <section class="flow-main card">
      <div class="flow-toolbar">
        <input v-model="flow.name" class="flow-name" maxlength="40" placeholder="流程名称" :disabled="!meta.writable" />
        <button class="primary" :disabled="!meta.writable || saving" @click="saveFlow">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button class="ghost" :disabled="!meta.writable" @click="duplicateFlow">复制</button>
        <button
          class="ghost"
          :disabled="!meta.writable || !flow.id || flow.id === activeFlowId || activating"
          @click="activateFlow"
        >
          {{ flow.id === activeFlowId ? '当前默认' : '设为默认' }}
        </button>
        <button class="ghost" :disabled="!meta.writable || !activeFlowId || clearing" @click="clearActiveFlow">
          {{ clearing ? '取消中…' : '取消默认' }}
        </button>
        <button class="ghost sm" title="缩小" @click="zoomOut">－</button>
        <button class="ghost sm" title="放大" @click="zoomIn">＋</button>
        <button class="ghost" @click="fitView({ padding: 0.08 })">适应画布</button>
        <button class="danger" :disabled="!meta.writable || !flow.id" @click="removeFlow">删除</button>
      </div>
      <p v-if="!meta.writable" class="hint">未启用 Nacos：展示内置标准流程，保存与激活已禁用。</p>
      <div v-if="errors.length" class="flow-errors">
        <p v-for="(item, index) in errors" :key="index">{{ item }}</p>
      </div>
      <div class="flow-canvas-wrap" @dragover.prevent @drop="onDrop">
        <div class="flow-palette">
          <div
            v-for="item in palette"
            :key="item.type"
            class="flow-palette-item"
            :draggable="meta.writable"
            @dragstart="onDragStart($event, item.type)"
            @click="addNode(item.type)"
          >
            <span class="flow-palette-icon">{{ item.icon }}</span>{{ item.label }}
          </div>
          <p class="hint">拖拽到画布，或点击添加</p>
        </div>
        <VueFlow
          :nodes="graphNodes"
          :edges="graphEdges"
          :default-viewport="{ x: 0, y: 0, zoom: 0.85 }"
          :min-zoom="0.3"
          :max-zoom="1.6"
          fit-view-on-init
          class="flow-canvas"
          @nodes-change="onNodesChange"
          @edges-change="onEdgesChange"
          @connect="onConnect"
        >
          <template #node-start="{ data }">
            <Handle type="source" :position="Position.Right" />
            <div class="flow-node flow-start">▶ {{ data.name }}</div>
          </template>
          <template #node-agent="{ data }">
            <Handle type="target" :position="Position.Left" />
            <div class="flow-node flow-agent">
              <span class="flow-avatar">{{ data.avatar || '🤖' }}</span>
              <div class="flow-node-text">
                <strong>{{ data.name }}</strong>
                <small>{{ data.agentId }}</small>
              </div>
            </div>
            <Handle type="source" :position="Position.Right" />
          </template>
          <template #node-condition="{ data }">
            <Handle type="target" :position="Position.Left" />
            <div class="flow-node flow-condition">
              <strong>{{ data.name }}</strong>
              <small>{{ conditionText(data.condition) }}</small>
            </div>
            <Handle id="TRUE" type="source" :position="Position.Right" style="top: 32%" />
            <Handle id="FALSE" type="source" :position="Position.Right" style="top: 68%" />
            <span class="flow-branch flow-branch-true">是</span>
            <span class="flow-branch flow-branch-false">否</span>
          </template>
          <template #node-end="{ data }">
            <Handle type="target" :position="Position.Left" />
            <div class="flow-node flow-end">⏹ {{ data.name }}</div>
          </template>
        </VueFlow>
      </div>
      <label class="flow-desc">
        流程说明
        <textarea v-model="flow.description" rows="2" maxlength="200" :disabled="!meta.writable"></textarea>
      </label>
    </section>

    <aside class="flow-props card">
      <h3>节点属性</h3>
      <template v-if="selectedNode">
        <label class="field">
          名称
          <input v-model="selectedNode.data.name" maxlength="30" :disabled="!meta.writable" />
        </label>
        <template v-if="selectedNode.type === 'agent'">
          <label class="field">
            绑定智能体
            <select
              v-model="selectedNode.data.agentId"
              :disabled="!meta.writable"
              @change="applyAgentDefaults(selectedNode)"
            >
              <option v-for="item in meta.agents" :key="item.id" :value="item.id">
                {{ item.name }}{{ item.status === 'ONLINE' ? '' : '（未上架）' }}
              </option>
            </select>
          </label>
          <label class="field">
            上游记录
            <select v-model="selectedNode.data.contextMode" :disabled="!meta.writable">
              <option value="">自动（仅数据流水线串联）</option>
              <option value="UPSTREAM">接收上游记录</option>
              <option value="NONE">不接收上游记录</option>
            </select>
          </label>
          <p class="hint">
            自动模式只把带核算对象的上游记录传给下游；纯文本角色默认隔离，跨角色串联需显式选择“接收上游记录”。
          </p>
        </template>
        <template v-if="selectedNode.type === 'condition'">
          <label class="field">
            条件
            <select v-model="selectedNode.data.condition" :disabled="!meta.writable">
              <option value="HAS_CALCULATION_TARGETS">有核算对象</option>
              <option value="HAS_TOOL_ERROR">上游工具报错</option>
              <option value="OUTPUT_CONTAINS">输出包含关键字</option>
            </select>
          </label>
          <label v-if="selectedNode.data.condition === 'OUTPUT_CONTAINS'" class="field">
            关键字
            <input v-model="selectedNode.data.conditionValue" maxlength="40" :disabled="!meta.writable" />
          </label>
        </template>
        <button class="danger sm" :disabled="!meta.writable" @click="deleteSelectedNode">删除节点</button>
      </template>
      <template v-else-if="selectedEdge">
        <p class="hint">
          {{ selectedEdge.source }} → {{ selectedEdge.target }}
          <span v-if="selectedEdge.sourceHandle">
            （{{ selectedEdge.sourceHandle === 'TRUE' ? '是' : '否' }}）
          </span>
        </p>
        <button class="danger sm" :disabled="!meta.writable" @click="deleteSelectedEdge">删除连线</button>
      </template>
      <p v-else class="hint">点击节点或连线查看属性</p>
      <hr />
      <p class="hint">{{ activeText }}</p>
      <p class="hint">条件节点有“是/否”两个输出锚点；智能体节点可连接多个下游并行执行。</p>
    </aside>
  </div>
</template>

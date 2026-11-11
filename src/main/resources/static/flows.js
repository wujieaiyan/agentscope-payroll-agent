/* 流程编排页：Vue 3 + 本地 Vue Flow，复用 app.js 的全局 api()/toast()/showError()。 */
let flowsVm = null;

function initFlowsTab() {
  if (flowsVm) {
    flowsVm.reload();
    return;
  }
  if (typeof Vue === 'undefined' || typeof VueFlowCore === 'undefined') {
    showError(new Error('流程画布资源未加载，无法打开流程编排'));
    return;
  }
  const { createApp } = Vue;

  const App = {
    setup() {
      const flow = VueFlowCore.useVueFlow();
      return {
        screenToFlowCoordinate: flow.screenToFlowCoordinate,
        fitView: flow.fitView,
        zoomIn: flow.zoomIn,
        zoomOut: flow.zoomOut,
      };
    },
    data() {
      return {
        Position: VueFlowCore.Position,
        loading: false,
        saving: false,
        activating: false,
        clearing: false,
        flows: [],
        activeFlowId: '',
        meta: { writable: false, location: '', agents: [] },
        graphNodes: [],
        graphEdges: [],
        flow: { id: null, name: '新流程', description: '', nodes: [], edges: [] },
        errors: [],
        palette: [
          { type: 'START', label: '开始', icon: '▶' },
          { type: 'AGENT', label: '智能体', icon: '🤖' },
          { type: 'CONDITION', label: '条件', icon: '🔀' },
          { type: 'END', label: '结束', icon: '⏹' },
        ],
      };
    },
    computed: {
      storageText() {
        return this.meta.writable
          ? '存储：Nacos ' + this.meta.location
          : '存储：代码内置（未启用 Nacos，只读）';
      },
      activeText() {
        const active = this.flows.find((flow) => flow.id === this.activeFlowId);
        return active
          ? '全局默认流程：' + active.name + '（下一轮对话生效）'
          : '全局默认流程：未设置，沿用既有对话链路';
      },
      selectedNode() {
        return this.graphNodes.find((node) => node.selected) || null;
      },
      selectedEdge() {
        return this.graphEdges.find((edge) => edge.selected) || null;
      },
    },
    methods: {
      async reload(preferredId) {
        this.loading = true;
        try {
          const [data, meta] = await Promise.all([
            api('/api/admin/flows'),
            api('/api/admin/flows/meta'),
          ]);
          this.flows = (data && data.flows) || [];
          this.activeFlowId = (data && data.activeFlowId) || '';
          this.meta = meta || { writable: false, location: '', agents: [] };
          const wanted = preferredId
            || (this.flow.id || this.activeFlowId || (this.flows[0] && this.flows[0].id));
          if (wanted) {
            this.selectFlow(wanted);
          } else {
            this.newFlow();
          }
        } catch (error) {
          showError(error);
        } finally {
          this.loading = false;
        }
      },
      selectFlow(id) {
        const found = this.flows.find((flow) => flow.id === id);
        if (found) {
          this.loadGraph(found);
        }
      },
      loadGraph(flow) {
        this.flow = JSON.parse(JSON.stringify(flow));
        this.errors = [];
        this.graphNodes = (flow.nodes || []).map((node) => ({
          id: node.id,
          type: (node.type || 'AGENT').toLowerCase(),
          position: { x: Number(node.x) || 0, y: Number(node.y) || 0 },
          data: {
            name: node.name || '',
            agentId: node.agentId || '',
            avatar: this.agentAvatar(node.agentId),
            condition: node.condition || '',
            conditionValue: node.conditionValue || '',
            contextMode: node.contextMode || '',
          },
        }));
        this.graphEdges = (flow.edges || []).map((edge) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          sourceHandle: edge.sourceHandle || undefined,
          label: edge.sourceHandle ? (edge.sourceHandle === 'TRUE' ? '是' : '否') : undefined,
          markerEnd: VueFlowCore.MarkerType.ArrowClosed,
        }));
        this.$nextTick(() => {
          if (this.fitView) this.fitView({ padding: 0.08 });
        });
      },
      newFlow() {
        const agent = this.meta.agents.find((item) => item.status === 'ONLINE')
          || this.meta.agents[0];
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
            data: {
              name: agent.name,
              agentId: agent.id,
              avatar: agent.avatar || '🤖',
            },
          });
          edges.push(this.makeEdge('start', 'agent-1', null));
          edges.push(this.makeEdge('agent-1', 'end', null));
        } else {
          edges.push(this.makeEdge('start', 'end', null));
        }
        this.flow = { id: null, name: '新流程', description: '', nodes: [], edges: [] };
        this.graphNodes = nodes;
        this.graphEdges = edges;
        this.errors = [];
      },
      duplicateFlow() {
        const copy = JSON.parse(JSON.stringify(this.flow));
        copy.id = null;
        copy.name = (copy.name || '流程') + ' 副本';
        this.loadGraph(copy);
        this.flow.id = null;
        this.flow.name = copy.name;
        toast('已复制为新流程，保存后生效');
      },
      async saveFlow() {
        if (!this.meta.writable) {
          toast('未启用 Nacos，流程只读', 'error');
          return;
        }
        if (!this.flow.name || !this.flow.name.trim()) {
          toast('流程名称必填', 'error');
          return;
        }
        this.syncFlowFromGraph();
        this.saving = true;
        this.errors = [];
        try {
          const body = {
            name: this.flow.name,
            description: this.flow.description,
            nodes: this.flow.nodes,
            edges: this.flow.edges,
          };
          const saved = this.flow.id
            ? await api('/api/admin/flows/' + encodeURIComponent(this.flow.id), {
              method: 'PUT',
              body,
            })
            : await api('/api/admin/flows', { method: 'POST', body });
          toast('已保存');
          await this.reload(saved.id);
        } catch (error) {
          this.errors = String(error.message || error).split('；').filter(Boolean);
          showError(error);
        } finally {
          this.saving = false;
        }
      },
      async removeFlow() {
        if (!this.flow.id || !this.meta.writable) return;
        if (!window.confirm('删除流程「' + this.flow.name + '」？')) return;
        try {
          await api('/api/admin/flows/' + encodeURIComponent(this.flow.id), {
            method: 'DELETE',
          });
          toast('已删除');
          this.flow.id = null;
          await this.reload();
        } catch (error) {
          showError(error);
        }
      },
      async activateFlow() {
        if (!this.flow.id || !this.meta.writable) return;
        if (!window.confirm('设为全局默认流程会同时取消默认核算智能体，确认继续？')) return;
        this.activating = true;
        try {
          await api('/api/admin/flows/' + encodeURIComponent(this.flow.id) + '/active', {
            method: 'PUT',
          });
          toast('已设为全局默认流程，并取消默认核算智能体');
          await this.reload(this.flow.id);
        } catch (error) {
          this.errors = String(error.message || error).split('；').filter(Boolean);
          showError(error);
        } finally {
          this.activating = false;
        }
      },
      async clearActiveFlow() {
        if (!this.meta.writable || !this.activeFlowId) return;
        if (!window.confirm('取消全局默认流程？下一轮对话将回到核算智能体链路。')) return;
        this.clearing = true;
        try {
          await api('/api/admin/flows/active', { method: 'DELETE' });
          toast('已取消全局默认流程，下一轮对话生效');
          await this.reload(this.flow.id);
        } catch (error) {
          showError(error);
        } finally {
          this.clearing = false;
        }
      },
      onDragStart(event, type) {
        if (!this.meta.writable) return;
        event.dataTransfer.setData('application/x-flow-node', type);
        event.dataTransfer.effectAllowed = 'move';
      },
      onDrop(event) {
        const type = event.dataTransfer.getData('application/x-flow-node');
        if (!type || !this.meta.writable) return;
        let position = { x: 120, y: 120 };
        if (this.screenToFlowCoordinate) {
          position = this.screenToFlowCoordinate({ x: event.clientX, y: event.clientY });
        }
        this.addNodeAt(type, position);
      },
      addNode(type) {
        this.addNodeAt(type, {
          x: 120 + (this.graphNodes.length % 4) * 220,
          y: 100 + Math.floor(this.graphNodes.length / 4) * 150,
        });
      },
      addNodeAt(type, position) {
        if (!this.meta.writable) return;
        const upper = String(type || '').toUpperCase();
        if (upper === 'START' && this.graphNodes.some((node) => node.type === 'start')) {
          toast('流程只能有一个开始节点', 'error');
          return;
        }
        if (upper === 'END' && this.graphNodes.some((node) => node.type === 'end')) {
          toast('流程只能有一个结束节点', 'error');
          return;
        }
        const id = upper === 'START'
          ? 'start'
          : upper === 'END'
            ? 'end'
            : upper.toLowerCase() + '-' + Date.now().toString(36);
        if (this.graphNodes.some((node) => node.id === id)) {
          toast('节点 id 重复，请重试', 'error');
          return;
        }
        const agent = this.meta.agents.find((item) => item.status === 'ONLINE')
          || this.meta.agents[0];
        const data = {
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
        this.graphNodes.push({
          id,
          type: upper.toLowerCase(),
          position: position || { x: 120, y: 120 },
          data,
        });
      },
      onNodesChange(changes) {
        this.graphNodes = VueFlowCore.applyNodeChanges(changes, this.graphNodes);
      },
      onEdgesChange(changes) {
        this.graphEdges = VueFlowCore.applyEdgeChanges(changes, this.graphEdges);
      },
      onConnect(connection) {
        if (!this.meta.writable || !connection) return;
        const source = connection.source;
        const target = connection.target;
        const sourceHandle = connection.sourceHandle || null;
        if (!source || !target || source === target) return;
        const sourceNode = this.graphNodes.find((node) => node.id === source);
        if (sourceNode && sourceNode.type === 'condition' && !sourceHandle) {
          toast('条件节点请从“是/否”锚点连线', 'error');
          return;
        }
        const exists = this.graphEdges.some((edge) => edge.source === source
          && edge.target === target
          && (edge.sourceHandle || null) === sourceHandle);
        if (exists) {
          toast('该连线已存在', 'error');
          return;
        }
        this.graphEdges.push(this.makeEdge(source, target, sourceHandle));
      },
      makeEdge(source, target, sourceHandle) {
        return {
          id: 'e-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 6),
          source,
          target,
          sourceHandle: sourceHandle || undefined,
          label: sourceHandle ? (sourceHandle === 'TRUE' ? '是' : '否') : undefined,
          markerEnd: VueFlowCore.MarkerType.ArrowClosed,
        };
      },
      deleteSelectedNode() {
        if (!this.selectedNode || !this.meta.writable) return;
        const id = this.selectedNode.id;
        this.graphNodes = this.graphNodes.filter((node) => node.id !== id);
        this.graphEdges = this.graphEdges.filter(
          (edge) => edge.source !== id && edge.target !== id,
        );
      },
      deleteSelectedEdge() {
        if (!this.selectedEdge || !this.meta.writable) return;
        const id = this.selectedEdge.id;
        this.graphEdges = this.graphEdges.filter((edge) => edge.id !== id);
      },
      applyAgentDefaults(node) {
        const agent = this.meta.agents.find((item) => item.id === node.data.agentId);
        if (agent) {
          node.data.name = agent.name;
          node.data.avatar = agent.avatar || '🤖';
        }
      },
      agentAvatar(agentId) {
        const agent = this.meta.agents.find((item) => item.id === agentId);
        return agent ? (agent.avatar || '🤖') : '🤖';
      },
      avatarMarkup(value) {
        return window.payrollAvatarMarkup
          ? window.payrollAvatarMarkup(value)
          : (value || '🤖');
      },
      conditionText(condition) {
        return {
          HAS_CALCULATION_TARGETS: '有核算对象',
          HAS_TOOL_ERROR: '上游工具报错',
          OUTPUT_CONTAINS: '输出包含关键字',
        }[condition] || condition || '未设置条件';
      },
      syncFlowFromGraph() {
        const typeMap = {
          start: 'START', agent: 'AGENT', condition: 'CONDITION', end: 'END',
        };
        this.flow.nodes = this.graphNodes.map((node) => ({
          id: node.id,
          type: typeMap[node.type] || 'AGENT',
          name: (node.data && node.data.name) || '',
          agentId: node.type === 'agent' ? ((node.data && node.data.agentId) || '') : '',
          condition: node.type === 'condition' ? ((node.data && node.data.condition) || '') : '',
          conditionValue: node.type === 'condition'
            ? ((node.data && node.data.conditionValue) || '')
            : '',
          contextMode: node.type === 'agent'
            ? ((node.data && node.data.contextMode) || null)
            : null,
          x: node.position.x,
          y: node.position.y,
        }));
        this.flow.edges = this.graphEdges.map((edge) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          sourceHandle: edge.sourceHandle || null,
        }));
      },
    },
    template: `
      <div class="flows-page">
        <aside class="flow-list card">
          <div class="panel-head">
            <h2>流程列表</h2>
            <button type="button" class="tiny" @click="reload()" :disabled="loading">刷新</button>
          </div>
          <button type="button" class="primary flow-new" @click="newFlow" :disabled="!meta.writable">＋ 新建流程</button>
          <p v-if="loading" class="hint">加载中…</p>
          <div v-for="item in flows" :key="item.id" class="flow-item"
               :class="{ active: item.id === flow.id }" @click="selectFlow(item.id)">
            <div class="flow-item-head">
              <strong>{{ item.name }}</strong>
              <span v-if="item.id === activeFlowId" class="flow-badge">默认</span>
            </div>
            <p class="hint">{{ (item.nodes || []).length }} 节点 · {{ (item.edges || []).length }} 连线</p>
          </div>
          <p class="hint flow-storage">{{ storageText }}</p>
        </aside>

        <section class="flow-main card">
          <div class="flow-toolbar">
            <input v-model="flow.name" class="flow-name" maxlength="40" placeholder="流程名称"
                   :disabled="!meta.writable">
            <button type="button" class="primary" @click="saveFlow" :disabled="!meta.writable || saving">
              {{ saving ? '保存中…' : '保存' }}
            </button>
            <button type="button" @click="duplicateFlow" :disabled="!meta.writable">复制</button>
            <button type="button" @click="activateFlow"
                    :disabled="!meta.writable || !flow.id || flow.id === activeFlowId || activating">
              {{ flow.id === activeFlowId ? '当前默认' : '设为默认' }}
            </button>
            <button type="button" @click="clearActiveFlow"
                    :disabled="!meta.writable || !activeFlowId || clearing">
              {{ clearing ? '取消中…' : '取消默认' }}
            </button>
            <button type="button" @click="zoomOut">－</button>
            <button type="button" @click="zoomIn">＋</button>
            <button type="button" @click="fitView({ padding: 0.08 })">适应画布</button>
            <button type="button" class="danger" @click="removeFlow"
                    :disabled="!meta.writable || !flow.id">删除</button>
          </div>
          <p v-if="!meta.writable" class="hint">未启用 Nacos：展示内置标准流程，保存与激活已禁用。</p>
          <div v-if="errors.length" class="flow-errors">
            <p v-for="(item, index) in errors" :key="index">{{ item }}</p>
          </div>
          <div class="flow-canvas-wrap" @dragover.prevent @drop="onDrop">
            <div class="flow-palette">
              <div v-for="item in palette" :key="item.type" class="flow-palette-item"
                   :draggable="meta.writable" @dragstart="onDragStart($event, item.type)"
                   @click="addNode(item.type)">
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
              @connect="onConnect">
              <template #node-start="{ data }">
                <Handle type="source" :position="Position.Right" />
                <div class="flow-node flow-start">▶ {{ data.name }}</div>
              </template>
              <template #node-agent="{ data }">
                <Handle type="target" :position="Position.Left" />
                <div class="flow-node flow-agent">
                  <span class="flow-avatar" v-html="avatarMarkup(data.avatar)"></span>
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
          <label class="flow-desc">流程说明
            <textarea v-model="flow.description" rows="2" maxlength="200"
                      :disabled="!meta.writable"></textarea>
          </label>
        </section>

        <aside class="flow-props card">
          <h2>节点属性</h2>
          <template v-if="selectedNode">
            <label>名称<input v-model="selectedNode.data.name" maxlength="30"></label>
            <template v-if="selectedNode.type === 'agent'">
              <label>绑定智能体
                <select v-model="selectedNode.data.agentId" @change="applyAgentDefaults(selectedNode)">
                  <option v-for="item in meta.agents" :key="item.id" :value="item.id">
                    {{ item.name }}{{ item.status === 'ONLINE' ? '' : '（未上架）' }}
                  </option>
                </select>
              </label>
              <label>上游记录
                <select v-model="selectedNode.data.contextMode">
                  <option value="">自动（仅数据流水线串联）</option>
                  <option value="UPSTREAM">接收上游记录</option>
                  <option value="NONE">不接收上游记录</option>
                </select>
              </label>
              <p class="hint">自动模式只把带核算对象的上游记录传给下游；纯文本角色默认隔离，跨角色串联需显式选择“接收上游记录”。</p>
            </template>
            <template v-if="selectedNode.type === 'condition'">
              <label>条件
                <select v-model="selectedNode.data.condition">
                  <option value="HAS_CALCULATION_TARGETS">有核算对象</option>
                  <option value="HAS_TOOL_ERROR">上游工具报错</option>
                  <option value="OUTPUT_CONTAINS">输出包含关键字</option>
                </select>
              </label>
              <label v-if="selectedNode.data.condition === 'OUTPUT_CONTAINS'">关键字
                <input v-model="selectedNode.data.conditionValue" maxlength="40">
              </label>
            </template>
            <button type="button" class="danger" @click="deleteSelectedNode"
                    :disabled="!meta.writable">删除节点</button>
          </template>
          <template v-else-if="selectedEdge">
            <p class="hint">{{ selectedEdge.source }} → {{ selectedEdge.target }}
              <span v-if="selectedEdge.sourceHandle">（{{ selectedEdge.sourceHandle === 'TRUE' ? '是' : '否' }}）</span>
            </p>
            <button type="button" class="danger" @click="deleteSelectedEdge"
                    :disabled="!meta.writable">删除连线</button>
          </template>
          <p v-else class="hint">点击节点或连线查看属性</p>
          <hr>
          <p class="hint">{{ activeText }}</p>
          <p class="hint">条件节点有“是/否”两个输出锚点；智能体节点可连接多个下游并行执行。</p>
        </aside>
      </div>
    `,
  };

  const app = createApp(App);
  app.component('VueFlow', VueFlowCore.VueFlow);
  app.component('Handle', VueFlowCore.Handle);
  flowsVm = app.mount('#flows-app');
  flowsVm.reload();
}

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const data = ref({
  revision: -1,
  defaults: { control: '', coding: '', utility: '' },
  connections: [],
  writable: false,
  envCodingLocked: false,
  secretKeyAvailable: false,
});
const loading = ref(false);
const showForm = ref(false);
const editing = ref(false);
const saving = ref(false);
const testing = ref('');
const form = reactive(blank());
const defaults = reactive({ control: '', coding: '', utility: '' });
const testResults = reactive({});

const providerLabels = {
  ollama: 'Ollama',
  dashscope: 'DashScope',
  'openai-compatible': 'OpenAI 兼容',
};

const providerHints = {
  ollama: '接入地址留空时使用 OLLAMA_BASE_URL 或 http://localhost:11434；模型需先 ollama pull',
  dashscope: '默认走 DashScope 官方端点；填写 baseUrl 可指向兼容代理',
  'openai-compatible': '必须填写接入地址，例如 https://api.deepseek.com/v1',
};

const connections = computed(() => data.value.connections || []);
const selectable = computed(() => connections.value.filter((item) => item.enabled));
const editingEnv = computed(() => editing.value && form.origin === 'ENV');

const planeLabels = { control: '控制面', coding: '编码面', utility: '轻量面' };

function defaultPlanes(id) {
  if (!id) return [];
  const current = data.value.defaults || {};
  return Object.keys(planeLabels)
    .filter((plane) => current[plane] === id)
    .map((plane) => planeLabels[plane]);
}

function staleDefault(id) {
  return !!id && !selectable.value.some((item) => item.id === id);
}

const staleDefaults = computed(() => {
  const current = data.value.defaults || {};
  return Object.keys(planeLabels)
    .filter((plane) => staleDefault(current[plane]))
    .map((plane) => `${planeLabels[plane]}（${connectionName(current[plane])}）`);
});

function blank() {
  return {
    id: '',
    name: '',
    provider: 'ollama',
    baseUrl: '',
    model: '',
    apiKeyEnv: '',
    apiKey: '',
    credentialSource: 'none',
    contextWindow: '',
    maxTokens: '',
    temperature: '',
    timeoutSeconds: '',
    capabilitiesText: 'tools,stream',
    enabled: true,
    origin: 'STORED',
  };
}

function formatTime(value) {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 16);
}

function sourceLabel(row) {
  if (row.credentialSource === 'stored') return '已加密保存';
  if (row.credentialSource === 'env') return row.apiKeyEnv || '环境变量';
  if (row.credentialSource === 'none') return '无需密钥';
  return '未设置';
}

function sourceClass(row) {
  if (row.credentialSource === 'stored') return 'green';
  if (row.credentialSource === 'env') return row.apiKeyEnvPresent ? 'blue' : 'orange';
  if (row.credentialSource === 'none') return 'blue';
  return 'orange';
}

function connectionName(id) {
  if (!id) return '未配置';
  const hit = connections.value.find((item) => item.id === id);
  if (!hit) return id;
  return `${hit.name}（${hit.model || '未填模型'}）${hit.enabled ? '' : ' · 已停用'}`;
}

async function load() {
  loading.value = true;
  try {
    data.value = (await api('/api/admin/model-connections')) || data.value;
    defaults.control = data.value.defaults?.control || '';
    defaults.coding = data.value.defaults?.coding || '';
    defaults.utility = data.value.defaults?.utility || '';
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function createConnection() {
  editing.value = false;
  Object.assign(form, blank());
  if (!data.value.secretKeyAvailable) form.credentialSource = 'env';
  showForm.value = true;
}

function copyConnection(row) {
  editing.value = false;
  Object.assign(form, blank(), {
    name: `${row.name}（副本）`,
    provider: row.provider,
    baseUrl: row.baseUrl,
    model: row.model,
    contextWindow: row.contextWindow ?? '',
    maxTokens: row.maxTokens ?? '',
    temperature: row.temperature ?? '',
    timeoutSeconds: row.timeoutSeconds ?? '',
    capabilitiesText: (row.capabilities || []).join(','),
    enabled: row.enabled,
    credentialSource: row.credentialSource === 'stored' ? 'env' : row.credentialSource,
    apiKeyEnv: row.apiKeyEnv || '',
  });
  showForm.value = true;
}

function editConnection(row) {
  editing.value = true;
  Object.assign(form, blank(), {
    id: row.id,
    name: row.name,
    provider: row.provider,
    baseUrl: row.baseUrl,
    model: row.model,
    apiKeyEnv: row.apiKeyEnv || '',
    apiKey: '',
    credentialSource:
      row.credentialSource === 'unset' ? 'none' : row.credentialSource || 'none',
    contextWindow: row.contextWindow ?? '',
    maxTokens: row.maxTokens ?? '',
    temperature: row.temperature ?? '',
    timeoutSeconds: row.timeoutSeconds ?? '',
    capabilitiesText: (row.capabilities || []).join(','),
    enabled: row.enabled,
    origin: row.origin,
    credentialStored: row.credentialStored,
    credentialCreatedAt: row.credentialCreatedAt,
  });
  showForm.value = true;
}

function onProviderChange() {
  if (form.provider === 'ollama') {
    form.credentialSource = 'none';
    form.apiKeyEnv = '';
    form.apiKey = '';
  } else if (form.credentialSource === 'none') {
    form.credentialSource = data.value.secretKeyAvailable ? 'stored' : 'env';
  }
}

function numberOrNull(value) {
  const text = String(value ?? '').trim();
  if (!text) return null;
  const numeric = Number(text);
  return Number.isFinite(numeric) ? numeric : null;
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    const body = {
      id: form.id.trim(),
      name: form.name.trim(),
      provider: form.provider,
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKeyEnv: form.credentialSource === 'env' ? form.apiKeyEnv.trim() : '',
      contextWindow: numberOrNull(form.contextWindow),
      maxTokens: numberOrNull(form.maxTokens),
      temperature: numberOrNull(form.temperature),
      timeoutSeconds: numberOrNull(form.timeoutSeconds),
      capabilities: form.capabilitiesText
        .split(/[,，\s]+/)
        .map((item) => item.trim())
        .filter(Boolean),
      enabled: form.enabled,
      revision: data.value.revision,
    };
    if (form.credentialSource === 'stored' && form.apiKey.trim()) {
      body.apiKey = form.apiKey.trim();
    }
    if (editing.value) {
      delete body.id;
      await api(`/api/admin/model-connections/${encodeURIComponent(form.id)}`, {
        method: 'PUT',
        body,
      });
    } else {
      await api('/api/admin/model-connections', { method: 'POST', body });
    }
    toast('模型连接已保存', 'success');
    showForm.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function clearCredential(row) {
  if (!window.confirm(`清除连接「${row.name}」已保存的密钥？`)) return;
  try {
    await api(
      `/api/admin/model-connections/${encodeURIComponent(row.id)}/credential?revision=${data.value.revision}`,
      { method: 'DELETE' }
    );
    toast('密钥已清除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function removeConnection(row) {
  if (!window.confirm(`删除连接「${row.name}」？仍被智能体引用时会被拒绝。`)) return;
  try {
    await api(
      `/api/admin/model-connections/${encodeURIComponent(row.id)}?revision=${data.value.revision}`,
      { method: 'DELETE' }
    );
    toast('连接已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function testConnection(row) {
  testing.value = row.id;
  try {
    const probe = await api(`/api/admin/model-connections/${encodeURIComponent(row.id)}/test`, {
      method: 'POST',
    });
    testResults[row.id] = probe;
    if (probe.ok) {
      toast(
        `连通正常：${probe.elapsedMillis} ms${probe.capabilities?.length ? `，能力 ${probe.capabilities.join('/')}` : ''}`,
        'success'
      );
    } else {
      toast(`测试失败（${probe.code}）：${probe.message}`, 'error');
    }
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    testing.value = '';
  }
}

async function saveDefaults() {
  try {
    await api('/api/admin/model-connections/defaults', {
      method: 'PUT',
      body: {
        control: defaults.control,
        coding: defaults.coding,
        utility: defaults.utility,
        revision: data.value.revision,
      },
    });
    toast('默认模型已更新', 'success');
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
        <h2>模型配置</h2>
        <p>连接元数据存 Nacos，页面录入的密钥加密入库；控制面 / 编码面 / 轻量面可分别指定默认连接。</p>
      </div>
      <div class="toolbar">
        <span class="badge" :class="data.writable ? 'green' : 'orange'">
          {{ data.writable ? '可写' : '只读（Nacos 未启用）' }}
        </span>
        <span class="badge" :class="data.secretKeyAvailable ? 'green' : 'orange'">
          {{ data.secretKeyAvailable ? '密钥加密可用' : '未配置 PAYROLL_SECRET_KEY' }}
        </span>
        <button class="primary" :disabled="!data.writable" @click="createConnection">新增连接</button>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <h3>使用面默认连接</h3>
        <small>控制面 = 对话 / 团队 / 流程，轻量面 = 意图与计划，编码面 = 研发域</small>
      </div>
      <div class="form-grid">
        <label class="field">
          控制面
          <select v-model="defaults.control" :disabled="!data.writable">
            <option value="">跟随环境变量（PAYROLL_MODEL）</option>
            <option v-if="staleDefault(defaults.control)" :value="defaults.control" disabled>
              {{ connectionName(defaults.control) }}（请先切换）
            </option>
            <option v-for="item in selectable" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.model }}
            </option>
          </select>
          <span class="hint">当前：{{ connectionName(data.defaults.control) }}</span>
        </label>
        <label class="field">
          轻量面
          <select v-model="defaults.utility" :disabled="!data.writable">
            <option value="">跟随控制面</option>
            <option v-if="staleDefault(defaults.utility)" :value="defaults.utility" disabled>
              {{ connectionName(defaults.utility) }}（请先切换）
            </option>
            <option v-for="item in selectable" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.model }}
            </option>
          </select>
          <span class="hint">当前：{{ connectionName(data.defaults.utility) }}</span>
        </label>
        <label class="field">
          编码面
          <select v-model="defaults.coding" :disabled="!data.writable || data.envCodingLocked">
            <option value="">未配置（写代码任务将失败）</option>
            <option v-if="staleDefault(defaults.coding)" :value="defaults.coding" disabled>
              {{ connectionName(defaults.coding) }}（请先切换）
            </option>
            <option v-for="item in selectable" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.model }}
            </option>
          </select>
          <span v-if="data.envCodingLocked" class="badge orange">环境变量锁定（PAYROLL_DEV_MODEL）</span>
          <span v-else class="hint">需上下文 ≥ 32768 且声明 tools</span>
        </label>
      </div>
      <p v-if="staleDefaults.length" class="error-text">
        仍被使用面引用的停用连接：{{ staleDefaults.join('、') }}。请先切换到启用中的连接，否则对应请求会直接报错。
      </p>
      <div class="toolbar">
        <span class="spacer"></span>
        <button class="ghost" :disabled="!data.writable" @click="saveDefaults">保存默认</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>提供方</th>
            <th>模型</th>
            <th>接入地址</th>
            <th>密钥来源</th>
            <th>能力</th>
            <th>状态</th>
            <th>最近测试</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in connections"
            :key="row.id"
            :class="{ 'row-warn': !row.enabled && defaultPlanes(row.id).length }"
          >
            <td>
              <strong>{{ row.name }}</strong>
              <div class="hint mono">{{ row.id }}</div>
              <span v-if="row.origin === 'ENV'" class="badge blue">环境变量只读</span>
            </td>
            <td>{{ providerLabels[row.provider] || row.provider }}</td>
            <td class="mono">{{ row.model || '—' }}</td>
            <td class="mono muted">{{ row.baseUrl || '（默认）' }}</td>
            <td>
              <span class="badge" :class="sourceClass(row)">{{ sourceLabel(row) }}</span>
              <div v-if="row.credentialStored" class="hint">
                录入 {{ formatTime(row.credentialCreatedAt) }}
                <template v-if="row.credentialLastUsedAt">
                  · 最近使用 {{ formatTime(row.credentialLastUsedAt) }}
                </template>
              </div>
            </td>
            <td>
              <span v-for="cap in row.capabilities || []" :key="cap" class="badge">{{ cap }}</span>
            </td>
            <td>
              <span class="badge" :class="row.enabled ? 'green' : 'red'">
                {{ row.enabled ? '启用' : '停用' }}
              </span>
              <span
                v-if="!row.enabled && defaultPlanes(row.id).length"
                class="badge red"
                :title="`仍是${defaultPlanes(row.id).join('、')}的默认连接`"
              >
                仍是默认
              </span>
            </td>
            <td>
              <template v-if="testResults[row.id]">
                <span class="badge" :class="testResults[row.id].ok ? 'green' : 'red'">
                  {{ testResults[row.id].ok ? '通过' : testResults[row.id].code }}
                </span>
                <span class="hint">{{ testResults[row.id].elapsedMillis }} ms</span>
              </template>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" :disabled="testing === row.id" @click="testConnection(row)">
                  {{ testing === row.id ? '测试中…' : '测试' }}
                </button>
                <button v-if="row.origin === 'ENV'" class="subtle sm" @click="copyConnection(row)">
                  复制为可编辑
                </button>
                <template v-else>
                  <button class="subtle sm" :disabled="!data.writable" @click="editConnection(row)">
                    编辑
                  </button>
                  <button
                    v-if="row.credentialSource === 'stored'"
                    class="subtle sm"
                    :disabled="!data.writable"
                    @click="clearCredential(row)"
                  >
                    清除密钥
                  </button>
                  <button
                    class="subtle sm"
                    :disabled="!data.writable"
                    @click="removeConnection(row)"
                  >
                    删除
                  </button>
                </template>
              </div>
            </td>
          </tr>
          <tr v-if="!connections.length">
            <td colspan="9" class="empty">{{ loading ? '加载中…' : '暂无模型连接' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width: min(760px, 96vw)">
        <div class="modal-head">
          <h3>{{ editing ? `编辑连接 ${form.id}` : '新增模型连接' }}</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <p v-if="editingEnv" class="hint">
            该连接由环境变量派生，只读；请关闭后使用「复制为可编辑」再修改。
          </p>
          <div class="form-grid">
            <label class="field">
              连接 ID
              <input v-model="form.id" :disabled="editing" placeholder="deepseek-chat" />
            </label>
            <label class="field">
              名称
              <input v-model="form.name" placeholder="DeepSeek 对话" />
            </label>
            <label class="field">
              提供方
              <select v-model="form.provider" :disabled="editingEnv" @change="onProviderChange">
                <option value="ollama">Ollama</option>
                <option value="dashscope">DashScope</option>
                <option value="openai-compatible">OpenAI 兼容</option>
              </select>
              <span class="hint">{{ providerHints[form.provider] }}</span>
            </label>
            <label class="field">
              模型名
              <input v-model="form.model" :disabled="editingEnv" placeholder="qwen2.5:3b-instruct" />
            </label>
            <label class="field span-2">
              接入地址
              <input v-model="form.baseUrl" :disabled="editingEnv" placeholder="https://api.deepseek.com/v1" />
            </label>
            <label class="field">
              上下文（tokens，可选）
              <input v-model="form.contextWindow" :disabled="editingEnv" placeholder="32768" />
            </label>
            <label class="field">
              最大输出（tokens，可选）
              <input v-model="form.maxTokens" :disabled="editingEnv" placeholder="2048" />
            </label>
            <label class="field">
              温度（0–2，可选）
              <input v-model="form.temperature" :disabled="editingEnv" placeholder="0.1" />
            </label>
            <label class="field">
              超时（秒，可选）
              <input v-model="form.timeoutSeconds" :disabled="editingEnv" placeholder="120" />
            </label>
            <label class="field span-2">
              能力标签（逗号分隔）
              <input v-model="form.capabilitiesText" :disabled="editingEnv" placeholder="tools,stream" />
            </label>
            <label class="field span-2">
              <span>
                <input v-model="form.enabled" type="checkbox" :disabled="editingEnv" />
                启用该连接
              </span>
            </label>
          </div>

          <template v-if="form.provider !== 'ollama' && !editingEnv">
            <div class="muted" style="margin: 12px 0 6px">API Key</div>
            <div class="toolbar">
              <label class="chip">
                <input v-model="form.credentialSource" type="radio" value="env" />
                引用环境变量
              </label>
              <label class="chip">
                <input
                  v-model="form.credentialSource"
                  type="radio"
                  value="stored"
                  :disabled="!data.secretKeyAvailable"
                />
                在页面录入
              </label>
              <label class="chip">
                <input v-model="form.credentialSource" type="radio" value="none" />
                暂不设置
              </label>
            </div>
            <div v-if="form.credentialSource === 'env'" class="form-grid">
              <label class="field span-2">
                环境变量名
                <input v-model="form.apiKeyEnv" placeholder="DEEPSEEK_API_KEY" />
                <span class="hint">只保存变量名，不读取值；服务端启动时需已配置该变量。</span>
              </label>
            </div>
            <div v-else-if="form.credentialSource === 'stored'" class="form-grid">
              <label class="field span-2">
                {{ form.credentialStored ? '替换密钥（留空保持原值）' : 'API Key（加密保存）' }}
                <input v-model="form.apiKey" type="password" autocomplete="new-password" placeholder="sk-…" />
                <span v-if="!data.secretKeyAvailable" class="hint">
                  未配置 PAYROLL_SECRET_KEY，页面录入不可用：{{ data.secretKeyUnavailableReason }}
                </span>
                <span v-else-if="form.credentialStored" class="hint">
                  已加密保存，录入于 {{ formatTime(form.credentialCreatedAt) }}；已存密钥不会回显。
                </span>
              </label>
            </div>
          </template>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showForm = false">取消</button>
          <button class="primary" :disabled="saving || editingEnv" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const rows = ref([]);
const meta = ref({ writable: false, location: '-' });
const skillOptions = ref([]);
const skillKeyword = ref('');
const SKILL_CHIP_LIMIT = 60;
const modelOptions = ref([]);
const modelMeta = ref({ secretKeyAvailable: false, envCodingLocked: false });
const active = ref({ agentId: '', name: '', configured: '', available: true });
const stats = ref({ window_days: 0, record_count: 0, feedback_count: 0, agents: [] });
const keyword = ref('');
const statusFilter = ref('');
const visibilityFilter = ref('');
const loading = ref(false);
const showForm = ref(false);
const editing = ref(false);
const saving = ref(false);
const form = reactive(blank());

const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  return rows.value.filter((row) => {
    if (statusFilter.value && row.status !== statusFilter.value) return false;
    if (visibilityFilter.value && row.visibility !== visibilityFilter.value) return false;
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
    inheritModel: true,
    modelConnectionId: '',
    modelName: '',
    modelNumCtx: '',
    modelNumPredict: '',
    modelTemperature: '',
    modelTimeoutSeconds: '',
  };
}

// 技能规模化后不再一次渲染全部条目：已选技能置顶，其余按关键字过滤后限量展示。
const visibleSkillOptions = computed(() => {
  const needle = skillKeyword.value.trim().toLowerCase();
  const selected = new Set(form.skillIds || []);
  const matched = skillOptions.value.filter((skill) => {
    if (!needle) return true;
    return [skill.id, skill.description]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle));
  });
  const pinned = matched.filter((skill) => selected.has(skill.id));
  const rest = matched.filter((skill) => !selected.has(skill.id));
  return [...pinned, ...rest].slice(0, SKILL_CHIP_LIMIT);
});

const hiddenSkillCount = computed(() => {
  const shown = new Set(visibleSkillOptions.value.map((skill) => skill.id));
  return skillOptions.value.filter((skill) => !shown.has(skill.id)).length;
});

async function load() {
  loading.value = true;
  try {
    const [agents, metaValue, skills, activeValue, statsValue, models] = await Promise.all([
      api('/api/admin/agents'),
      api('/api/admin/agents/meta'),
      api('/api/admin/agents/skills'),
      api('/api/admin/agents/active'),
      api('/api/admin/agent-stats'),
      api('/api/admin/model-connections').catch(() => null),
    ]);
    rows.value = agents || [];
    meta.value = metaValue || meta.value;
    skillOptions.value = skills || [];
    active.value = activeValue || active.value;
    stats.value = statsValue || stats.value;
    if (models) {
      modelOptions.value = (models.connections || []).filter((item) => item.enabled);
      modelMeta.value = {
        secretKeyAvailable: Boolean(models.secretKeyAvailable),
        envCodingLocked: Boolean(models.envCodingLocked),
      };
    }
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function createAgent() {
  editing.value = false;
  skillKeyword.value = '';
  Object.assign(form, blank());
  showForm.value = true;
}

function editAgent(row) {
  const model = row.model || {};
  editing.value = true;
  skillKeyword.value = '';
  Object.assign(form, {
    ...blank(),
    ...row,
    tagsText: (row.tags || []).join(', '),
    skillIds: [...(row.skill_ids || row.skillIds || [])],
    inheritModel: !model.connectionId,
    modelConnectionId: model.connectionId || '',
    modelName: model.model || '',
    modelNumCtx: model.numCtx ?? '',
    modelNumPredict: model.numPredict ?? '',
    modelTemperature: model.temperature ?? '',
    modelTimeoutSeconds: model.timeoutSeconds ?? '',
  });
  showForm.value = true;
}

function modelSummary(row) {
  const model = row.model || {};
  if (!model.connectionId) return '继承控制面默认';
  const hit = modelOptions.value.find((item) => item.id === model.connectionId);
  const name = hit ? hit.name : model.connectionId;
  return model.model ? `${name} · ${model.model}` : name;
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
      model: form.inheritModel
        ? null
        : {
            connectionId: form.modelConnectionId,
            model: form.modelName.trim(),
            numCtx: numberOrNull(form.modelNumCtx),
            numPredict: numberOrNull(form.modelNumPredict),
            temperature: numberOrNull(form.modelTemperature),
            timeoutSeconds: numberOrNull(form.modelTimeoutSeconds),
          },
    };
    if (editing.value) {
      await api(
        `/api/admin/agents/${encodeURIComponent(body.id)}?clearModel=${form.inheritModel}`,
        { method: 'PUT', body }
      );
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

function numberOrNull(value) {
  const text = String(value ?? '').trim();
  if (!text) return null;
  const numeric = Number(text);
  return Number.isFinite(numeric) ? numeric : null;
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

function skillCount(row) {
  return (row.skill_ids || row.skillIds || []).length;
}

function visibilityLabel(value) {
  return { PUBLIC: '公开', PRIVATE: '私有' }[value] || value;
}

function statRows() {
  return stats.value.agents || [];
}

function numberText(value, digits) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric.toFixed(digits) : '—';
}

function percentText(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? `${(numeric * 100).toFixed(1)}%` : '—';
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
        <select v-model="visibilityFilter" style="width: 120px">
          <option value="">全部可见性</option>
          <option value="PUBLIC">公开</option>
          <option value="PRIVATE">私有</option>
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
        <span v-if="!active.available" class="error-text">配置的智能体不可用，已阻断回退流水线</span>
        <span class="spacer"></span>
        <button class="ghost sm" :disabled="!active.configured" @click="clearActive">取消全局智能体</button>
      </div>
    </div>

    <p v-if="loading && !rows.length" class="hint">加载中…</p>
    <p v-else-if="!filtered.length" class="empty">没有符合条件的智能体</p>
    <div v-else class="agent-grid">
      <article v-for="row in filtered" :key="row.id" class="agent-card">
        <div class="agent-card-head">
          <span class="agent-avatar">{{ row.avatar || '🤖' }}</span>
          <div class="agent-title">
            <strong>{{ row.name }}</strong>
            <span class="hint mono">{{ row.id }}</span>
          </div>
          <span v-if="active.agentId === row.id" class="badge blue">当前核算</span>
        </div>
        <div class="agent-tags">
          <span v-for="tag in row.tags || []" :key="tag" class="tag">{{ tag }}</span>
          <span class="tag">技能 {{ skillCount(row) }}</span>
          <span class="tag">模型 {{ modelSummary(row) }}</span>
        </div>
        <p class="agent-desc">{{ row.description || '（无描述）' }}</p>
        <div class="agent-card-foot">
          <span class="badge" :class="row.status === 'ONLINE' ? 'green' : 'red'">
            {{ row.status === 'ONLINE' ? '在线' : '离线' }}
          </span>
          <span class="badge">{{ visibilityLabel(row.visibility) }}</span>
          <span class="spacer"></span>
          <div class="cell-actions">
            <button
              class="subtle sm"
              :disabled="!meta.writable || row.status !== 'ONLINE' || active.agentId === row.id"
              @click="activate(row)"
            >
              设为全局
            </button>
            <button class="subtle sm" :disabled="!meta.writable" @click="editAgent(row)">编辑</button>
            <button class="subtle sm" :disabled="!meta.writable" @click="remove(row)">删除</button>
          </div>
        </div>
      </article>
    </div>

    <div class="card">
      <div class="card-title">
        <h3>运行评分榜</h3>
        <small>
          近 {{ stats.window_days || 0 }} 天 · 轨迹 {{ stats.record_count || 0 }} · 反馈
          {{ stats.feedback_count || 0 }}
        </small>
      </div>
      <p class="hint">
        综合分 = 客观轨迹 60% + 用户反馈 40%，再按样本量向角色先验收缩；样本少于 5 条标记冷启动。
      </p>
      <div v-if="!statRows().length" class="empty">还没有运行样本</div>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>智能体</th>
              <th>样本</th>
              <th>综合分</th>
              <th>客观分 O</th>
              <th>反馈分 F</th>
              <th>成功率</th>
              <th>P50 时延</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in statRows()" :key="row.agent_id">
              <td>
                {{ row.name }}
                <span v-if="row.cold_start" class="badge orange">冷启动</span>
                <span v-if="row.name !== row.agent_id" class="hint mono">{{ row.agent_id }}</span>
              </td>
              <td>{{ row.samples }}</td>
              <td>{{ numberText(row.final_score, 3) }}</td>
              <td>{{ numberText(row.objective_score, 3) }}</td>
              <td>{{ numberText(row.feedback_score, 3) }}</td>
              <td>{{ row.samples ? percentText(row.success_rate) : '—' }}</td>
              <td>{{ row.p50_latency_ms ? `${row.p50_latency_ms} ms` : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
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
            <div class="muted" style="margin-bottom: 6px">绑定技能（已选 {{ form.skillIds.length }}）</div>
            <input
              v-model="skillKeyword"
              placeholder="搜索技能 ID / 说明"
              style="width: 260px; margin-bottom: 6px"
            />
            <div class="toolbar">
              <button
                v-for="skill in visibleSkillOptions"
                :key="skill.id"
                class="chip"
                :class="{ 'badge blue': form.skillIds.includes(skill.id) }"
                @click="toggleSkill(skill.id)"
              >
                {{ skill.id }}
              </button>
              <span v-if="!skillOptions.length" class="muted">暂无可绑定技能</span>
              <span v-else-if="hiddenSkillCount" class="muted">
                还有 {{ hiddenSkillCount }} 个未展示，请输入关键字继续过滤
              </span>
            </div>
          </div>
          <div>
            <div class="muted" style="margin-bottom: 6px">模型</div>
            <div class="form-grid">
              <label class="field span-2">
                绑定方式
                <select v-model="form.inheritModel">
                  <option :value="true">继承控制面默认（全局默认 / 流程设置）</option>
                  <option :value="false">指定模型连接</option>
                </select>
              </label>
              <label v-if="!form.inheritModel" class="field span-2">
                模型连接
                <select v-model="form.modelConnectionId">
                  <option value="">（沿用默认连接）</option>
                  <option v-for="item in modelOptions" :key="item.id" :value="item.id">
                    {{ item.name }}（{{ item.provider }} · {{ item.model }}）
                  </option>
                </select>
              </label>
              <template v-if="!form.inheritModel">
                <label class="field">
                  模型名覆盖
                  <input v-model="form.modelName" placeholder="留空则沿用连接" />
                </label>
                <label class="field">
                  上下文 numCtx
                  <input v-model="form.modelNumCtx" type="number" min="1" placeholder="沿用连接" />
                </label>
                <label class="field">
                  最大输出 numPredict
                  <input
                    v-model="form.modelNumPredict"
                    type="number"
                    min="1"
                    placeholder="沿用连接"
                  />
                </label>
                <label class="field">
                  温度
                  <input
                    v-model="form.modelTemperature"
                    type="number"
                    step="0.1"
                    min="0"
                    max="2"
                    placeholder="沿用连接"
                  />
                </label>
                <label class="field">
                  超时（秒）
                  <input
                    v-model="form.modelTimeoutSeconds"
                    type="number"
                    min="1"
                    placeholder="沿用连接"
                  />
                </label>
              </template>
            </div>
            <p class="hint" style="margin-top: 6px">
              仅作用于对话 / 团队 / 流程（控制面）；编码面由环境变量锁定，不在此配置。留空的覆盖项继承连接默认值。
            </p>
            <p v-if="!modelOptions.length" class="hint">
              还没有可用的模型连接，请先在「模型配置」中新增。
            </p>
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

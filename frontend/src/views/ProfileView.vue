<script setup>
import { computed, onMounted, ref } from 'vue';
import { api } from '../api/http';
import { toast } from '../stores/toast';

const items = ref([]);
const loading = ref(false);
const busyId = ref(0);
const clearing = ref(false);
const blocked = ref('');

const CATEGORY_LABELS = {
  ROLE: '职责',
  SCOPE: '习惯',
  PREFERENCE: '口径',
  TOPIC: '关注',
};

const SOURCE_LABELS = {
  RULE: '规则识别',
  MODEL: '模型识别',
  MANUAL: '手动设置',
};

const STATUS = {
  ACTIVE: { label: '生效中', tone: 'green' },
  STALE: { label: '已过期', tone: 'orange' },
  DISABLED: { label: '已停用', tone: 'red' },
};

const activeCount = computed(() => items.value.filter((item) => item.status === 'ACTIVE').length);

function categoryLabel(category) {
  return CATEGORY_LABELS[category] || category;
}

function sourceLabel(source) {
  return SOURCE_LABELS[source] || source || '—';
}

function statusMeta(status) {
  return STATUS[status] || { label: status || '—', tone: '' };
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—';
}

async function load() {
  loading.value = true;
  blocked.value = '';
  try {
    const data = (await api('/api/profile')) || {};
    items.value = data.items || [];
  } catch (error) {
    blocked.value = error.message;
    items.value = [];
  } finally {
    loading.value = false;
  }
}

async function toggle(item) {
  if (busyId.value) return;
  const next = item.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED';
  busyId.value = item.item_id;
  try {
    const saved = await api(`/api/profile/items/${item.item_id}`, {
      method: 'PUT',
      body: { status: next },
    });
    const index = items.value.findIndex((row) => row.item_id === item.item_id);
    if (index >= 0 && saved) items.value.splice(index, 1, saved);
    toast(next === 'DISABLED' ? '已停用，下一轮对话不再使用该偏好' : '已启用', 'success');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    busyId.value = 0;
  }
}

async function remove(item) {
  if (busyId.value) return;
  if (!window.confirm(`删除偏好「${item.label}：${item.item_value}」？删除后不再注入，审计只保留值哈希。`)) {
    return;
  }
  busyId.value = item.item_id;
  try {
    await api(`/api/profile/items/${item.item_id}`, { method: 'DELETE' });
    items.value = items.value.filter((row) => row.item_id !== item.item_id);
    toast('已删除', 'success');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    busyId.value = 0;
  }
}

async function clearAll() {
  if (clearing.value || !items.value.length) return;
  if (!window.confirm(`清空全部 ${items.value.length} 条工作偏好？此操作不可撤销。`)) return;
  clearing.value = true;
  try {
    await api('/api/profile', { method: 'DELETE' });
    items.value = [];
    toast('已清空全部偏好', 'success');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    clearing.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>我的工作偏好</h2>
        <p>从你的对话中沉淀城市、部门与常用口径，只用于你自己的对话；不参与权限、审批或答复验收。</p>
      </div>
      <div class="toolbar">
        <span class="hint">生效中 {{ activeCount }} / 共 {{ items.length }} 条</span>
        <button class="ghost sm" :disabled="loading" @click="load">刷新</button>
        <button class="ghost sm" :disabled="clearing || !items.length" @click="clearAll">
          {{ clearing ? '清空中…' : '清空全部' }}
        </button>
      </div>
    </div>

    <div class="notice">
      <strong>开关说明</strong>
      <p>
        偏好按个人隔离，不会共享给同部门或同权限的其他用户。停用或删除后，下一轮对话立即不再注入；
        删除为物理删除，审计日志只保留类别、键与值哈希，无法还原原文。
      </p>
      <p>
        系统只会自动抽取受控词表内的组织级事实（城市、部门、默认周期、口径与关注主题），
        不会记录金额、人员或证件信息；你也可以直接在下方停用或删除任何一条。
      </p>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>偏好项</th>
            <th>当前值</th>
            <th>来源</th>
            <th>状态</th>
            <th>注入次数</th>
            <th>最近更新</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.item_id" :class="{ muted: item.status === 'DISABLED' }">
            <td>
              <div>{{ item.label }}</div>
              <div class="hint">{{ categoryLabel(item.category) }}</div>
            </td>
            <td>{{ item.item_value }}</td>
            <td>{{ sourceLabel(item.source) }}</td>
            <td>
              <span class="badge" :class="statusMeta(item.status).tone">{{ statusMeta(item.status).label }}</span>
            </td>
            <td>{{ item.injected_count }}</td>
            <td>{{ formatTime(item.updated_at) }}</td>
            <td>
              <button class="subtle sm" :disabled="busyId === item.item_id" @click="toggle(item)">
                {{ item.status === 'DISABLED' ? '启用' : '停用' }}
              </button>
              <button class="subtle sm" :disabled="busyId === item.item_id" @click="remove(item)">删除</button>
            </td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="7" class="empty">
              {{ blocked || (loading ? '加载中…' : '还没有沉淀出工作偏好；完成多轮对话后会自动识别。') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="hint" style="margin-top: 12px">
      画像变更后，同一问题的语义缓存会按个人维度重新计算，不会复用他人的答复。
    </p>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
  color: var(--text-soft);
  line-height: 1.7;
  font-size: 13px;
}

.notice strong {
  color: var(--text);
}

.notice p {
  margin: 6px 0 0;
}

.notice p:first-of-type {
  margin-top: 8px;
}
</style>

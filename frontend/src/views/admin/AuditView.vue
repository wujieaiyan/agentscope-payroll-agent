<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const page = ref({ items: [], total: 0, page: 1, size: 50 });
const loading = ref(false);
const detail = ref(null);
const filters = reactive({
  from: '',
  to: '',
  actor: '',
  action: '',
  resource_type: '',
  keyword: '',
  result: '',
});

const pages = computed(() => Math.max(1, Math.ceil(page.value.total / page.value.size)));

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—';
}

function query(pageNo = page.value.page) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value);
  });
  params.set('page', String(pageNo));
  params.set('size', String(page.value.size));
  return params;
}

async function load(pageNo = page.value.page) {
  loading.value = true;
  try {
    page.value = (await api(`/api/audit?${query(pageNo).toString()}`)) || page.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function exportCsv() {
  window.open(`/api/audit/export?${query(1).toString()}`, '_blank');
}

function reset() {
  Object.assign(filters, { from: '', to: '', actor: '', action: '', resource_type: '', keyword: '', result: '' });
  load(1);
}

function pretty(value) {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch (error) {
    return value;
  }
}

onMounted(() => load(1));
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>审计日志</h2>
        <p>登录、写操作、审批与对话反馈均记录操作者、来源 IP 与变更快照。</p>
      </div>
      <div class="toolbar">
        <button class="ghost" @click="exportCsv">导出 CSV</button>
        <button class="primary" @click="load(1)">查询</button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <input v-model="filters.from" type="date" style="width: 150px" title="开始日期" />
        <input v-model="filters.to" type="date" style="width: 150px" title="结束日期" />
        <input v-model="filters.actor" placeholder="操作者" style="width: 140px" />
        <input v-model="filters.action" placeholder="动作，如 user.create" style="width: 180px" />
        <input v-model="filters.resource_type" placeholder="资源类型" style="width: 150px" />
        <select v-model="filters.result" style="width: 120px">
          <option value="">全部结果</option>
          <option value="SUCCESS">成功</option>
          <option value="FAILURE">失败</option>
        </select>
        <input v-model="filters.keyword" placeholder="关键字" style="width: 160px" />
        <button class="ghost sm" @click="reset">重置</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>时间</th>
            <th>操作者</th>
            <th>动作</th>
            <th>资源</th>
            <th>结果</th>
            <th>IP</th>
            <th>消息</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in page.items" :key="row.id">
            <td>{{ formatTime(row.occurred_at) }}</td>
            <td>{{ row.actor_name || row.actor_id || '—' }}</td>
            <td class="mono">{{ row.action }}</td>
            <td class="mono">{{ row.resource_type }}{{ row.resource_id ? `:${row.resource_id}` : '' }}</td>
            <td>
              <span class="badge" :class="row.result === 'SUCCESS' ? 'green' : 'red'">
                {{ row.result }}
              </span>
            </td>
            <td class="muted">{{ row.ip || '—' }}</td>
            <td class="muted" style="max-width: 260px">{{ row.message || '—' }}</td>
            <td><button class="subtle sm" @click="detail = row">详情</button></td>
          </tr>
          <tr v-if="!page.items.length">
            <td colspan="8" class="empty">{{ loading ? '加载中…' : '没有日志' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <span>共 {{ page.total }} 条 · 第 {{ page.page }} / {{ pages }} 页</span>
      <button class="ghost sm" :disabled="page.page <= 1" @click="load(page.page - 1)">上一页</button>
      <button class="ghost sm" :disabled="page.page >= pages" @click="load(page.page + 1)">下一页</button>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>审计详情 #{{ detail.id }}</h3>
          <button class="subtle sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div class="panel-row"><span class="k">时间</span><span class="v">{{ formatTime(detail.occurred_at) }}</span></div>
          <div class="panel-row"><span class="k">操作者</span><span class="v">{{ detail.actor_name }}（{{ detail.actor_id }}）</span></div>
          <div class="panel-row"><span class="k">动作</span><span class="v mono">{{ detail.action }}</span></div>
          <div class="panel-row"><span class="k">资源</span><span class="v mono">{{ detail.resource_type }}:{{ detail.resource_id }}</span></div>
          <div class="panel-row"><span class="k">结果</span><span class="v">{{ detail.result }} · HTTP {{ detail.status_code }}</span></div>
          <div class="panel-row"><span class="k">来源</span><span class="v">{{ detail.ip }} · {{ detail.user_agent || '—' }}</span></div>
          <div v-if="detail.trace_id" class="panel-row"><span class="k">Trace</span><span class="v mono">{{ detail.trace_id }}</span></div>
          <div v-if="detail.message" class="panel-row"><span class="k">消息</span><span class="v">{{ detail.message }}</span></div>
          <template v-for="key in ['before_json', 'after_json', 'payload_json']" :key="key">
            <div v-if="detail[key]" class="card">
              <div class="card-title"><h3>{{ key }}</h3></div>
              <pre class="trace-pre">{{ pretty(detail[key]) }}</pre>
            </div>
          </template>
        </div>
      </aside>
    </div>
  </div>
</template>

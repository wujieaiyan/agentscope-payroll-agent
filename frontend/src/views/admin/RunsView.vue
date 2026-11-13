<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const runs = ref([]);
const loading = ref(false);
const detail = ref(null);
const detailLoading = ref(false);
const showCreate = ref(false);
const creating = ref(false);
const form = reactive({ period: currentPeriod(), scope: '全部' });

const STATUS_LABEL = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  FINALIZED: '已出账',
  SENT: '已发送',
};

const STATUS_CLASS = {
  DRAFT: '',
  PENDING_APPROVAL: 'orange',
  APPROVED: 'blue',
  REJECTED: 'red',
  FINALIZED: 'green',
  SENT: 'green',
};

function currentPeriod() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

function money(value) {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function statusLabel(status) {
  return STATUS_LABEL[status] || status;
}

function statusClass(status) {
  return STATUS_CLASS[status] || '';
}

const run = computed(() => detail.value && detail.value.run);
const items = computed(() => (detail.value && detail.value.items) || []);
const failures = computed(() => (detail.value && detail.value.failures) || []);
const approvals = computed(() => (detail.value && detail.value.approvals) || []);

async function load() {
  loading.value = true;
  try {
    runs.value = (await api('/api/runs')) || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function openRun(id) {
  detailLoading.value = true;
  try {
    detail.value = await api(`/api/runs/${id}`);
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    detailLoading.value = false;
  }
}

async function createBatch() {
  if (creating.value) return;
  creating.value = true;
  try {
    const created = await api('/api/runs/preview', {
      method: 'POST',
      body: { period: form.period, scope: form.scope, adjustments: [] },
    });
    toast(`批次 ${created.run.run_id} 已生成草稿`, 'success');
    showCreate.value = false;
    await load();
    await openRun(created.run.run_id);
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    creating.value = false;
  }
}

async function act(action, payload = null, confirmText = '') {
  if (confirmText && !window.confirm(confirmText)) return;
  try {
    const updated = await api(`/api/runs/${run.value.run_id}/${action}`, {
      method: 'POST',
      body: payload || {},
    });
    toast('操作成功', 'success');
    await load();
    if (updated && updated.run) detail.value = updated;
    else await openRun(run.value.run_id);
  } catch (error) {
    toast(error.message, 'error');
  }
}

function submitApproval() {
  act('submit-approval', { comment: '' }, '提交审批后将锁定核算结果，确定提交？');
}

function approve() {
  act('approve', { comment: '' }, '确认审批通过本批次？');
}

function reject() {
  const reason = window.prompt('请输入驳回原因');
  if (!reason || !reason.trim()) return;
  act('reject', { reason: reason.trim() });
}

function finalize() {
  act('finalize', null, '出账后将生成 Excel 并锁定批次，确定出账？');
}

function send() {
  act('send', { confirm: true }, '发送将走 dry-run 邮件网关，只为在职员工生成发送记录。确定继续？');
}

function downloadExcel() {
  window.open(`/api/runs/${run.value.run_id}/excel`, '_blank');
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>工资批次</h2>
        <p>草稿 → 提交审批 → 审批通过 → 出账 → 发送（固定 dry-run 邮件网关）。</p>
      </div>
      <button class="primary" @click="showCreate = true">新建批次</button>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>批次</th>
            <th>期间</th>
            <th>范围</th>
            <th>人数</th>
            <th>应发合计</th>
            <th>扣款合计</th>
            <th>实发合计</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in runs" :key="row.run_id">
            <td class="mono">#{{ row.run_id }}</td>
            <td>{{ row.period }}</td>
            <td>{{ row.scope }}</td>
            <td>{{ row.employee_count }}</td>
            <td class="mono">{{ money(row.total_gross) }}</td>
            <td class="mono">{{ money(row.total_deductions) }}</td>
            <td class="mono">{{ money(row.total_net) }}</td>
            <td>
              <span class="badge" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
            </td>
            <td>{{ (row.created_at || '').replace('T', ' ').slice(0, 16) }}</td>
            <td><button class="subtle sm" @click="openRun(row.run_id)">详情</button></td>
          </tr>
          <tr v-if="!runs.length">
            <td colspan="10" class="empty">{{ loading ? '加载中…' : '还没有工资批次' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal" style="width: min(460px, 96vw)">
        <div class="modal-head">
          <h3>新建核算批次</h3>
          <button class="subtle sm" @click="showCreate = false">关闭</button>
        </div>
        <div class="modal-body">
          <label class="field">
            核算期间
            <input v-model="form.period" type="month" />
          </label>
          <label class="field">
            范围（部门名称或「全部」）
            <input v-model="form.scope" placeholder="全部" />
          </label>
          <p class="hint">生成的是草稿批次，可在详情中提交审批后出账。</p>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showCreate = false">取消</button>
          <button class="primary" :disabled="creating" @click="createBatch">
            {{ creating ? '核算中…' : '开始核算' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="detail || detailLoading" class="drawer-mask" @click.self="detail = null">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>
            批次详情
            <span v-if="run" class="mono">#{{ run.run_id }}</span>
            <span v-if="run" class="badge" :class="statusClass(run.status)">
              {{ statusLabel(run.status) }}
            </span>
          </h3>
          <button class="subtle sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="detailLoading" class="empty">加载中…</div>
          <template v-else-if="run">
            <div class="card">
              <div class="card-title">
                <h3>批次概要</h3>
                <small>{{ run.period }} · {{ run.scope }}</small>
              </div>
              <div class="form-grid">
                <div class="field">人数 <strong>{{ run.employee_count }}</strong></div>
                <div class="field">应发合计 <strong class="mono">{{ money(run.total_gross) }}</strong></div>
                <div class="field">扣款合计 <strong class="mono">{{ money(run.total_deductions) }}</strong></div>
                <div class="field">实发合计 <strong class="mono">{{ money(run.total_net) }}</strong></div>
                <div class="field">企业成本 <strong class="mono">{{ money(run.total_employer_cost) }}</strong></div>
                <div class="field">出账时间 <strong>{{ (run.finalized_at || '').replace('T', ' ') || '—' }}</strong></div>
              </div>
              <div class="toolbar" style="margin-top: 14px">
                <button v-if="run.status === 'DRAFT'" class="primary sm" @click="submitApproval">提交审批</button>
                <template v-if="run.status === 'PENDING_APPROVAL'">
                  <button class="success sm" @click="approve">审批通过</button>
                  <button class="danger sm" @click="reject">驳回</button>
                </template>
                <button v-if="run.status === 'APPROVED'" class="primary sm" @click="finalize">出账</button>
                <button
                  v-if="run.status === 'FINALIZED' || run.status === 'SENT'"
                  class="ghost sm"
                  @click="downloadExcel"
                >
                  下载 Excel
                </button>
                <button v-if="run.status === 'FINALIZED'" class="ghost sm" @click="send">发送工资条</button>
              </div>
            </div>

            <div v-if="failures.length" class="card">
              <div class="card-title"><h3>核算失败</h3><small>{{ failures.length }} 人</small></div>
              <div v-for="failure in failures" :key="failure.employee_id" class="error-text">
                {{ failure.employee_id }}：{{ failure.message }}
              </div>
            </div>

            <div class="card">
              <div class="card-title"><h3>核算明细</h3><small>{{ items.length }} 人</small></div>
              <div class="table-wrap" style="max-height: 360px">
                <table class="table">
                  <thead>
                    <tr>
                      <th>工号</th>
                      <th>姓名</th>
                      <th>部门</th>
                      <th>应发</th>
                      <th>个税</th>
                      <th>实发</th>
                      <th>邮件</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="item in items" :key="item.employee_id">
                      <td class="mono">{{ item.employee_id }}</td>
                      <td>{{ item.employee_name }}</td>
                      <td>{{ item.department }}</td>
                      <td class="mono">{{ money(item.payroll?.gross_pay) }}</td>
                      <td class="mono">{{ money(item.payroll?.tax?.tax_this_month) }}</td>
                      <td class="mono">{{ money(item.payroll?.net_pay) }}</td>
                      <td>{{ item.email_status || '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div class="card">
              <div class="card-title"><h3>审批轨迹</h3><small>{{ approvals.length }} 条</small></div>
              <div v-if="!approvals.length" class="muted">尚未提交审批。</div>
              <div v-for="event in approvals" :key="event.event_id" class="node-item">
                <span class="badge blue">{{ event.action }}</span>
                <span class="name">{{ event.step_name || event.flow_name || '审批' }}</span>
                <span class="muted">{{ event.actor_name || '—' }}</span>
                <span class="muted">{{ (event.created_at || '').replace('T', ' ').slice(5, 16) }}</span>
              </div>
            </div>
          </template>
        </div>
      </aside>
    </div>
  </div>
</template>

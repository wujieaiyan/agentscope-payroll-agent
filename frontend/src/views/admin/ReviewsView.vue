<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api, upload } from '../../api/http';
import { can } from '../../stores/auth';
import { toast } from '../../stores/toast';

const rows = ref([]);
const employees = ref([]);
const loading = ref(false);
const saving = ref(false);
const period = ref(currentPeriod());
const editing = ref(false);
const fileInput = ref(null);
const importing = ref(false);
const report = ref(null);
const form = reactive({
  employee_id: '',
  period: currentPeriod(),
  score: '90',
  coefficient: '1',
  comment: '',
});

const writable = computed(() => can('review:write'));
const importable = computed(() => can('import:use'));

function currentPeriod() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

async function load() {
  loading.value = true;
  try {
    const query = period.value ? `?period=${encodeURIComponent(period.value)}` : '';
    rows.value = (await api(`/api/reviews${query}`)) || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function loadEmployees() {
  try {
    employees.value = (await api('/api/employees?include_inactive=false')) || [];
  } catch (error) {
    employees.value = [];
  }
}

function createReview() {
  editing.value = false;
  Object.assign(form, {
    employee_id: employees.value[0]?.employee_id || '',
    period: period.value || currentPeriod(),
    score: '90',
    coefficient: '1',
    comment: '',
  });
}

function editReview(row) {
  editing.value = true;
  Object.assign(form, {
    employee_id: row.employee_id,
    period: row.period,
    score: String(row.score ?? ''),
    coefficient: String(row.coefficient ?? ''),
    comment: row.comment || '',
  });
}

async function save() {
  if (!form.employee_id) {
    toast('请选择员工', 'error');
    return;
  }
  if (saving.value) return;
  saving.value = true;
  try {
    await api('/api/reviews', {
      method: 'POST',
      body: {
        employee_id: form.employee_id,
        period: form.period,
        score: Number(form.score),
        coefficient: Number(form.coefficient),
        comment: form.comment.trim(),
      },
    });
    toast('考核已保存', 'success');
    period.value = form.period;
    editing.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function remove(row) {
  if (!window.confirm(`确认删除 ${row.employee_id} 在 ${row.period} 的考核记录？`)) return;
  try {
    await api(
      `/api/reviews/${encodeURIComponent(row.employee_id)}/${encodeURIComponent(row.period)}`,
      { method: 'DELETE' }
    );
    toast('考核已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

function pickFile() {
  if (fileInput.value) fileInput.value.click();
}

async function importExcel(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  if (!window.confirm(`确认导入「${file.name}」？已存在的考核记录会被覆盖更新。`)) {
    event.target.value = '';
    return;
  }
  importing.value = true;
  try {
    report.value = await upload('/api/import/reviews', file);
    const failed = Number(report.value?.failed || 0);
    toast(
      `考核导入完成：成功 ${report.value?.success ?? 0}，失败 ${failed}`,
      failed ? 'error' : 'success'
    );
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    importing.value = false;
    event.target.value = '';
  }
}

function downloadTemplate() {
  window.open('/api/import/templates/reviews', '_blank');
}

function fixed(value, digits) {
  if (value === null || value === undefined || value === '') return '—';
  return Number(value).toFixed(digits);
}

onMounted(async () => {
  await Promise.all([load(), loadEmployees()]);
  createReview();
});
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>绩效考核</h2>
        <p>绩效系数（0–2）乘以当月绩效奖金后进入应发、个税与实发；未维护考核时按系数 1 处理。</p>
      </div>
      <div class="toolbar">
        <label class="hint" style="display: flex; align-items: center; gap: 6px">
          期间
          <input v-model="period" type="month" style="width: 150px" @change="load" />
        </label>
        <input ref="fileInput" type="file" accept=".xlsx,.xls" hidden @change="importExcel" />
        <button v-if="importable" class="ghost" :disabled="importing" @click="pickFile">
          {{ importing ? '导入中…' : '导入 Excel' }}
        </button>
        <button v-if="importable" class="ghost" @click="downloadTemplate">下载模板</button>
        <button class="primary" :disabled="!writable" @click="createReview">录入考核</button>
      </div>
    </div>

    <div v-if="report" class="card">
      <div class="card-title">
        <h3>导入结果 · {{ report.type_label || '考核' }}</h3>
        <button class="subtle sm" @click="report = null">关闭</button>
      </div>
      <p class="hint">共 {{ report.total }} 行，成功 {{ report.success }}，失败 {{ report.failed }}。</p>
      <div v-if="report.errors && report.errors.length" class="table-wrap" style="max-height: 240px">
        <table class="table">
          <thead>
            <tr>
              <th>行号</th>
              <th>工号</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="err in report.errors" :key="`${err.row}-${err.employee_id}`">
              <td class="mono">{{ err.row }}</td>
              <td class="mono">{{ err.employee_id || '—' }}</td>
              <td class="error-text">{{ err.message }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="writable" class="card">
      <div class="card-title">
        <h3>{{ editing ? `编辑考核 · ${form.employee_id}` : '录入 / 更新考核' }}</h3>
        <small>同一员工同一期间重复保存会覆盖原记录</small>
      </div>
      <div class="form-grid">
        <label class="field">
          员工
          <select v-model="form.employee_id" :disabled="editing">
            <option value="">请选择员工</option>
            <option v-for="employee in employees" :key="employee.employee_id" :value="employee.employee_id">
              {{ employee.employee_id }} · {{ employee.name }}{{ employee.active ? '' : '（停用）' }}
            </option>
          </select>
        </label>
        <label class="field">
          期间
          <input v-model="form.period" type="month" :disabled="editing" />
        </label>
        <label class="field">
          考核分数（0–100）
          <input v-model="form.score" type="number" min="0" max="100" step="0.01" />
        </label>
        <label class="field">
          绩效系数（0–2）
          <input v-model="form.coefficient" type="number" min="0" max="2" step="0.01" />
        </label>
        <label class="field span-2">
          备注
          <input v-model="form.comment" placeholder="可选" />
        </label>
      </div>
      <div class="toolbar" style="margin-top: 14px">
        <button class="primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存考核' }}
        </button>
        <button v-if="editing" class="ghost" @click="createReview">取消编辑</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>期间</th>
            <th>工号</th>
            <th>姓名</th>
            <th>分数</th>
            <th>系数</th>
            <th>备注</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="`${row.employee_id}-${row.period}`">
            <td>{{ row.period }}</td>
            <td class="mono">{{ row.employee_id }}</td>
            <td>{{ row.employee_name }}</td>
            <td class="mono">{{ fixed(row.score, 2) }}</td>
            <td class="mono">{{ fixed(row.coefficient, 4) }}</td>
            <td>{{ row.comment || '—' }}</td>
            <td>
              <div class="cell-actions">
                <template v-if="writable">
                  <button class="subtle sm" @click="editReview(row)">编辑</button>
                  <button class="subtle sm" @click="remove(row)">删除</button>
                </template>
                <span v-else class="hint">只读</span>
              </div>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="7" class="empty">{{ loading ? '加载中…' : '该期间暂无考核记录' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

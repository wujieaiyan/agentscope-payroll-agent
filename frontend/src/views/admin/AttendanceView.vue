<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api, upload } from '../../api/http';
import { can } from '../../stores/auth';
import { toast } from '../../stores/toast';

const FIELDS = [
  ['personal_leave_days', '事假（天）'],
  ['sick_leave_days', '病假（天）'],
  ['absent_days', '旷工（天）'],
  ['weekday_overtime_hours', '工作日加班（小时）'],
  ['rest_day_overtime_hours', '休息日加班（小时）'],
  ['holiday_overtime_hours', '节假日加班（小时）'],
];

const employees = ref([]);
const records = ref({});
const drafts = reactive({});
const loading = ref(false);
const savingId = ref('');
const period = ref(currentPeriod());
const fileInput = ref(null);
const importing = ref(false);
const report = ref(null);

const writable = computed(() => can('attendance:write'));
const importable = computed(() => can('import:use'));

function currentPeriod() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

function blankDraft() {
  const draft = {};
  FIELDS.forEach(([key]) => {
    draft[key] = '0';
  });
  return draft;
}

async function loadEmployees() {
  try {
    employees.value = (await api('/api/employees?include_inactive=false')) || [];
  } catch (error) {
    employees.value = [];
  }
}

async function load() {
  if (!period.value) {
    toast('请选择考勤期间', 'error');
    return;
  }
  loading.value = true;
  try {
    const list = (await api(`/api/attendance?period=${encodeURIComponent(period.value)}`)) || [];
    records.value = Object.fromEntries(list.map((item) => [item.employee_id, item]));
    employees.value.forEach((employee) => {
      const saved = records.value[employee.employee_id];
      drafts[employee.employee_id] = blankDraft();
      FIELDS.forEach(([key]) => {
        drafts[employee.employee_id][key] = String(saved?.[key] ?? 0);
      });
    });
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function save(employee) {
  const draft = drafts[employee.employee_id];
  if (!draft) return;
  savingId.value = employee.employee_id;
  try {
    const body = {
      employee_id: employee.employee_id,
      period: period.value,
    };
    FIELDS.forEach(([key]) => {
      body[key] = Number(draft[key] || 0);
    });
    const saved = await api(
      `/api/attendance/${encodeURIComponent(employee.employee_id)}/${encodeURIComponent(period.value)}`,
      { method: 'PUT', body }
    );
    records.value = { ...records.value, [employee.employee_id]: saved || body };
    toast(`${employee.name} 考勤已保存`, 'success');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    savingId.value = '';
  }
}

function pickFile() {
  if (fileInput.value) fileInput.value.click();
}

async function importExcel(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  if (!window.confirm(`确认导入「${file.name}」？已存在的考勤记录会被覆盖更新。`)) {
    event.target.value = '';
    return;
  }
  importing.value = true;
  try {
    report.value = await upload('/api/import/attendance', file);
    const failed = Number(report.value?.failed || 0);
    toast(
      `考勤导入完成：成功 ${report.value?.success ?? 0}，失败 ${failed}`,
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
  window.open('/api/import/templates/attendance', '_blank');
}

onMounted(async () => {
  await loadEmployees();
  await load();
});
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>考勤管理</h2>
        <p>考勤由 HR 录入，系统不会按全勤推算；缺勤数据缺失的员工会在核算中失败并给出原因。</p>
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
        <button class="ghost" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="report" class="card">
      <div class="card-title">
        <h3>导入结果 · {{ report.type_label || '考勤' }}</h3>
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

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>工号</th>
            <th>姓名</th>
            <th>部门</th>
            <th v-for="[key, label] in FIELDS" :key="key">{{ label }}</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="employee in employees" :key="employee.employee_id">
            <td class="mono">{{ employee.employee_id }}</td>
            <td>{{ employee.name }}</td>
            <td>{{ employee.department }}</td>
            <td v-for="[key] in FIELDS" :key="`${employee.employee_id}-${key}`">
              <input
                v-if="drafts[employee.employee_id]"
                v-model="drafts[employee.employee_id][key]"
                type="number"
                min="0"
                step="0.5"
                style="width: 84px"
                :disabled="!writable"
                :aria-label="`${employee.name} ${key}`"
              />
            </td>
            <td>
              <span class="badge" :class="records[employee.employee_id] ? 'green' : 'orange'">
                {{ records[employee.employee_id] ? '已录入' : '未录入' }}
              </span>
            </td>
            <td>
              <button
                v-if="writable"
                class="subtle sm"
                :disabled="savingId === employee.employee_id"
                @click="save(employee)"
              >
                {{ savingId === employee.employee_id ? '保存中…' : '保存' }}
              </button>
              <span v-else class="hint">只读</span>
            </td>
          </tr>
          <tr v-if="!employees.length">
            <td colspan="11" class="empty">{{ loading ? '加载中…' : '暂无在职员工' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

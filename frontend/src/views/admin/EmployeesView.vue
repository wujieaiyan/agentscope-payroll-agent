<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const rows = ref([]);
const loading = ref(false);
const keyword = ref('');
const includeInactive = ref(false);
const showForm = ref(false);
const saving = ref(false);
const editing = ref(false);
const form = reactive(blank());

const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  if (!needle) return rows.value;
  return rows.value.filter((row) =>
    [row.employee_id, row.name, row.department, row.position, row.city]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle))
  );
});

function blank() {
  return {
    employee_id: '',
    name: '',
    department: '',
    position: '',
    city: '北京',
    base_salary: '',
    position_allowance: '',
    housing_fund_rate: '0.12',
    social_insurance_base: '',
    special_additional_deduction: '0',
    hire_date: '',
    bank_account_masked: '',
    email: '',
  };
}

function money(value) {
  if (value === null || value === undefined || value === '') return '—';
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

async function load() {
  loading.value = true;
  try {
    rows.value = (await api(`/api/employees?include_inactive=${includeInactive.value}`)) || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function createEmployee() {
  editing.value = false;
  Object.assign(form, blank());
  showForm.value = true;
}

function editEmployee(row) {
  editing.value = true;
  Object.assign(form, {
    ...blank(),
    ...row,
    base_salary: row.base_salary ?? '',
    position_allowance: row.position_allowance ?? '',
    housing_fund_rate: row.housing_fund_rate ?? '',
    social_insurance_base: row.social_insurance_base ?? '',
    special_additional_deduction: row.special_additional_deduction ?? '',
  });
  showForm.value = true;
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    const body = { ...form };
    ['base_salary', 'position_allowance', 'housing_fund_rate', 'social_insurance_base', 'special_additional_deduction'].forEach(
      (key) => {
        body[key] = body[key] === '' || body[key] === null ? null : Number(body[key]);
      }
    );
    if (body.social_insurance_base === null) delete body.social_insurance_base;
    await api('/api/employees', { method: 'POST', body });
    toast('员工档案已保存', 'success');
    showForm.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function deactivate(row) {
  if (!window.confirm(`停用员工「${row.name}」？历史批次不受影响。`)) return;
  try {
    await api(`/api/employees/${encodeURIComponent(row.employee_id)}/deactivate`, {
      method: 'POST',
    });
    toast('员工已停用', 'success');
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
        <h2>员工</h2>
        <p>维护薪酬档案、社保基数与专项附加扣除；停用员工不参与新的核算批次。</p>
      </div>
      <div class="toolbar">
        <input v-model="keyword" placeholder="搜索工号 / 姓名 / 部门" style="width: 220px" />
        <label class="hint" style="display: flex; align-items: center; gap: 6px">
          <input v-model="includeInactive" type="checkbox" style="height: auto" @change="load" />
          含已停用
        </label>
        <button class="primary" @click="createEmployee">新增员工</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>工号</th>
            <th>姓名</th>
            <th>部门</th>
            <th>职位</th>
            <th>城市</th>
            <th>基本工资</th>
            <th>岗位津贴</th>
            <th>社保基数</th>
            <th>入职日期</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in filtered" :key="row.employee_id">
            <td class="mono">{{ row.employee_id }}</td>
            <td>{{ row.name }}</td>
            <td>{{ row.department }}</td>
            <td>{{ row.position || '—' }}</td>
            <td>{{ row.city }}</td>
            <td class="mono">{{ money(row.base_salary) }}</td>
            <td class="mono">{{ money(row.position_allowance) }}</td>
            <td class="mono">{{ money(row.social_insurance_base) }}</td>
            <td>{{ row.hire_date || '—' }}</td>
            <td>
              <span class="badge" :class="row.active ? 'green' : 'red'">
                {{ row.active ? '在职' : '停用' }}
              </span>
            </td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" @click="editEmployee(row)">编辑</button>
                <button v-if="row.active" class="subtle sm" @click="deactivate(row)">停用</button>
              </div>
            </td>
          </tr>
          <tr v-if="!filtered.length">
            <td colspan="11" class="empty">{{ loading ? '加载中…' : '没有符合条件的员工' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal">
        <div class="modal-head">
          <h3>{{ editing ? `编辑员工 ${form.employee_id}` : '新增员工' }}</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              工号
              <input v-model="form.employee_id" :disabled="editing" placeholder="EMP001" />
            </label>
            <label class="field">
              姓名
              <input v-model="form.name" placeholder="张三" />
            </label>
            <label class="field">
              部门
              <input v-model="form.department" placeholder="研发部" />
            </label>
            <label class="field">
              职位
              <input v-model="form.position" placeholder="工程师" />
            </label>
            <label class="field">
              城市
              <input v-model="form.city" placeholder="北京" />
            </label>
            <label class="field">
              入职日期
              <input v-model="form.hire_date" type="date" />
            </label>
            <label class="field">
              基本工资
              <input v-model="form.base_salary" type="number" step="0.01" />
            </label>
            <label class="field">
              岗位津贴
              <input v-model="form.position_allowance" type="number" step="0.01" />
            </label>
            <label class="field">
              公积金比例（0-1）
              <input v-model="form.housing_fund_rate" type="number" step="0.01" />
            </label>
            <label class="field">
              社保基数（留空按工资口径）
              <input v-model="form.social_insurance_base" type="number" step="0.01" />
            </label>
            <label class="field">
              专项附加扣除
              <input v-model="form.special_additional_deduction" type="number" step="0.01" />
            </label>
            <label class="field">
              邮箱
              <input v-model="form.email" type="email" />
            </label>
            <label class="field span-2">
              银行卡（脱敏展示）
              <input v-model="form.bank_account_masked" placeholder="**** **** **** 1234" />
            </label>
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

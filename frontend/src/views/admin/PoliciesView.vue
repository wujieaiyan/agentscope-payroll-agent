<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const policies = ref([]);
const loading = ref(false);
const keyword = ref('');
const editing = ref(null);
const saving = ref(false);
const newCity = ref('');

const SCALAR_FIELDS = [
  { key: 'monthly_threshold', label: '月度起征点' },
  { key: 'standard_work_days', label: '月标准工作天数' },
  { key: 'standard_daily_hours', label: '日标准工时' },
  { key: 'sick_leave_pay_rate', label: '病假工资比例' },
  { key: 'social_base_lower', label: '社保基数下限' },
  { key: 'social_base_upper', label: '社保基数上限' },
  { key: 'pension_employee_rate', label: '养老（个人）' },
  { key: 'medical_employee_rate', label: '医疗（个人）' },
  { key: 'medical_fixed_amount', label: '医疗固定额（个人）' },
  { key: 'unemployment_employee_rate', label: '失业（个人）' },
  { key: 'pension_employer_rate', label: '养老（单位）' },
  { key: 'medical_employer_rate', label: '医疗（单位）' },
  { key: 'unemployment_employer_rate', label: '失业（单位）' },
  { key: 'injury_employer_rate', label: '工伤（单位）' },
  { key: 'maternity_employer_rate', label: '生育（单位）' },
];

const form = reactive({ city: '', values: {}, brackets: [] });

const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  if (!needle) return policies.value;
  return policies.value.filter((item) => String(item.city).toLowerCase().includes(needle));
});

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—';
}

function number(value) {
  return value === null || value === undefined || value === '' ? '—' : value;
}

async function load() {
  loading.value = true;
  try {
    policies.value = (await api('/api/policies')) || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function openEditor(item) {
  const policy = item.policy || {};
  const values = {};
  SCALAR_FIELDS.forEach((field) => {
    values[field.key] = policy[field.key] ?? '';
  });
  const brackets = (policy.tax_brackets || []).map((bracket) => ({
    upper_limit: bracket.upper_limit === null || bracket.upper_limit === undefined ? '' : bracket.upper_limit,
    rate: bracket.rate ?? '',
    quick_deduction: bracket.quick_deduction ?? '',
  }));
  editing.value = item;
  Object.assign(form, { city: item.city, values, brackets });
}

function openNew() {
  const city = newCity.value.trim();
  if (!city) {
    toast('请填写城市名称', 'error');
    return;
  }
  const existing = policies.value.find((item) => item.city === city);
  if (existing) {
    newCity.value = '';
    openEditor(existing);
    return;
  }
  const base = policies.value[0]?.policy;
  if (!base) {
    toast('暂无基准政策，无法新建城市', 'error');
    return;
  }
  openEditor({ city, policy: base, updated_at: null });
  newCity.value = '';
}

function addBracket() {
  form.brackets.push({ upper_limit: '', rate: '', quick_deduction: '' });
}

function removeBracket(index) {
  form.brackets.splice(index, 1);
}

async function save() {
  if (saving.value) return;
  if (!form.brackets.length) {
    toast('税率表至少需要一档', 'error');
    return;
  }
  const last = form.brackets[form.brackets.length - 1];
  if (last.upper_limit !== '' && last.upper_limit !== null) {
    toast('最后一档税率的上限必须留空（无上限）', 'error');
    return;
  }
  const body = {};
  for (const field of SCALAR_FIELDS) {
    const raw = form.values[field.key];
    if (raw === '' || raw === null) {
      toast(`请填写「${field.label}」`, 'error');
      return;
    }
    body[field.key] = Number(raw);
  }
  body.tax_brackets = form.brackets.map((bracket) => ({
    upper_limit:
      bracket.upper_limit === '' || bracket.upper_limit === null ? null : Number(bracket.upper_limit),
    rate: Number(bracket.rate),
    quick_deduction: bracket.quick_deduction === '' ? 0 : Number(bracket.quick_deduction),
  }));
  saving.value = true;
  try {
    await api(`/api/policies/${encodeURIComponent(form.city)}`, { method: 'PUT', body });
    toast(`已保存「${form.city}」政策`, 'success');
    editing.value = null;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>政策</h2>
        <p>按城市维护社保、公积金与个税累计预扣参数；保存后立即用于后续核算。</p>
      </div>
      <div class="toolbar">
        <input v-model="keyword" placeholder="搜索城市" style="width: 180px" />
        <input
          v-model="newCity"
          placeholder="新增城市，如 杭州"
          style="width: 180px"
          @keyup.enter="openNew"
        />
        <button class="primary" @click="openNew">编辑 / 新增</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>城市</th>
            <th>起征点</th>
            <th>社保下限</th>
            <th>社保上限</th>
            <th>养老（个人/单位）</th>
            <th>医保（个人/单位）</th>
            <th>税率档数</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filtered" :key="item.city">
            <td>{{ item.city }}</td>
            <td>{{ number(item.policy?.monthly_threshold) }}</td>
            <td>{{ number(item.policy?.social_base_lower) }}</td>
            <td>{{ number(item.policy?.social_base_upper) }}</td>
            <td>
              {{ number(item.policy?.pension_employee_rate) }} /
              {{ number(item.policy?.pension_employer_rate) }}
            </td>
            <td>
              {{ number(item.policy?.medical_employee_rate) }} /
              {{ number(item.policy?.medical_employer_rate) }}
            </td>
            <td>{{ item.policy?.tax_brackets?.length ?? '—' }}</td>
            <td>{{ formatTime(item.updated_at) }}</td>
            <td>
              <button class="subtle sm" @click="openEditor(item)">编辑</button>
            </td>
          </tr>
          <tr v-if="!filtered.length">
            <td colspan="9" class="empty">{{ loading ? '加载中…' : '暂无城市政策' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal" style="width: min(980px, 96vw)">
        <div class="modal-head">
          <h3>{{ form.city }} · 政策参数</h3>
          <button class="subtle sm" @click="editing = null">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label v-for="field in SCALAR_FIELDS" :key="field.key" class="field">
              {{ field.label }}
              <input v-model="form.values[field.key]" type="number" step="any" />
            </label>
          </div>

          <h4 style="margin: 18px 0 8px">个税累计预扣税率表</h4>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>档位</th>
                  <th>上限（留空表示无上限）</th>
                  <th>预扣率</th>
                  <th>速算扣除数</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(bracket, index) in form.brackets" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td>
                    <input
                      v-model="bracket.upper_limit"
                      type="number"
                      step="any"
                      :placeholder="index === form.brackets.length - 1 ? '无上限' : ''"
                    />
                  </td>
                  <td><input v-model="bracket.rate" type="number" step="any" /></td>
                  <td><input v-model="bracket.quick_deduction" type="number" step="any" /></td>
                  <td>
                    <button class="subtle sm" @click="removeBracket(index)">移除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <button class="ghost sm" style="margin-top: 8px" @click="addBracket">新增档位</button>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="editing = null">取消</button>
          <button class="primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存政策' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

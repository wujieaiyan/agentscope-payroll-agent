'use strict';

const state = {
  allEmployees: [],
  employees: [],
  reviews: [],
  policies: [],
  selectedCity: null,
  currentPolicy: null,
  attendance: [],
  currentRun: null,
  lastEmailReport: null,
  previewDirty: false,
  runs: [],
  dataThrough: null,
  chatBusy: false,
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const POLICY_FIELDS = [
  ['monthly_threshold', '月减除费用（元）', '0.01'],
  ['standard_work_days', '月标准工作日', '0.01'],
  ['standard_daily_hours', '日标准工时', '0.01'],
  ['sick_leave_pay_rate', '病假计薪比例（0-1）', '0.0001'],
  ['social_base_lower', '社保基数下限（元）', '0.01'],
  ['social_base_upper', '社保基数上限（元）', '0.01'],
  ['pension_employee_rate', '养老个人比例', '0.0001'],
  ['medical_employee_rate', '医疗个人比例', '0.0001'],
  ['medical_fixed_amount', '医疗个人固定额（元）', '0.01'],
  ['unemployment_employee_rate', '失业个人比例', '0.0001'],
  ['pension_employer_rate', '养老企业比例', '0.0001'],
  ['medical_employer_rate', '医疗企业比例', '0.0001'],
  ['unemployment_employer_rate', '失业企业比例', '0.0001'],
  ['injury_employer_rate', '工伤企业比例', '0.0001'],
  ['maternity_employer_rate', '生育企业比例', '0.0001'],
];

function esc(value) {
  return String(value === null || value === undefined ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function num(value) {
  if (value === '' || value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function money(value) {
  return Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function formatDateTime(value) {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 19);
}

function runStatusLabel(status) {
  return { DRAFT: '草稿', FINALIZED: '已出账', SENT: '已发送' }[status] || status || '—';
}

function emailStatusLabel(status) {
  return {
    PENDING: '未发送',
    SIMULATED_SUCCESS: '模拟发送成功',
    FAILED: '发送失败',
  }[status] || status || '—';
}

let toastTimer = null;
function toast(message, type) {
  const el = $('#toast');
  el.textContent = message;
  el.className = 'toast ' + (type || '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.className = 'toast hidden';
  }, type === 'error' ? 7000 : 3500);
}

function showError(error) {
  console.error(error);
  toast(error && error.message ? error.message : String(error), 'error');
}

async function api(path, options = {}) {
  const init = { method: options.method || 'GET', headers: {} };
  if (options.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(path, init);
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (parseError) {
      throw new Error(text);
    }
  }
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    throw new Error((payload && payload.message) || `请求失败：HTTP ${response.status}`);
  }
  return payload ? payload.data : null;
}

function setPeriodDefaults(period) {
  if (!period) return;
  ['#review-period', '#attendance-period', '#run-period',
    '#review-form [name="period"]'].forEach((selector) => {
    const input = $(selector);
    if (input && !input.value) input.value = period;
  });
}

async function loadHealth() {
  const badge = $('#health');
  try {
    const health = await api('/api/health');
    state.dataThrough = health.data_through_period;
    badge.textContent = `数据库已连接 · 考勤至 ${health.data_through_period} · 邮件 ${health.email_mode}`;
    badge.className = 'badge ok';
    const chatModel = $('#chat-model');
    if (chatModel) chatModel.textContent = '模型 ' + (health.chat_model || '未配置');
    setPeriodDefaults(health.data_through_period);
  } catch (error) {
    badge.textContent = '数据库连接失败';
    badge.className = 'badge bad';
    throw error;
  }
}

function showTab(name) {
  $$('#tabs button').forEach((button) => {
    button.classList.toggle('active', button.dataset.tab === name);
  });
  $$('.tab').forEach((tab) => {
    tab.classList.toggle('active', tab.id === 'tab-' + name);
  });
  if (name === 'employees') loadEmployees().catch(showError);
  if (name === 'reviews') loadReviews().catch(showError);
  if (name === 'policies') loadPolicies().catch(showError);
  if (name === 'attendance') loadAttendance().catch(showError);
  if (name === 'payroll') loadRuns().catch(showError);
  if (name === 'chat') setTimeout(() => $('#chat-input').focus(), 50);
}

async function loadEmployees() {
  const all = await api('/api/employees?include_inactive=true');
  state.allEmployees = all;
  state.employees = all.filter((employee) => employee.active);
  renderEmployees();
  fillEmployeeSelect();
  fillScopeSelect();
}

function renderEmployees() {
  const showInactive = $('#employee-include-inactive').checked;
  const rows = state.allEmployees.filter((employee) => showInactive || employee.active);
  const body = $('#employee-rows');
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="11" class="hint">暂无员工</td></tr>';
    return;
  }
  body.innerHTML = rows
    .map(
      (employee) => `
      <tr>
        <td>${esc(employee.employee_id)}</td>
        <td>${esc(employee.name)}</td>
        <td>${esc(employee.department)}</td>
        <td>${esc(employee.position)}</td>
        <td>${esc(employee.city)}</td>
        <td class="num">${money(employee.base_salary)}</td>
        <td class="num">${money(employee.position_allowance)}</td>
        <td class="num">${(Number(employee.housing_fund_rate) * 100).toFixed(2)}%</td>
        <td>${employee.email ? esc(employee.email) : '<span class="hint">未维护</span>'}</td>
        <td><span class="status ${employee.active ? 'SENT' : 'FAILED'}">${employee.active ? '在职' : '停用'}</span></td>
        <td>
          <button class="link" data-edit-employee="${esc(employee.employee_id)}">编辑</button>
          ${employee.active ? `<button class="link danger-text" data-deactivate="${esc(employee.employee_id)}">停用</button>` : ''}
        </td>
      </tr>`
    )
    .join('');
}

function showEmployeeForm(employee) {
  const form = $('#employee-form');
  form.reset();
  $('#employee-form-title').textContent = employee
    ? `编辑员工 ${employee.employee_id}`
    : '新增员工';
  if (employee) {
    Object.entries(employee).forEach(([key, value]) => {
      const input = form.elements[key];
      if (input && key !== 'active' && key !== 'updated_at') {
        input.value = value === null || value === undefined ? '' : value;
      }
    });
    form.elements.employee_id.readOnly = true;
  } else {
    form.elements.employee_id.readOnly = false;
    form.elements.city.value = '北京';
    form.elements.position_allowance.value = '0';
    form.elements.housing_fund_rate.value = '0.12';
    form.elements.special_additional_deduction.value = '0';
    form.elements.hire_date.value = new Date().toISOString().slice(0, 10);
  }
  form.classList.remove('hidden');
  form.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function fillEmployeeSelect() {
  const select = $('#review-employee');
  const previous = select.value;
  select.innerHTML =
    '<option value="">请选择员工</option>' +
    state.allEmployees
      .map(
        (employee) =>
          `<option value="${esc(employee.employee_id)}">${esc(employee.employee_id)} · ${esc(employee.name)}${employee.active ? '' : '（停用）'}</option>`
      )
      .join('');
  if (previous) select.value = previous;
}

function fillScopeSelect() {
  const select = $('#run-scope');
  const previous = select.value;
  const departments = [...new Set(state.employees.map((employee) => employee.department))];
  select.innerHTML =
    '<option value="全部">全部</option>' +
    departments.map((department) => `<option>${esc(department)}</option>`).join('');
  select.value = previous && (previous === '全部' || departments.includes(previous))
    ? previous
    : '全部';
}

async function loadReviews() {
  const period = $('#review-period').value;
  const query = period ? '?period=' + encodeURIComponent(period) : '';
  state.reviews = await api('/api/reviews' + query);
  renderReviews();
}

function renderReviews() {
  const body = $('#review-rows');
  if (!state.reviews.length) {
    body.innerHTML = '<tr><td colspan="7" class="hint">该期间暂无考核记录</td></tr>';
    return;
  }
  body.innerHTML = state.reviews
    .map(
      (review) => `
      <tr>
        <td>${esc(review.period)}</td>
        <td>${esc(review.employee_id)}</td>
        <td>${esc(review.employee_name)}</td>
        <td class="num">${Number(review.score).toFixed(2)}</td>
        <td class="num">${Number(review.coefficient).toFixed(4)}</td>
        <td>${esc(review.comment)}</td>
        <td>
          <button class="link" data-edit-review="${esc(review.employee_id)}" data-period="${esc(review.period)}">编辑</button>
          <button class="link danger-text" data-delete-review="${esc(review.employee_id)}" data-period="${esc(review.period)}">删除</button>
        </td>
      </tr>`
    )
    .join('');
}

async function loadPolicies() {
  state.policies = await api('/api/policies');
  renderPolicyCities();
  const city =
    (state.selectedCity &&
      state.policies.some((item) => item.city === state.selectedCity) &&
      state.selectedCity) ||
    (state.policies[0] && state.policies[0].city);
  if (city) {
    selectPolicy(city);
  } else {
    state.selectedCity = null;
    state.currentPolicy = null;
    $('#policy-fields').innerHTML = '<p class="hint">暂无城市政策</p>';
    $('#policy-tax-rows').innerHTML = '';
  }
}

function renderPolicyCities() {
  $('#policy-cities').innerHTML = state.policies
    .map(
      (item) =>
        `<li data-city="${esc(item.city)}" class="${item.city === state.selectedCity ? 'active' : ''}">
          <span>${esc(item.city)}</span>
          <span class="hint">${esc((item.updated_at || '').slice(0, 10))}</span>
        </li>`
    )
    .join('');
}

function selectPolicy(city) {
  const item = state.policies.find((policy) => policy.city === city);
  if (!item) return;
  state.selectedCity = city;
  state.currentPolicy = item.policy;
  renderPolicyCities();
  $('#policy-title').textContent = `城市政策 · ${city}`;
  $('#policy-fields').innerHTML = POLICY_FIELDS.map(([key, label, step]) => {
    const value = item.policy[key];
    return `<label>${esc(label)}<input name="${key}" type="number" step="${step}" value="${value === null || value === undefined ? '' : esc(value)}"></label>`;
  }).join('');
  $('#policy-tax-rows').innerHTML = (item.policy.tax_brackets || [])
    .map(
      (bracket) => `
      <tr>
        <td class="num">${bracket.upper_limit === null ? '无上限' : money(bracket.upper_limit)}</td>
        <td class="num">${(Number(bracket.rate) * 100).toFixed(1)}%</td>
        <td class="num">${money(bracket.quick_deduction)}</td>
      </tr>`
    )
    .join('');
  $('#policy-updated').textContent = '最近更新：' + (item.updated_at || '—');
}

async function loadAttendance() {
  const period = $('#attendance-period').value;
  if (!period) {
    toast('请选择考勤期间', 'error');
    return;
  }
  state.attendance = await api('/api/attendance?period=' + encodeURIComponent(period));
  renderAttendance(period);
}

function renderAttendance(period) {
  const byEmployee = new Map(state.attendance.map((item) => [item.employee_id, item]));
  const body = $('#attendance-rows');
  if (!state.employees.length) {
    body.innerHTML = '<tr><td colspan="11" class="hint">暂无在职员工</td></tr>';
    return;
  }
  body.innerHTML = state.employees
    .map((employee) => {
      const item = byEmployee.get(employee.employee_id) || {
        personal_leave_days: 0,
        sick_leave_days: 0,
        absent_days: 0,
        weekday_overtime_hours: 0,
        rest_day_overtime_hours: 0,
        holiday_overtime_hours: 0,
      };
      const field = (name, label) =>
        `<td class="num"><input class="small" type="number" min="0" step="0.5" data-field="${name}" value="${esc(item[name])}" aria-label="${label}"></td>`;
      return `
        <tr>
          <td>${esc(employee.employee_id)}</td>
          <td>${esc(employee.name)}</td>
          <td>${esc(employee.department)}</td>
          ${field('personal_leave_days', '事假')}
          ${field('sick_leave_days', '病假')}
          ${field('absent_days', '旷工')}
          ${field('weekday_overtime_hours', '工作日加班')}
          ${field('rest_day_overtime_hours', '休息日加班')}
          ${field('holiday_overtime_hours', '节假日加班')}
          <td>${byEmployee.has(employee.employee_id) ? '<span class="status SENT">已录入</span>' : '<span class="status PENDING">未录入</span>'}</td>
          <td><button class="tiny primary" data-save-attendance="${esc(employee.employee_id)}" data-period="${esc(period)}">保存</button></td>
        </tr>`;
    })
    .join('');
}

function renderRunSummary(run) {
  $('#run-summary').innerHTML = `
    <div class="metric"><div class="label">人数</div><div class="value">${run.employee_count}</div></div>
    <div class="metric"><div class="label">应发合计</div><div class="value">${money(run.total_gross)}</div></div>
    <div class="metric"><div class="label">扣款合计</div><div class="value">${money(run.total_deductions)}</div></div>
    <div class="metric"><div class="label">实发合计</div><div class="value">${money(run.total_net)}</div></div>
    <div class="metric"><div class="label">企业成本</div><div class="value">${money(run.total_employer_cost)}</div></div>`;
  $('#run-summary').classList.remove('hidden');
}

function showRunDetail(detail) {
  state.currentRun = detail;
  state.previewDirty = false;
  if (
    state.lastEmailReport &&
    state.lastEmailReport.run_id !== detail.run.run_id
  ) {
    state.lastEmailReport = null;
  }
  renderRunDetail();
}

function renderRunDetail() {
  const detail = state.currentRun;
  if (!detail) {
    $('#run-panel').classList.add('hidden');
    $('#run-summary').classList.add('hidden');
    return;
  }
  const run = detail.run;
  const editable = run.status === 'DRAFT';
  renderRunSummary(run);
  $('#run-panel').classList.remove('hidden');
  $('#run-title').textContent = `批次 #${run.run_id} · ${run.period} · ${run.scope}`;
  $('#run-status').textContent = runStatusLabel(run.status);
  $('#run-status').className = 'status ' + run.status;

  $('#run-rows').innerHTML = detail.items
    .map((item) => {
      const payroll = item.payroll;
      return `
        <tr>
          <td>${esc(item.employee_id)}</td>
          <td>${esc(item.employee_name)}</td>
          <td>${esc(item.department)}</td>
          <td>${esc(item.city)}</td>
          <td>${item.email ? esc(item.email) : '<span class="hint">未维护</span>'}</td>
          <td class="num">${money(payroll.base_salary)}</td>
          <td class="num"><input class="small" type="number" min="0" step="0.01"
              data-field="performance_bonus" data-emp="${esc(item.employee_id)}"
              value="${Number(payroll.performance_bonus).toFixed(2)}"
              ${editable ? '' : 'disabled'}></td>
          <td class="num"><input class="small" type="number" min="0" step="0.01"
              data-field="other_allowance" data-emp="${esc(item.employee_id)}"
              value="${Number(payroll.other_allowance).toFixed(2)}"
              ${editable ? '' : 'disabled'}></td>
          <td class="num">${money(payroll.gross_pay)}</td>
          <td class="num">${money(payroll.total_deductions)}</td>
          <td class="num"><strong>${money(payroll.net_pay)}</strong></td>
          <td><span class="status ${esc(item.email_status)}">${emailStatusLabel(item.email_status)}</span></td>
          <td><span title="${esc((payroll.warnings || []).join('；'))}">${(payroll.warnings || []).length ? (payroll.warnings || []).length + ' 条' : '—'}</span></td>
        </tr>`;
    })
    .join('');

  const failures = detail.failures || [];
  const failureBox = $('#run-failures');
  if (failures.length) {
    failureBox.innerHTML =
      `<strong>核算失败 ${failures.length} 人，出账前必须修正：</strong><ul>` +
      failures
        .map((failure) => `<li>${esc(failure.employee_id)}：${esc(failure.message)}</li>`)
        .join('') +
      '</ul>';
    failureBox.classList.remove('hidden');
  } else {
    failureBox.classList.add('hidden');
    failureBox.innerHTML = '';
  }

  $('#run-apply-adjust').classList.toggle('hidden', !editable);
  $('#run-finalize').classList.toggle('hidden', !editable);
  $('#run-finalize').disabled = state.previewDirty || failures.length > 0;
  $('#run-send').classList.toggle('hidden', run.status !== 'FINALIZED');
  $('#run-send').disabled = state.previewDirty;
  const excel = $('#run-excel');
  const hasExcel = Boolean(run.excel_path);
  excel.classList.toggle('hidden', !hasExcel);
  if (hasExcel) excel.href = `/api/runs/${run.run_id}/excel`;

  const hint = $('#run-hint');
  if (state.previewDirty) {
    hint.textContent = '微调金额已修改，请先“应用微调并重新预览”，再出账。';
  } else if (failures.length) {
    hint.textContent = '存在核算失败员工，请先补齐考勤/政策/邮箱等数据后重新预览。';
  } else if (run.status === 'DRAFT') {
    hint.textContent = '草稿不会发送邮件。确认明细后可出账；出账会生成 Excel 并落库。';
  } else if (run.status === 'FINALIZED') {
    hint.textContent = '已出账。确认无误后发送全员邮件；当前邮件为 dry-run，不会真实投递。';
  } else {
    hint.textContent = '该批次已完成 dry-run 发送，不允许重复发送。';
  }

  renderEmailReportForCurrentRun();
}

function reportFromItems(detail) {
  const results = detail.items.map((item) => ({
    employee_id: item.employee_id,
    employee_name: item.employee_name,
    email: item.email,
    status: item.email_status,
    reason: item.email_reason,
  }));
  const success = results.filter((result) => result.status === 'SIMULATED_SUCCESS').length;
  return {
    run_id: detail.run.run_id,
    total: results.length,
    success_count: success,
    failure_count: results.length - success,
    dry_run: true,
    results,
  };
}

function renderEmailReportForCurrentRun() {
  const detail = state.currentRun;
  if (!detail) {
    $('#email-report').classList.add('hidden');
    return;
  }
  if (state.lastEmailReport && state.lastEmailReport.run_id === detail.run.run_id) {
    renderEmailReport(state.lastEmailReport);
    return;
  }
  if (detail.run.status === 'SENT') {
    renderEmailReport(reportFromItems(detail));
    return;
  }
  $('#email-report').classList.add('hidden');
}

function renderEmailReport(report) {
  $('#email-report-summary').innerHTML = `
    <div class="metric"><div class="label">收件人</div><div class="value">${report.total}</div></div>
    <div class="metric"><div class="label">模拟发送成功</div><div class="value">${report.success_count}</div></div>
    <div class="metric"><div class="label">发送失败</div><div class="value">${report.failure_count}</div></div>
    <div class="metric"><div class="label">模式</div><div class="value mono">${report.dry_run ? 'dry-run 模拟' : '真实发送'}</div></div>`;
  $('#email-report-rows').innerHTML = report.results
    .map(
      (result) => `
      <tr>
        <td>${esc(result.employee_id)}</td>
        <td>${esc(result.employee_name)}</td>
        <td>${esc(result.email) || '<span class="hint">未维护</span>'}</td>
        <td><span class="status ${esc(result.status)}">${emailStatusLabel(result.status)}</span></td>
        <td>${esc(result.reason) || '—'}</td>
      </tr>`
    )
    .join('');
  $('#email-report').classList.remove('hidden');
}

async function loadRuns() {
  state.runs = await api('/api/runs');
  renderRunHistory();
  fillScopeSelect();
}

function renderRunHistory() {
  const body = $('#run-history-rows');
  if (!state.runs.length) {
    body.innerHTML = '<tr><td colspan="8" class="hint">暂无工资批次</td></tr>';
    return;
  }
  body.innerHTML = state.runs
    .map(
      (run) => `
      <tr>
        <td>#${run.run_id}</td>
        <td>${esc(run.period)}</td>
        <td>${esc(run.scope)}</td>
        <td><span class="status ${esc(run.status)}">${runStatusLabel(run.status)}</span></td>
        <td class="num">${run.employee_count}</td>
        <td class="num">${money(run.total_net)}</td>
        <td>${formatDateTime(run.created_at)}</td>
        <td>
          <button class="link" data-view-run="${run.run_id}">查看</button>
          ${run.excel_path ? `<a class="link" href="/api/runs/${run.run_id}/excel" target="_blank" rel="noopener">Excel</a>` : ''}
        </td>
      </tr>`
    )
    .join('');
}

function collectAdjustments() {
  if (!state.currentRun) return [];
  const baseline = new Map(
    state.currentRun.items.map((item) => [item.employee_id, item.payroll])
  );
  const grouped = new Map();
  $$('#run-rows input[data-field]').forEach((input) => {
    const employeeId = input.dataset.emp;
    const payroll = baseline.get(employeeId);
    if (!payroll) return;
    const value = num(input.value);
    if (value === null) return;
    const original = Number(
      input.dataset.field === 'performance_bonus'
        ? payroll.performance_bonus
        : payroll.other_allowance
    );
    if (Math.abs(value - original) < 0.005) return;
    const entry = grouped.get(employeeId) || { employee_id: employeeId };
    entry[input.dataset.field] = value;
    grouped.set(employeeId, entry);
  });
  return Array.from(grouped.values());
}

async function previewRun() {
  const period = $('#run-period').value;
  if (!period) {
    toast('请选择工资期间', 'error');
    return;
  }
  const adjustments = collectAdjustments();
  const button = $('#run-preview');
  const original = button.textContent;
  button.disabled = true;
  button.textContent = '核算中…';
  try {
    const detail = await api('/api/runs/preview', {
      method: 'POST',
      body: {
        period,
        scope: $('#run-scope').value || '全部',
        adjustments,
      },
    });
    state.lastEmailReport = null;
    showRunDetail(detail);
    toast('预览完成，可微调金额后重新预览', 'success');
    await loadRuns();
    $('#run-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) {
    showError(error);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

async function finalizeRun() {
  if (!state.currentRun) return;
  if (!confirm('确认出账？系统会重新核算、写入数据库并生成 Excel。')) return;
  try {
    const detail = await api(`/api/runs/${state.currentRun.run.run_id}/finalize`, {
      method: 'POST',
    });
    showRunDetail(detail);
    await loadRuns();
    toast('出账完成，Excel 已生成', 'success');
  } catch (error) {
    showError(error);
  }
}

async function sendRun() {
  if (!state.currentRun) return;
  if (
    !confirm(
      '确认工资数据无误并发送全员工资条邮件？当前为 dry-run，不会真实投递，但会记录每人的发送结果。'
    )
  ) {
    return;
  }
  try {
    const report = await api(`/api/runs/${state.currentRun.run.run_id}/send`, {
      method: 'POST',
      body: { confirm: true },
    });
    state.lastEmailReport = report;
    const detail = await api(`/api/runs/${state.currentRun.run.run_id}`);
    showRunDetail(detail);
    state.lastEmailReport = report;
    renderEmailReport(report);
    await loadRuns();
    toast(`发送完成：成功 ${report.success_count}，失败 ${report.failure_count}`, 'success');
    $('#email-report').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) {
    showError(error);
  }
}

async function upload(path, file) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    body: file,
  });
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (parseError) {
      throw new Error(text);
    }
  }
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    throw new Error((payload && payload.message) || `导入失败：HTTP ${response.status}`);
  }
  return payload ? payload.data : null;
}

const IMPORT_META = {
  employees: { label: '员工', reload: () => loadEmployees() },
  reviews: { label: '考核', reload: () => loadReviews() },
  attendance: { label: '考勤', reload: () => loadAttendance() },
};

function showImportReport(report) {
  const card = $('#import-report');
  const failed = Number(report.failed || 0);
  card.classList.remove('hidden');
  card.classList.toggle('has-errors', failed > 0);
  $('#import-report-title').textContent = `导入结果 · ${report.type_label || ''}`;
  $('#import-report-summary').innerHTML = `
    <div class="metric"><div class="label">总行数</div><div class="value">${report.total}</div></div>
    <div class="metric"><div class="label">导入成功</div><div class="value">${report.success}</div></div>
    <div class="metric"><div class="label">导入失败</div><div class="value">${report.failed}</div></div>
    <div class="metric"><div class="label">失败处理</div><div class="value mono">修正后可重新导入</div></div>`;
  const errorsBox = $('#import-report-errors');
  const errors = report.errors || [];
  if (!errors.length) {
    errorsBox.innerHTML = '<p class="hint">全部行导入成功。</p>';
  } else {
    errorsBox.innerHTML = `
      <table>
        <thead><tr><th>Excel 行号</th><th>工号</th><th>失败原因</th></tr></thead>
        <tbody>
          ${errors
            .map(
              (error) => `
              <tr>
                <td>${error.row}</td>
                <td>${esc(error.employee_id) || '—'}</td>
                <td>${esc(error.message)}</td>
              </tr>`
            )
            .join('')}
        </tbody>
      </table>`;
  }
  card.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function importWorkbook(type, input) {
  const file = input.files && input.files[0];
  if (!file) return;
  const meta = IMPORT_META[type];
  if (!confirm(`确认导入「${file.name}」？\n已存在的记录会被覆盖更新，失败行会单独列出。`)) {
    input.value = '';
    return;
  }
  toast('正在导入，请稍候…');
  try {
    const report = await upload('/api/import/' + type, file);
    showImportReport(report);
    await meta.reload();
    toast(
      `${meta.label}导入完成：成功 ${report.success}，失败 ${report.failed}`,
      report.failed ? 'error' : 'success'
    );
  } catch (error) {
    showError(error);
  } finally {
    input.value = '';
  }
}

function chatEmptyMarkup() {
  return `
    <div class="chat-empty">
      <strong>可以这样问：</strong>
      <div class="chat-examples">
        <button type="button" data-chat-example="算一下张伟 2026-10 的工资">算一下张伟 2026-10 的工资</button>
        <button type="button" data-chat-example="研发部 2026-10 工资表">研发部 2026-10 工资表</button>
        <button type="button" data-chat-example="上海社保基数上下限是多少？">上海社保基数上下限是多少？</button>
        <button type="button" data-chat-example="替我检查 EMP005 2026-10 工资能不能发">检查 EMP005 能否发薪</button>
      </div>
    </div>`;
}

function appendChatMessage(role, text, tools, isError) {
  const container = $('#chat-messages');
  const empty = container.querySelector('.chat-empty');
  if (empty) empty.remove();
  const wrapper = document.createElement('div');
  wrapper.className =
    'chat-message ' + (role === 'user' ? 'user' : 'assistant') + (isError ? ' error' : '');
  const label = document.createElement('div');
  label.className = 'chat-role';
  label.textContent = role === 'user' ? '你' : '薪酬智能体';
  const bubble = document.createElement('div');
  bubble.className = 'chat-bubble';
  bubble.textContent = text;
  wrapper.append(label, bubble);
  if (tools && tools.length) {
    const toolLabel = document.createElement('div');
    toolLabel.className = 'chat-tools';
    toolLabel.textContent = '调用工具：' + tools.join('、');
    wrapper.append(toolLabel);
  }
  container.append(wrapper);
  container.scrollTop = container.scrollHeight;
  return wrapper;
}

const CHAT_TOOL_STATE = {
  SUCCESS: { label: '成功', cls: 'ok' },
  ERROR: { label: '失败', cls: 'bad' },
  INTERRUPTED: { label: '中断', cls: 'bad' },
  DENIED: { label: '拒绝', cls: 'bad' },
  RUNNING: { label: '执行中', cls: '' },
};

function appendStreamingMessage() {
  const container = $('#chat-messages');
  const empty = container.querySelector('.chat-empty');
  if (empty) empty.remove();

  const wrapper = document.createElement('div');
  wrapper.className = 'chat-message assistant';

  const label = document.createElement('div');
  label.className = 'chat-role';
  label.textContent = '薪酬智能体';

  const thinking = document.createElement('details');
  thinking.className = 'chat-thinking empty';
  thinking.open = true;
  const summary = document.createElement('summary');
  const summaryTitle = document.createElement('span');
  summaryTitle.className = 'chat-thinking-title';
  summaryTitle.textContent = '思考过程';
  const summaryState = document.createElement('span');
  summaryState.className = 'chat-thinking-state';
  summaryState.textContent = '进行中…';
  summary.append(summaryTitle, summaryState);
  const thinkingBody = document.createElement('div');
  thinkingBody.className = 'chat-thinking-body';
  thinking.append(summary, thinkingBody);

  const bubble = document.createElement('div');
  bubble.className = 'chat-bubble chat-streaming';

  const toolsLine = document.createElement('div');
  toolsLine.className = 'chat-tools hidden';

  wrapper.append(label, thinking, bubble, toolsLine);
  container.append(wrapper);
  container.scrollTop = container.scrollHeight;

  const startedAt = Date.now();
  let reasoning = null;
  let lastPhase = '';
  let activeTool = null;
  let activeAgent = null;
  let fullText = '';
  let finished = false;

  const scroll = () => {
    container.scrollTop = container.scrollHeight;
  };
  const revealThinking = () => {
    thinking.classList.remove('empty');
  };
  const currentBody = () => (activeAgent ? activeAgent.body : thinkingBody);
  const appendPhase = (text) => {
    if (!text || text === lastPhase) return;
    lastPhase = text;
    revealThinking();
    const line = document.createElement('div');
    line.className = 'chat-thinking-phase';
    line.textContent = text;
    currentBody().append(line);
    if (activeAgent) activeAgent.textNode = null;
    scroll();
  };

  return {
    appendPhase,
    startAgent(agent, label) {
      revealThinking();
      const block = document.createElement('div');
      block.className = 'chat-agent-block';
      const header = document.createElement('div');
      header.className = 'chat-agent-name';
      header.textContent = label || agent || '智能体';
      const body = document.createElement('div');
      body.className = 'chat-agent-body';
      block.append(header, body);
      thinkingBody.append(block);
      activeAgent = { agent, block, body, textNode: null };
      reasoning = null;
      lastPhase = '';
      scroll();
    },
    appendAgentText(agent, delta) {
      if (!delta) return;
      revealThinking();
      if (!activeAgent || (agent && activeAgent.agent !== agent)) {
        const span = document.createElement('div');
        span.className = 'chat-agent-text';
        span.textContent = delta;
        currentBody().append(span);
        scroll();
        return;
      }
      if (!activeAgent.textNode) {
        activeAgent.textNode = document.createElement('div');
        activeAgent.textNode.className = 'chat-agent-text';
        activeAgent.body.append(activeAgent.textNode);
      }
      activeAgent.textNode.textContent += delta;
      scroll();
    },
    finishAgent(agent) {
      if (!activeAgent || (agent && activeAgent.agent !== agent)) return;
      activeAgent.block.classList.add('done');
      activeAgent = null;
      reasoning = null;
    },
    appendThinking(delta) {
      if (!delta) return;
      revealThinking();
      if (!reasoning || (activeAgent && reasoning.parentElement !== activeAgent.body)) {
        reasoning = document.createElement('div');
        reasoning.className = 'chat-thinking-reasoning';
        currentBody().append(reasoning);
        if (activeAgent) activeAgent.textNode = null;
      }
      reasoning.textContent += delta;
      scroll();
    },
    startTool(toolCallId, name) {
      revealThinking();
      lastPhase = '';
      const step = document.createElement('div');
      step.className = 'chat-thinking-step';
      step.dataset.toolCallId = toolCallId || '';
      const nameEl = document.createElement('code');
      nameEl.className = 'chat-tool-name';
      nameEl.textContent = name || '工具';
      const argsEl = document.createElement('span');
      argsEl.className = 'chat-tool-args';
      const stateEl = document.createElement('span');
      stateEl.className = 'chat-tool-state';
      stateEl.textContent = '调用中…';
      step.append(nameEl, argsEl, stateEl);
      currentBody().append(step);
      if (activeAgent) activeAgent.textNode = null;
      activeTool = { toolCallId, step, argsEl, stateEl };
      scroll();
    },
    appendToolArgs(toolCallId, delta) {
      if (!activeTool || !delta) return;
      if (toolCallId && activeTool.toolCallId && toolCallId !== activeTool.toolCallId) return;
      activeTool.argsEl.textContent += delta;
      scroll();
    },
    finishTool(toolCallId, state) {
      if (!activeTool) return;
      if (toolCallId && activeTool.toolCallId && toolCallId !== activeTool.toolCallId) return;
      const meta = CHAT_TOOL_STATE[state] || { label: state || '完成', cls: '' };
      activeTool.stateEl.textContent = meta.label;
      activeTool.stateEl.className = 'chat-tool-state ' + meta.cls;
      activeTool = null;
    },
    appendText(delta) {
      if (!delta) return;
      fullText += delta;
      bubble.textContent = fullText;
      scroll();
    },
    finish(reply) {
      if (finished) return;
      finished = true;
      activeAgent = null;
      bubble.classList.remove('chat-streaming');
      if (!fullText && reply && reply.reply) {
        fullText = reply.reply;
        bubble.textContent = fullText;
      }
      if (reply && reply.tools && reply.tools.length) {
        toolsLine.textContent = '调用工具：' + reply.tools.join('、');
        toolsLine.classList.remove('hidden');
      }
      const seconds = ((Date.now() - startedAt) / 1000).toFixed(1);
      summaryState.textContent = '已完成 · ' + seconds + 's';
      if (!thinking.classList.contains('empty')) {
        thinking.open = false;
      }
      scroll();
    },
    fail(message) {
      if (finished) return;
      finished = true;
      wrapper.classList.add('error');
      bubble.classList.remove('chat-streaming');
      bubble.textContent = message || '对话失败';
      summaryState.textContent = '失败';
      if (!thinking.classList.contains('empty')) {
        thinking.open = false;
      }
      scroll();
    },
  };
}

async function streamChat(message, onEvent) {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message }),
  });
  if (!response.ok) {
    let payload = null;
    try {
      payload = JSON.parse(await response.text());
    } catch (parseError) {
      payload = null;
    }
    throw new Error((payload && payload.message) || `请求失败：HTTP ${response.status}`);
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  const dispatch = (raw) => {
    let event = 'message';
    const dataLines = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    if (!dataLines.length) return;
    let data;
    try {
      data = JSON.parse(dataLines.join('\n'));
    } catch (parseError) {
      return;
    }
    onEvent(event, data);
  };
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
      let index;
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        if (raw.trim()) dispatch(raw);
      }
    }
    buffer = (buffer + decoder.decode()).replace(/\r\n/g, '\n').trim();
    if (buffer) dispatch(buffer);
  } finally {
    reader.cancel().catch(() => {});
  }
}

async function sendChat(override) {
  if (state.chatBusy) return;
  const input = $('#chat-input');
  const message = (override || input.value || '').trim();
  if (!message) {
    input.focus();
    return;
  }
  appendChatMessage('user', message);
  input.value = '';
  state.chatBusy = true;
  const button = $('#chat-send');
  button.disabled = true;
  const view = appendStreamingMessage();
  let done = false;
  try {
    await streamChat(message, (event, data) => {
      switch (event) {
        case 'agent_start':
          view.startAgent(data.agent, data.label);
          break;
        case 'agent_text':
          view.appendAgentText(data.agent, data.text);
          break;
        case 'agent_end':
          view.finishAgent(data.agent);
          break;
        case 'phase':
          view.appendPhase(data.label);
          break;
        case 'thinking':
          view.appendThinking(data.text);
          break;
        case 'tool_start':
          view.startTool(data.tool_call_id, data.name);
          break;
        case 'tool_args':
          view.appendToolArgs(data.tool_call_id, data.text);
          break;
        case 'tool_result':
          view.finishTool(data.tool_call_id, data.state);
          break;
        case 'text':
          view.appendText(data.text);
          break;
        case 'done':
          done = true;
          view.finish(data);
          break;
        case 'error':
          throw new Error(data.message || '对话失败');
        default:
          break;
      }
    });
    if (!done) view.finish(null);
  } catch (error) {
    view.fail(error.message);
  } finally {
    state.chatBusy = false;
    button.disabled = false;
    input.focus();
  }
}

async function resetChat() {
  if (state.chatBusy) {
    toast('回答生成中，请稍候再重置', 'error');
    return;
  }
  if (!confirm('清空对话并重置智能体上下文？')) return;
  try {
    await api('/api/chat/reset', { method: 'POST' });
    $('#chat-messages').innerHTML = chatEmptyMarkup();
    toast('对话已重置', 'success');
  } catch (error) {
    showError(error);
  }
}

function bindEvents() {
  $$('#tabs button').forEach((button) => {
    button.addEventListener('click', () => showTab(button.dataset.tab));
  });

  $('#employee-import').addEventListener('change', (event) =>
    importWorkbook('employees', event.target)
  );
  $('#review-import').addEventListener('change', (event) =>
    importWorkbook('reviews', event.target)
  );
  $('#attendance-import').addEventListener('change', (event) =>
    importWorkbook('attendance', event.target)
  );
  $('#import-report-close').addEventListener('click', () =>
    $('#import-report').classList.add('hidden')
  );

  $('#chat-form').addEventListener('submit', (event) => {
    event.preventDefault();
    sendChat();
  });
  $('#chat-reset').addEventListener('click', resetChat);
  $('#chat-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendChat();
    }
  });
  $('#chat-messages').addEventListener('click', (event) => {
    const example = event.target.dataset.chatExample;
    if (example) sendChat(example);
  });

  $('#employee-new').addEventListener('click', () => showEmployeeForm(null));
  $('#employee-cancel').addEventListener('click', () => {
    $('#employee-form').classList.add('hidden');
  });
  $('#employee-include-inactive').addEventListener('change', renderEmployees);

  $('#employee-rows').addEventListener('click', async (event) => {
    const editId = event.target.dataset.editEmployee;
    if (editId) {
      const employee = state.allEmployees.find((item) => item.employee_id === editId);
      if (employee) showEmployeeForm(employee);
      return;
    }
    const deactivateId = event.target.dataset.deactivate;
    if (deactivateId) {
      if (!confirm(`确认停用员工 ${deactivateId}？停用后不再参与核算。`)) return;
      try {
        await api(`/api/employees/${encodeURIComponent(deactivateId)}/deactivate`, {
          method: 'POST',
        });
        toast('员工已停用', 'success');
        await loadEmployees();
      } catch (error) {
        showError(error);
      }
    }
  });

  $('#employee-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const payload = {
      employee_id: form.elements.employee_id.value.trim(),
      name: form.elements.name.value.trim(),
      department: form.elements.department.value.trim(),
      position: form.elements.position.value.trim(),
      base_salary: num(form.elements.base_salary.value) ?? 0,
      position_allowance: num(form.elements.position_allowance.value) ?? 0,
      housing_fund_rate: num(form.elements.housing_fund_rate.value) ?? 0,
      social_insurance_base: num(form.elements.social_insurance_base.value),
      special_additional_deduction:
        num(form.elements.special_additional_deduction.value) ?? 0,
      hire_date: form.elements.hire_date.value,
      bank_account_masked: form.elements.bank_account_masked.value.trim(),
      email: form.elements.email.value.trim(),
      city: form.elements.city.value.trim() || '默认',
    };
    try {
      await api('/api/employees', { method: 'POST', body: payload });
      toast('员工已保存', 'success');
      form.classList.add('hidden');
      await loadEmployees();
    } catch (error) {
      showError(error);
    }
  });

  $('#review-refresh').addEventListener('click', () => loadReviews().catch(showError));
  $('#review-period').addEventListener('change', () => loadReviews().catch(showError));
  $('#review-rows').addEventListener('click', async (event) => {
    const editId = event.target.dataset.editReview;
    if (editId) {
      const review = state.reviews.find(
        (item) => item.employee_id === editId && item.period === event.target.dataset.period
      );
      if (review) {
        const form = $('#review-form');
        form.elements.employee_id.value = review.employee_id;
        form.elements.period.value = review.period;
        form.elements.score.value = review.score;
        form.elements.coefficient.value = review.coefficient;
        form.elements.comment.value = review.comment || '';
        form.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
      return;
    }
    const deleteId = event.target.dataset.deleteReview;
    if (deleteId) {
      const period = event.target.dataset.period;
      if (!confirm(`确认删除 ${deleteId} 在 ${period} 的考核记录？`)) return;
      try {
        await api(
          `/api/reviews/${encodeURIComponent(deleteId)}/${encodeURIComponent(period)}`,
          { method: 'DELETE' }
        );
        toast('考核已删除', 'success');
        await loadReviews();
      } catch (error) {
        showError(error);
      }
    }
  });
  $('#review-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const payload = {
      employee_id: form.elements.employee_id.value,
      period: form.elements.period.value,
      score: num(form.elements.score.value) ?? 0,
      coefficient: num(form.elements.coefficient.value) ?? 1,
      comment: form.elements.comment.value.trim(),
    };
    try {
      await api('/api/reviews', { method: 'POST', body: payload });
      toast('考核已保存', 'success');
      $('#review-period').value = payload.period;
      await loadReviews();
    } catch (error) {
      showError(error);
    }
  });

  $('#policy-add').addEventListener('click', async () => {
    const city = prompt('请输入城市名称，例如 广州');
    if (!city || !city.trim()) return;
    const base =
      (state.policies.find((item) => item.city === '默认') || state.policies[0] || {}).policy;
    if (!base) {
      toast('缺少可复制的默认政策', 'error');
      return;
    }
    try {
      await api('/api/policies/' + encodeURIComponent(city.trim()), {
        method: 'PUT',
        body: base,
      });
      state.selectedCity = city.trim();
      await loadPolicies();
      toast('城市政策已创建', 'success');
    } catch (error) {
      showError(error);
    }
  });
  $('#policy-cities').addEventListener('click', (event) => {
    const item = event.target.closest('li[data-city]');
    if (item) selectPolicy(item.dataset.city);
  });
  $('#policy-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!state.selectedCity || !state.currentPolicy) return;
    const values = {};
    $$('#policy-fields input').forEach((input) => {
      values[input.name] = num(input.value);
    });
    const policy = { ...state.currentPolicy, ...values };
    try {
      await api('/api/policies/' + encodeURIComponent(state.selectedCity), {
        method: 'PUT',
        body: policy,
      });
      await loadPolicies();
      toast('城市政策已保存', 'success');
    } catch (error) {
      showError(error);
    }
  });

  $('#attendance-load').addEventListener('click', () => loadAttendance().catch(showError));
  $('#attendance-period').addEventListener('change', () => loadAttendance().catch(showError));
  $('#attendance-rows').addEventListener('click', async (event) => {
    const employeeId = event.target.dataset.saveAttendance;
    if (!employeeId) return;
    const period = event.target.dataset.period;
    const row = event.target.closest('tr');
    const read = (field) => {
      const input = row.querySelector(`input[data-field="${field}"]`);
      return num(input.value) ?? 0;
    };
    const payload = {
      employee_id: employeeId,
      period,
      personal_leave_days: read('personal_leave_days'),
      sick_leave_days: read('sick_leave_days'),
      absent_days: read('absent_days'),
      weekday_overtime_hours: read('weekday_overtime_hours'),
      rest_day_overtime_hours: read('rest_day_overtime_hours'),
      holiday_overtime_hours: read('holiday_overtime_hours'),
    };
    try {
      await api(
        `/api/attendance/${encodeURIComponent(employeeId)}/${encodeURIComponent(period)}`,
        { method: 'PUT', body: payload }
      );
      toast(`${employeeId} 考勤已保存`, 'success');
      await loadAttendance();
    } catch (error) {
      showError(error);
    }
  });

  $('#run-preview').addEventListener('click', previewRun);
  $('#run-apply-adjust').addEventListener('click', previewRun);
  $('#run-finalize').addEventListener('click', finalizeRun);
  $('#run-send').addEventListener('click', sendRun);
  $('#runs-refresh').addEventListener('click', () => loadRuns().catch(showError));
  $('#run-rows').addEventListener('input', (event) => {
    if (!event.target.dataset.field) return;
    state.previewDirty = true;
    $('#run-finalize').disabled = true;
    $('#run-send').disabled = true;
    $('#run-hint').textContent =
      '微调金额已修改，请先“应用微调并重新预览”，再出账。';
  });
  $('#run-history-rows').addEventListener('click', async (event) => {
    const runId = event.target.dataset.viewRun;
    if (!runId) return;
    try {
      const detail = await api('/api/runs/' + encodeURIComponent(runId));
      state.lastEmailReport = null;
      showRunDetail(detail);
      $('#run-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) {
      showError(error);
    }
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  bindEvents();
  try {
    await loadHealth();
    await loadEmployees();
    fillScopeSelect();
  } catch (error) {
    showError(error);
  }
});

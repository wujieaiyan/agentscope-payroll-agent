<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const router = useRouter();
const tasks = ref([]);
const loading = ref(false);
const acting = ref('');

function money(value) {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function approverLabel(type, value) {
  if (!type) return '—';
  const typeLabel = { user: '用户', role: '角色', permission: '权限' }[type] || type;
  return `${typeLabel}：${value || '—'}`;
}

async function load() {
  loading.value = true;
  try {
    tasks.value = (await api('/api/approval/tasks')) || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function viewRun(runId) {
  router.push({ path: '/runs', query: { run: runId } });
}

async function act(task, approve) {
  const text = window.prompt(approve ? '审批意见（可选）' : '请填写驳回原因（必填）');
  if (text === null) return;
  if (!approve && !text.trim()) {
    toast('驳回原因不能为空', 'error');
    return;
  }
  acting.value = `${task.run_id}-${approve ? 'approve' : 'reject'}`;
  try {
    await api(`/api/runs/${task.run_id}/${approve ? 'approve' : 'reject'}`, {
      method: 'POST',
      body: approve ? { comment: text } : { reason: text },
    });
    toast(approve ? '已通过当前节点' : '已驳回批次', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    acting.value = '';
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>我的待办</h2>
        <p>当前审批节点由你的角色、账号或权限匹配到的工资批次；通过或驳回后即从待办中移除。</p>
      </div>
      <button class="ghost" @click="load">刷新</button>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>批次</th>
            <th>期间</th>
            <th>范围</th>
            <th>实发合计</th>
            <th>当前节点</th>
            <th>进度</th>
            <th>提交人</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.run_id">
            <td class="mono">#{{ task.run_id }}</td>
            <td>{{ task.period }}</td>
            <td>{{ task.scope }}</td>
            <td class="mono">{{ money(task.total_net) }}</td>
            <td>
              <strong>{{ task.step?.name || '审批' }}</strong>
              <br />
              <small class="hint">{{ approverLabel(task.step?.approver_type, task.step?.approver_value) }}</small>
            </td>
            <td>
              <span class="badge">{{ Number(task.current_step || 0) + 1 }}/{{ task.step_count }}</span>
            </td>
            <td>{{ task.submitted_by_name || '—' }}</td>
            <td>{{ (task.submitted_at || '').replace('T', ' ').slice(0, 16) || '—' }}</td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" @click="viewRun(task.run_id)">查看</button>
                <button
                  class="subtle sm"
                  :disabled="acting === `${task.run_id}-approve`"
                  @click="act(task, true)"
                >
                  通过
                </button>
                <button
                  class="subtle sm"
                  :disabled="acting === `${task.run_id}-reject`"
                  @click="act(task, false)"
                >
                  驳回
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!tasks.length">
            <td colspan="9" class="empty">
              {{ loading ? '加载中…' : '当前没有需要你处理的审批任务。' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

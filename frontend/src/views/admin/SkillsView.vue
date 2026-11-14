<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const data = ref({ writable: false, location: '-', skills: [] });
const keyword = ref('');
const loading = ref(false);
const detail = ref(null);
const detailLoading = ref(false);
const showForm = ref(false);
const editing = ref(false);
const saving = ref(false);
const form = reactive({ id: '', description: '', content: '' });

const skills = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  const list = data.value.skills || [];
  if (!needle) return list;
  return list.filter((skill) =>
    [skill.id, skill.description, skill.source]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle))
  );
});

function formatTimestamp(value) {
  if (!value) return '—';
  if (typeof value === 'number') return new Date(value).toLocaleString('zh-CN');
  return String(value).replace('T', ' ').slice(0, 16);
}

async function load() {
  loading.value = true;
  try {
    data.value = (await api('/api/admin/skills')) || data.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

async function openSkill(skill) {
  detailLoading.value = true;
  detail.value = { ...skill, content: '', loaded: false };
  try {
    detail.value = await api(`/api/admin/skills/${encodeURIComponent(skill.id)}`);
    detail.value.loaded = true;
  } catch (error) {
    detail.value = null;
    toast(error.message, 'error');
  } finally {
    detailLoading.value = false;
  }
}

function createSkill() {
  editing.value = false;
  Object.assign(form, { id: '', description: '', content: '' });
  showForm.value = true;
}

function editSkill(skill) {
  editing.value = true;
  Object.assign(form, {
    id: skill.id,
    description: skill.description || '',
    content: skill.content || '',
  });
  showForm.value = true;
}

async function editSkillById(skill) {
  await openSkill(skill);
  if (detail.value && detail.value.loaded) editSkill(detail.value);
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    const body = { id: form.id.trim(), description: form.description.trim(), content: form.content };
    if (editing.value) {
      await api(`/api/admin/skills/${encodeURIComponent(body.id)}`, { method: 'PUT', body });
    } else {
      await api('/api/admin/skills', { method: 'POST', body });
    }
    toast('技能已保存', 'success');
    showForm.value = false;
    detail.value = null;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function remove(skill) {
  if (!window.confirm(`删除技能「${skill.id}」？`)) return;
  try {
    await api(`/api/admin/skills/${encodeURIComponent(skill.id)}`, { method: 'DELETE' });
    if (detail.value && detail.value.id === skill.id) detail.value = null;
    toast('技能已删除', 'success');
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
        <h2>技能</h2>
        <p>技能正文以 Markdown 形式存入 {{ data.location || 'Nacos' }}，覆盖内置技能后仍可恢复；被智能体引用时不可删除。</p>
      </div>
      <div class="toolbar">
        <span class="badge" :class="data.writable ? 'green' : 'orange'">
          {{ data.writable ? '可写' : '只读（Nacos 未启用）' }}
        </span>
        <input v-model="keyword" placeholder="搜索技能" style="width: 220px" />
        <button class="primary" :disabled="!data.writable" @click="createSkill">新增技能</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>技能 ID</th>
            <th>说明</th>
            <th>来源</th>
            <th>类型</th>
            <th>长度</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="skill in skills" :key="skill.id">
            <td class="mono">{{ skill.id }}</td>
            <td class="muted">{{ skill.description }}</td>
            <td>{{ skill.source }}</td>
            <td>
              <span class="badge" :class="skill.builtin ? 'blue' : 'green'">
                {{ skill.builtin ? '内置' : '自定义' }}
              </span>
              <span v-if="skill.overridden" class="badge orange">已覆盖</span>
            </td>
            <td>{{ skill.content_length ?? skill.contentLength ?? '—' }}</td>
            <td>{{ formatTimestamp(skill.updated_at ?? skill.updatedAt) }}</td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" @click="openSkill(skill)">查看</button>
                <button class="subtle sm" :disabled="!data.writable" @click="editSkillById(skill)">
                  编辑
                </button>
                <button class="subtle sm" :disabled="!data.writable" @click="remove(skill)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!skills.length">
            <td colspan="7" class="empty">{{ loading ? '加载中…' : '暂无技能' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <aside class="drawer">
        <div class="drawer-head">
          <h3>{{ detail.id }}</h3>
          <button class="subtle sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="detailLoading" class="empty">加载中…</div>
          <template v-else>
            <div class="panel-row"><span class="k">说明</span><span class="v">{{ detail.description || '—' }}</span></div>
            <div class="panel-row"><span class="k">来源</span><span class="v">{{ detail.source || '—' }}</span></div>
            <div class="panel-row">
              <span class="k">类型</span>
              <span class="v">
                {{ detail.builtin ? '内置' : '自定义' }}
                <span v-if="detail.overridden">· 已覆盖</span>
              </span>
            </div>
            <pre class="trace-pre" style="max-height: none">{{ detail.content || '（空）' }}</pre>
          </template>
        </div>
        <div class="drawer-foot">
          <button class="ghost" @click="detail = null">关闭</button>
          <button class="primary" :disabled="!data.writable" @click="editSkill(detail)">编辑</button>
        </div>
      </aside>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width: min(860px, 96vw)">
        <div class="modal-head">
          <h3>{{ editing ? `编辑技能 ${form.id}` : '新增技能' }}</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              技能 ID
              <input v-model="form.id" :disabled="editing" placeholder="payroll-policy-notes" />
            </label>
            <label class="field">
              一句话说明
              <input v-model="form.description" placeholder="用于解释社保与公积金口径" />
            </label>
            <label class="field span-2">
              正文（Markdown）
              <textarea v-model="form.content" rows="16" class="mono"></textarea>
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

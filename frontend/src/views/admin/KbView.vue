<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { auth } from '../../stores/auth';
import { toast } from '../../stores/toast';

const page = ref({ items: [], total: 0, page: 1, size: 20 });
const loading = ref(false);
const filters = reactive({ scope: '', keyword: '', status: '' });
const departments = ref([]);

const showForm = ref(false);
const saving = ref(false);
const form = reactive({
  name: '',
  deptId: '',
  description: '',
  indexStrategy: 'HYBRID',
  draft: false,
});

const detail = ref(null);
const detailLoading = ref(false);
const documents = ref([]);
const docLoading = ref(false);
const summary = ref(null);
const stats = ref(null);
const uploading = ref(false);
const fileInput = ref(null);

const pages = computed(() => Math.max(1, Math.ceil(page.value.total / page.value.size)));

const SERIES_STATUS = {
  DRAFT: { text: '草稿', cls: 'orange' },
  ACTIVE: { text: '已启用', cls: 'green' },
  DISABLED: { text: '已停用', cls: 'red' },
  DELETING: { text: '删除中', cls: 'orange' },
  DELETED: { text: '已删除', cls: 'red' },
};

const DOC_STATUS = {
  PENDING: { text: '待处理', cls: 'orange' },
  PARSING: { text: '解析中', cls: 'blue' },
  CHUNKING: { text: '分块中', cls: 'blue' },
  INDEXING: { text: '索引中', cls: 'blue' },
  READY: { text: '就绪', cls: 'green' },
  FAILED: { text: '失败', cls: 'red' },
  DISABLED: { text: '已停用', cls: 'red' },
};

function seriesBadge(row) {
  return SERIES_STATUS[row.status] || { text: row.displayStatus || row.status, cls: 'blue' };
}

function docBadge(row) {
  return DOC_STATUS[row.status] || { text: row.status, cls: 'blue' };
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—';
}

function formatSize(bytes) {
  const size = Number(bytes || 0);
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

async function loadDepartments() {
  try {
    departments.value = (await api('/api/admin/departments')) || [];
  } catch (error) {
    departments.value = [];
  }
}

async function load(pageNo = page.value.page) {
  loading.value = true;
  try {
    const params = new URLSearchParams();
    if (filters.scope) params.set('scope', filters.scope);
    if (filters.keyword) params.set('keyword', filters.keyword);
    if (filters.status) params.set('status', filters.status);
    params.set('page', String(pageNo));
    params.set('size', String(page.value.size));
    page.value = (await api(`/api/kb/series?${params.toString()}`)) || page.value;
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function resetFilters() {
  Object.assign(filters, { scope: '', keyword: '', status: '' });
  load(1);
}

function openCreate() {
  Object.assign(form, {
    name: '',
    deptId: auth.user?.department_id ? String(auth.user.department_id) : '',
    description: '',
    indexStrategy: 'HYBRID',
    draft: false,
  });
  showForm.value = true;
}

async function createSeries() {
  if (saving.value) return;
  if (!form.name.trim()) {
    toast('请填写知识库名称', 'error');
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: form.name.trim(),
      description: form.description.trim(),
      indexStrategy: form.indexStrategy,
      draft: form.draft,
    };
    if (form.deptId) body.deptId = Number(form.deptId);
    const created = await api('/api/kb/series', { method: 'POST', body });
    toast('知识库已创建', 'success');
    showForm.value = false;
    await load(1);
    if (created && created.id) await openDetail(created);
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saving.value = false;
  }
}

async function openDetail(row) {
  detailLoading.value = true;
  detail.value = row;
  documents.value = [];
  summary.value = null;
  stats.value = null;
  try {
    detail.value = await api(`/api/kb/series/${row.id}`);
    await Promise.all([loadDocuments(row.id), loadOverview(row.id)]);
  } catch (error) {
    detail.value = null;
    toast(error.message, 'error');
  } finally {
    detailLoading.value = false;
  }
}

async function loadDocuments(seriesId = detail.value?.id) {
  if (!seriesId) return;
  docLoading.value = true;
  try {
    documents.value = (await api(`/api/kb/series/${seriesId}/documents`)) || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    docLoading.value = false;
  }
}

async function loadOverview(seriesId) {
  try {
    const [sum, st] = await Promise.all([
      api(`/api/kb/series/${seriesId}/documents/status-summary`),
      api(`/api/kb/series/${seriesId}/stats`),
    ]);
    summary.value = sum;
    stats.value = st;
  } catch (error) {
    summary.value = null;
    stats.value = null;
  }
}

async function refreshDetail() {
  if (!detail.value) return;
  const id = detail.value.id;
  detail.value = await api(`/api/kb/series/${id}`);
  await Promise.all([loadDocuments(id), loadOverview(id)]);
}

async function toggleSeries(row) {
  const enable = row.status !== 'ACTIVE';
  try {
    await api(`/api/kb/series/${row.id}/${enable ? 'enable' : 'disable'}`, { method: 'POST' });
    toast(enable ? '知识库已启用' : '知识库已停用', 'success');
    if (detail.value && detail.value.id === row.id) await refreshDetail();
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function removeSeries(row) {
  if (!window.confirm(`删除知识库「${row.name}」？该操作不可恢复。`)) return;
  try {
    await api(`/api/kb/series/${row.id}`, { method: 'DELETE' });
    toast('知识库已删除', 'success');
    if (detail.value && detail.value.id === row.id) detail.value = null;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function uploadFiles(event) {
  const files = Array.from(event.target.files || []);
  event.target.value = '';
  if (!files.length || !detail.value) return;
  uploading.value = true;
  try {
    const body = new FormData();
    files.forEach((file) => body.append('files', file));
    const response = await fetch(`/api/kb/series/${detail.value.id}/documents`, {
      method: 'POST',
      body,
      credentials: 'same-origin',
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok || (payload && payload.status === 'ERROR')) {
      throw new Error((payload && payload.message) || `上传失败：HTTP ${response.status}`);
    }
    const result = payload?.data;
    toast(
      `上传完成：成功 ${result?.uploaded ?? 0}，跳过 ${result?.skipped ?? 0}，失败 ${result?.failed ?? 0}`,
      result?.failed ? 'error' : 'success'
    );
    await refreshDetail();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    uploading.value = false;
  }
}

async function reparseDocument(doc) {
  try {
    await api(`/api/kb/series/${detail.value.id}/documents/${doc.id}/reparse`, { method: 'POST' });
    toast('已提交重新解析', 'success');
    await refreshDetail();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function removeDocument(doc) {
  if (!window.confirm(`删除文档「${doc.name}」？`)) return;
  try {
    await api(`/api/kb/series/${detail.value.id}/documents/${doc.id}`, { method: 'DELETE' });
    toast('文档已删除', 'success');
    await refreshDetail();
  } catch (error) {
    toast(error.message, 'error');
  }
}

function downloadDocument(doc) {
  window.open(`/api/kb/series/${detail.value.id}/documents/${doc.id}/download`, '_blank');
}

onMounted(() => {
  loadDepartments();
  load(1);
});
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>知识库</h2>
        <p>按系列组织制度与政策文档，解析分块后供对话检索引用；写操作按库级角色校验。</p>
      </div>
      <div class="toolbar">
        <button class="primary" @click="openCreate">新建知识库</button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <select v-model="filters.scope" style="width: 130px" @change="load(1)">
          <option value="">全部可见</option>
          <option value="mine">我创建的</option>
        </select>
        <select v-model="filters.status" style="width: 130px" @change="load(1)">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="ACTIVE">已启用</option>
          <option value="DISABLED">已停用</option>
        </select>
        <input
          v-model="filters.keyword"
          placeholder="名称或描述关键字"
          style="width: 220px"
          @keyup.enter="load(1)"
        />
        <button class="ghost sm" @click="resetFilters">重置</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>部门</th>
            <th>索引</th>
            <th>状态</th>
            <th>文档</th>
            <th>分块</th>
            <th>我的角色</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in page.items" :key="row.id">
            <td>
              <div>{{ row.name }}</div>
              <div class="muted" style="font-size: 12px">{{ row.description || '—' }}</div>
            </td>
            <td>{{ row.deptName || `部门#${row.deptId}` }}</td>
            <td class="mono">{{ row.indexStrategy }}</td>
            <td>
              <span class="badge" :class="seriesBadge(row).cls">{{ seriesBadge(row).text }}</span>
            </td>
            <td>
              {{ row.readyDocCount }} / {{ row.docCount }}
              <span v-if="row.failedDocCount" class="badge red">{{ row.failedDocCount }} 失败</span>
            </td>
            <td>{{ row.chunkCount }}</td>
            <td>{{ row.role || '—' }}</td>
            <td>{{ formatTime(row.updatedAt) }}</td>
            <td>
              <div class="cell-actions">
                <button class="subtle sm" @click="openDetail(row)">详情</button>
                <button class="subtle sm" @click="toggleSeries(row)">
                  {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
                <button class="subtle sm" @click="removeSeries(row)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!page.items.length">
            <td colspan="9" class="empty">{{ loading ? '加载中…' : '暂无知识库' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <span>共 {{ page.total }} 个，第 {{ page.page }} / {{ pages }} 页</span>
        <button class="ghost sm" :disabled="page.page <= 1" @click="load(page.page - 1)">上一页</button>
        <button class="ghost sm" :disabled="page.page >= pages" @click="load(page.page + 1)">下一页</button>
      </div>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width: min(640px, 96vw)">
        <div class="modal-head">
          <h3>新建知识库</h3>
          <button class="subtle sm" @click="showForm = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              名称
              <input v-model="form.name" maxlength="50" placeholder="薪酬制度与政策" />
            </label>
            <label class="field">
              所属部门
              <select v-model="form.deptId">
                <option value="">使用我的部门</option>
                <option v-for="dept in departments" :key="dept.id" :value="String(dept.id)">
                  {{ dept.name }}
                </option>
              </select>
            </label>
            <label class="field span-2">
              描述
              <textarea v-model="form.description" rows="3" maxlength="200"></textarea>
            </label>
            <label class="field">
              索引策略
              <select v-model="form.indexStrategy">
                <option value="HYBRID">HYBRID（向量 + 关键字）</option>
                <option value="VECTOR">VECTOR（仅向量）</option>
              </select>
            </label>
            <label class="field" style="justify-content: flex-end">
              <span><input v-model="form.draft" type="checkbox" /> 先保存为草稿</span>
            </label>
          </div>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showForm = false">取消</button>
          <button class="primary" :disabled="saving" @click="createSeries">
            {{ saving ? '创建中…' : '创建' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <aside class="drawer" style="width: min(880px, 96vw)">
        <div class="drawer-head">
          <h3>{{ detail.name }}</h3>
          <button class="subtle sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="detailLoading" class="empty">加载中…</div>
          <template v-else>
            <div class="panel-row">
              <span class="k">状态</span>
              <span class="v">
                <span class="badge" :class="seriesBadge(detail).cls">{{ seriesBadge(detail).text }}</span>
                <span class="muted"> · {{ detail.indexStrategy }} · {{ detail.role || '—' }}</span>
              </span>
            </div>
            <div class="panel-row"><span class="k">部门</span><span class="v">{{ detail.deptName }}</span></div>
            <div class="panel-row"><span class="k">创建人</span><span class="v">{{ detail.authorName }}</span></div>
            <div class="panel-row"><span class="k">描述</span><span class="v">{{ detail.description || '—' }}</span></div>
            <div class="panel-row">
              <span class="k">统计</span>
              <span class="v">
                文档 {{ stats?.documentCount ?? detail.docCount }}，就绪 {{ stats?.readyCount ?? detail.readyDocCount }}，
                失败 {{ stats?.failedCount ?? detail.failedDocCount }}，分块 {{ stats?.chunkCount ?? detail.chunkCount }}
              </span>
            </div>
            <div v-if="summary" class="panel-row">
              <span class="k">处理中</span>
              <span class="v">
                待处理 {{ summary.pending }} · 解析 {{ summary.parsing }} · 分块 {{ summary.chunking }} ·
                索引 {{ summary.indexing }} · 停用 {{ summary.disabled }}
              </span>
            </div>

            <div class="toolbar" style="margin-top: 16px">
              <button class="ghost sm" @click="toggleSeries(detail)">
                {{ detail.status === 'ACTIVE' ? '停用知识库' : '启用知识库' }}
              </button>
              <button class="ghost sm" :disabled="uploading" @click="fileInput?.click()">
                {{ uploading ? '上传中…' : '上传文档' }}
              </button>
              <button class="ghost sm" @click="loadDocuments()">刷新</button>
              <input
                ref="fileInput"
                type="file"
                multiple
                style="display: none"
                @change="uploadFiles"
              />
            </div>

            <div class="table-wrap" style="margin-top: 12px">
              <table class="table">
                <thead>
                  <tr>
                    <th>文档</th>
                    <th>类型</th>
                    <th>大小</th>
                    <th>分块</th>
                    <th>状态</th>
                    <th>上传人</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="doc in documents" :key="doc.id">
                    <td>
                      <div>{{ doc.name }}</div>
                      <div v-if="doc.errorMessage" class="muted" style="font-size: 12px">
                        {{ doc.errorCode }}：{{ doc.errorMessage }}
                      </div>
                    </td>
                    <td>{{ doc.ext }}</td>
                    <td>{{ formatSize(doc.sizeBytes) }}</td>
                    <td>{{ doc.chunkCount }}</td>
                    <td><span class="badge" :class="docBadge(doc).cls">{{ docBadge(doc).text }}</span></td>
                    <td>{{ doc.uploadedByName }}</td>
                    <td>
                      <div class="cell-actions">
                        <button class="subtle sm" @click="downloadDocument(doc)">下载</button>
                        <button class="subtle sm" @click="reparseDocument(doc)">重解析</button>
                        <button class="subtle sm" @click="removeDocument(doc)">删除</button>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="!documents.length">
                    <td colspan="7" class="empty">{{ docLoading ? '加载中…' : '暂无文档' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
        <div class="drawer-foot">
          <button class="ghost" @click="detail = null">关闭</button>
          <button class="danger" @click="removeSeries(detail)">删除知识库</button>
        </div>
      </aside>
    </div>
  </div>
</template>

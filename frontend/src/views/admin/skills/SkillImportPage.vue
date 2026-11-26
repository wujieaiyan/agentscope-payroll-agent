<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, ApiError } from '../../../api/http';
import { toast } from '../../../stores/toast';
import { formatSize } from '../../../utils/format';
import PanelCard from '../../../components/ui/PanelCard.vue';
import SegmentedTabs from '../../../components/ui/SegmentedTabs.vue';

const route = useRoute();
const router = useRouter();

const modeTabs = [
  { key: 'git', label: 'Git 仓库' },
  { key: 'zip', label: 'ZIP 包' },
];

const STATUS = {
  ADDED: { label: '新增', tone: 'ok' },
  REPLACED: { label: '覆盖', tone: 'warn' },
  UNCHANGED: { label: '不变', tone: 'muted' },
  UNKNOWN: { label: '未知', tone: 'muted' },
};

const GIT_SCRIPT_SUFFIX = /\.(py|sh|js)$/i;
const SENSITIVE = /(^|\/)(\.env(\..*)?|\.npmrc|\.pypirc|id_rsa|id_ed25519|.*\.(pem|key|pfx|p12)|.*(secret|credential).*)$/i;

const mode = ref(route.query.kind === 'zip' ? 'zip' : 'git');
const form = reactive({
  url: '',
  ref: '',
  id: typeof route.query.id === 'string' ? route.query.id : '',
  file: null,
  fileName: '',
  overwrite: false,
});

const fileInput = ref(null);
const attempted = ref(false);
const previewing = ref(false);
const importing = ref(false);
const preview = ref(null);
const existingDetail = ref(null);
const previewError = ref('');
const importError = ref('');
const conflictMessage = ref('');

const urlError = computed(() => {
  if (mode.value !== 'git') return '';
  const url = form.url.trim();
  if (!url) return '请填写 Git 地址';
  if (!/^https:\/\//i.test(url)) return 'Git 导入只支持 https 地址';
  return '';
});

const fileError = computed(() => {
  if (mode.value !== 'zip') return '';
  if (!form.file) return '请选择 ZIP 文件';
  if (!/\.zip$/i.test(form.file.name)) return '请选择 .zip 文件';
  return '';
});

const sourceReady = computed(() =>
  mode.value === 'git' ? !urlError.value : !fileError.value
);

const currentStep = computed(() => {
  if (importing.value) return 3;
  if (preview.value) return 2;
  return 1;
});

const steps = computed(() =>
  [
    { n: 1, label: '来源' },
    { n: 2, label: '预检' },
    { n: 3, label: '确认导入' },
  ].map((step) => ({
    ...step,
    on: step.n === currentStep.value,
    done: step.n < currentStep.value,
  }))
);

const totals = computed(() => preview.value?.totals || {});
const totalFiles = computed(() => Number(totals.value.files || 0));
const totalBytes = computed(() => Number(totals.value.bytes || 0));

const tree = computed(() => {
  const groups = new Map();
  for (const file of preview.value?.files || []) {
    const slash = file.path.indexOf('/');
    const dir = slash === -1 ? '' : file.path.slice(0, slash + 1);
    if (!groups.has(dir)) groups.set(dir, { key: dir || 'root', label: dir || '根目录', files: [] });
    const group = groups.get(dir);
    group.files.push({ ...file, name: dir ? file.path.slice(dir.length) : file.path });
  }
  return Array.from(groups.values()).sort((a, b) => {
    if (a.key === 'root') return -1;
    if (b.key === 'root') return 1;
    return a.key.localeCompare(b.key);
  });
});

const checks = computed(() => {
  if (!preview.value) return [];
  const files = preview.value.files || [];
  const hasSkillMd = files.some((file) => file.path === 'SKILL.md');
  const description = String(preview.value.description || '').trim();
  const scripts = files.filter((file) => file.path.startsWith('scripts/'));
  const badScripts = scripts.filter((file) => !GIT_SCRIPT_SUFFIX.test(file.path));
  const sensitive = files.filter((file) => SENSITIVE.test(file.path));
  return [
    {
      key: 'skill-md',
      label: 'SKILL.md 结构合法',
      ok: hasSkillMd && description.length > 0,
      detail: !hasSkillMd
        ? '缺少 SKILL.md'
        : description
          ? `${preview.value.id} · 说明 ${description.length} 字`
          : 'SKILL.md 未写说明',
    },
    {
      key: 'scripts',
      label: '脚本后缀合规',
      ok: badScripts.length === 0,
      detail: badScripts.length
        ? badScripts.slice(0, 3).map((file) => file.path).join('、')
        : scripts.length
          ? `${scripts.length} 个脚本均为 .py / .sh / .js`
          : '没有脚本文件',
    },
    {
      key: 'sensitive',
      label: '未发现敏感文件',
      ok: sensitive.length === 0,
      detail: sensitive.length
        ? sensitive.slice(0, 3).map((file) => file.path).join('、')
        : '未发现 .env / 私钥 / 凭据文件',
    },
    {
      key: 'setup',
      label: 'setup 联网提示',
      ok: true,
      info: true,
      detail: preview.value.setupCommand
        ? 'setup 会在沙箱初始化时联网安装依赖，确认来源可信'
        : '未声明 setup，导入后无需初始化数据',
    },
  ];
});

const failedChecks = computed(() => checks.value.filter((check) => !check.ok && !check.info));

const sourceLabel = computed(() => {
  const source = preview.value?.source || {};
  if (source.kind === 'zip') return form.fileName || 'ZIP 包';
  const ref = source.ref ? ` · ${source.ref}` : '';
  return `Git 仓库${ref}`;
});

const shortSha = computed(() => {
  const sha = preview.value?.source?.resolvedSha || preview.value?.commitSha || '';
  return sha ? sha.slice(0, 8) : '—';
});

const elapsedLabel = computed(() => {
  const millis = Number(preview.value?.elapsedMillis || 0);
  if (millis < 1) return '—';
  return millis >= 1000 ? `${(millis / 1000).toFixed(1)}s` : `${millis}ms`;
});

const existingVersionNo = computed(() => {
  const value = preview.value?.currentVersionNo;
  return Number.isFinite(value) && value > 0 ? value : null;
});

const existingWarning = computed(() => {
  if (!preview.value?.existing) return '';
  const replaced = Number(totals.value.replaced || 0);
  if (existingVersionNo.value) {
    return `技能 ${preview.value.id} 已存在（当前 v${existingVersionNo.value}），导入会生成 v${existingVersionNo.value + 1} 并覆盖 ${replaced} 个文件。`;
  }
  return `技能 ${preview.value.id} 已存在，导入会覆盖 ${replaced} 个文件并生成新版本。`;
});

const overwriteBlocked = computed(() => Boolean(preview.value?.existing) && !form.overwrite);

const confirmLabel = computed(() => {
  if (importing.value) return '导入中…';
  if (existingVersionNo.value) return `确认导入并生成 v${existingVersionNo.value + 1}`;
  return '确认导入';
});

const canImport = computed(
  () =>
    Boolean(preview.value) &&
    !importing.value &&
    !previewing.value &&
    !failedChecks.value.length &&
    !overwriteBlocked.value
);

function statusMeta(status) {
  return STATUS[status] || STATUS.UNKNOWN;
}

function resetPreview() {
  preview.value = null;
  existingDetail.value = null;
  previewError.value = '';
  importError.value = '';
  conflictMessage.value = '';
}

watch(mode, () => {
  attempted.value = false;
  resetPreview();
});

function onFileChange(event) {
  const file = (event.target.files || [])[0] || null;
  form.file = file;
  form.fileName = file ? file.name : '';
  resetPreview();
}

function pickSourceErrors() {
  attempted.value = true;
  if (!sourceReady.value) toast(mode.value === 'git' ? urlError.value : fileError.value, 'error');
  return sourceReady.value;
}

async function postForm(path, formData) {
  const response = await fetch(path, {
    method: 'POST',
    body: formData,
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (response.status === 401) {
    window.location.href = '/login';
    throw new ApiError('未登录或会话已过期', 401);
  }
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (error) {
      payload = { status: 'ERROR', message: text };
    }
  }
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    throw new ApiError(
      (payload && payload.message) || `导入失败：HTTP ${response.status}`,
      response.status,
      (payload && payload.data) || null
    );
  }
  return payload ? payload.data : null;
}

/** 预检永远按「允许覆盖」发，否则同名技能会直接被服务端 409 掉，看不到文件差异。 */
async function sendPreview() {
  if (mode.value === 'git') {
    return api('/api/admin/skills/import/git', {
      method: 'POST',
      body: {
        url: form.url.trim(),
        ref: form.ref.trim() || null,
        id: form.id.trim() || null,
        overwrite: true,
        dryRun: true,
      },
    });
  }
  const body = new FormData();
  body.append('file', form.file);
  if (form.id.trim()) body.append('id', form.id.trim());
  body.append('overwrite', 'true');
  body.append('dryRun', 'true');
  return postForm('/api/admin/skills/import/zip', body);
}

async function runPreview() {
  if (previewing.value || !pickSourceErrors()) return;
  previewing.value = true;
  previewError.value = '';
  conflictMessage.value = '';
  importError.value = '';
  try {
    const result = await sendPreview();
    preview.value = result;
    existingDetail.value = null;
    if (result?.existing && result?.id) {
      existingDetail.value = await api(`/api/admin/skills/${encodeURIComponent(result.id)}`).catch(
        () => null
      );
    }
  } catch (caught) {
    preview.value = null;
    previewError.value = caught.message;
  } finally {
    previewing.value = false;
  }
}

async function sendImport() {
  const revision = existingDetail.value?.revision;
  if (mode.value === 'git') {
    const query = revision ? `?revision=${encodeURIComponent(revision)}` : '';
    return api(`/api/admin/skills/import/git${query}`, {
      method: 'POST',
      body: {
        url: form.url.trim(),
        ref: form.ref.trim() || null,
        id: form.id.trim() || null,
        overwrite: Boolean(form.overwrite),
        dryRun: false,
      },
    });
  }
  const body = new FormData();
  body.append('file', form.file);
  if (form.id.trim()) body.append('id', form.id.trim());
  body.append('overwrite', String(Boolean(form.overwrite)));
  body.append('dryRun', 'false');
  if (revision) body.append('revision', revision);
  return postForm('/api/admin/skills/import/zip', body);
}

async function runImport() {
  if (!canImport.value) return;
  importing.value = true;
  importError.value = '';
  conflictMessage.value = '';
  try {
    const result = await sendImport();
    const id = result?.id || preview.value?.id || '';
    const versionNo = Number(result?.currentVersionNo || 0);
    toast(
      versionNo > 0 ? `已导入「${id}」，当前 v${versionNo}` : `技能「${id}」已导入`,
      'success'
    );
    router.push({ name: 'skill-workbench', params: { id, tab: 'overview' } });
  } catch (caught) {
    if (caught instanceof ApiError && caught.status === 409) {
      conflictMessage.value = `${caught.message}。同名技能在预检后可能已被更新，请重新预检。`;
    } else {
      importError.value = caught.message;
    }
  } finally {
    importing.value = false;
  }
}

function editSource() {
  if (importing.value) return;
  resetPreview();
}
</script>

<template>
  <div class="page skills-canvas import-page">
    <nav class="crumb" aria-label="面包屑">
      <router-link :to="'/platform/skills/new'" class="crumb-back">← 新建技能</router-link>
      <span class="crumb-sep" aria-hidden="true">/</span>
      <span class="crumb-current">导入项目</span>
    </nav>

    <header class="import-head">
      <h1 tabindex="-1">检查导入内容</h1>
      <p class="import-sub">预检不会写入任何数据，确认后再导入。</p>
    </header>

    <div class="steps" aria-label="导入步骤">
      <template v-for="(step, index) in steps" :key="step.n">
        <span class="step" :class="{ on: step.on, done: step.done }">
          <span class="n">{{ step.done ? '✓' : step.n }}</span>{{ step.label }}
        </span>
        <span v-if="index < steps.length - 1" class="line" aria-hidden="true" />
      </template>
    </div>

    <section v-if="!preview" class="panel-card source-card">
      <header class="panel-card-head">
        <div class="panel-card-title">
          <h3>来源</h3>
          <span class="hint">Git 仓库或 ZIP 项目包，需包含 SKILL.md</span>
        </div>
      </header>
      <div class="panel-card-body source-body">
        <SegmentedTabs v-model="mode" :tabs="modeTabs" aria-label="导入来源" class="src-tabs" />

        <template v-if="mode === 'git'">
          <label class="field">
            <span class="field-label">Git 地址</span>
            <input
              v-model="form.url"
              class="field-input mono"
              placeholder="https://github.com/org/skill.git"
              autocomplete="off"
              spellcheck="false"
            />
            <span v-if="attempted && urlError" class="field-error">{{ urlError }}</span>
          </label>
          <div class="field-row">
            <label class="field">
              <span class="field-label">分支 / 标签（可选）</span>
              <input v-model="form.ref" class="field-input mono" placeholder="main" />
            </label>
            <label class="field">
              <span class="field-label">技能 ID（可选）</span>
              <input v-model="form.id" class="field-input mono" placeholder="留空按 SKILL.md 生成" />
            </label>
          </div>
        </template>

        <template v-else>
          <div class="field">
            <span class="field-label">ZIP 文件</span>
            <div class="file-pick">
              <button class="ghost sm" type="button" @click="fileInput?.click()">
                {{ form.file ? '重新选择' : '选择 ZIP 文件' }}
              </button>
              <span v-if="form.file" class="file-picked mono">
                {{ form.fileName }} · {{ formatSize(form.file.size) }}
              </span>
            </div>
            <input
              ref="fileInput"
              type="file"
              accept=".zip,application/zip"
              class="sr-only"
              @change="onFileChange"
            />
            <span v-if="attempted && fileError" class="field-error">{{ fileError }}</span>
          </div>
          <label class="field">
            <span class="field-label">技能 ID（可选）</span>
            <input v-model="form.id" class="field-input mono" placeholder="留空按包内目录名生成" />
          </label>
        </template>

        <label class="overwrite-row">
          <input v-model="form.overwrite" type="checkbox" />
          <span>覆盖同名技能（会生成新版本，可在版本页回滚）</span>
        </label>

        <p v-if="previewError" class="source-error" role="alert">{{ previewError }}</p>

        <div class="source-foot">
          <span class="hint">
            预检只解析、不落库；Git 拉取与 ZIP 解压都受现有大小与条目上限约束。
          </span>
          <button class="primary" type="button" :disabled="previewing" @click="runPreview">
            {{ previewing ? '预检中…' : '下一步：预检' }}
          </button>
        </div>
      </div>
    </section>

    <div v-else class="skill-import-grid">
      <div class="import-main">
        <PanelCard title="来源" :flush="true">
          <template #actions>
            <button class="link-btn" type="button" :disabled="importing" @click="editSource">修改</button>
          </template>
          <div class="src-card">
            <span class="src-icon" aria-hidden="true">
              <svg viewBox="0 0 20 20">
                <path v-if="preview.source?.kind === 'git'" d="M7 4.5v11M13 4.5v5a3 3 0 0 1-3 3H7" />
                <path v-if="preview.source?.kind === 'git'" d="M5.5 4.5a1.5 1.5 0 1 0 3 0 1.5 1.5 0 1 0-3 0M5.5 15.5a1.5 1.5 0 1 0 3 0 1.5 1.5 0 1 0-3 0M11.5 4.5a1.5 1.5 0 1 0 3 0 1.5 1.5 0 1 0-3 0" />
                <path v-else d="M3 5.5h5l1.5 2H17v9H3z" />
                <path v-if="preview.source?.kind !== 'git'" d="M10 9v5M7.8 11.2 10 13.4l2.2-2.2" />
              </svg>
            </span>
            <div class="src-text">
              <strong>{{ sourceLabel }}</strong>
              <span class="src-meta">
                <span class="mono">{{ shortSha }}</span>
                <span class="dot" aria-hidden="true" />
                <span>预检耗时 {{ elapsedLabel }}</span>
              </span>
            </div>
          </div>
        </PanelCard>

        <p v-if="preview.existing" class="warn-bar" role="alert">{{ existingWarning }}</p>

        <PanelCard title="将写入的文件" :flush="true">
          <template #hint>
            <span class="hint">{{ totalFiles }} 个 · {{ formatSize(totalBytes) }}</span>
          </template>
          <div class="status-legend">
            <span class="badge b-ok">新增 {{ totals.added || 0 }}</span>
            <span class="badge b-warn">覆盖 {{ totals.replaced || 0 }}</span>
            <span class="badge b-muted">不变 {{ totals.unchanged || 0 }}</span>
            <span v-if="totals.unknown" class="badge b-muted">未知 {{ totals.unknown }}</span>
          </div>
          <div class="file-groups">
            <div v-for="group in tree" :key="group.key" class="file-group">
              <div class="file-group-head">
                <span class="mono">{{ group.label }}</span>
                <span class="hint">{{ group.files.length }} 个</span>
              </div>
              <ul class="file-rows">
                <li v-for="file in group.files" :key="file.path">
                  <span class="file-name mono" :title="file.path">{{ file.name }}</span>
                  <span class="file-size">{{ formatSize(file.size) }}</span>
                  <span class="badge" :class="`b-${statusMeta(file.status).tone}`">
                    {{ statusMeta(file.status).label }}
                  </span>
                </li>
              </ul>
            </div>
          </div>
        </PanelCard>

        <PanelCard title="预检结果">
          <template #hint>
            <span class="hint">{{ failedChecks.length ? `${failedChecks.length} 项待处理` : '全部通过' }}</span>
          </template>
          <ul class="checks">
            <li
              v-for="check in checks"
              :key="check.key"
              class="check"
              :class="{ bad: !check.ok, info: check.info }"
            >
              <span class="check-dot" aria-hidden="true" />
              <div class="check-text">
                <strong>{{ check.label }}</strong>
                <span>{{ check.detail }}</span>
              </div>
            </li>
          </ul>
          <p v-if="preview.warnings?.length" class="warn-text">
            解析告警：{{ preview.warnings.join('；') }}
          </p>
        </PanelCard>
      </div>

      <aside class="import-rail" aria-label="导入摘要">
        <PanelCard title="导入摘要">
          <dl class="rail-dl">
            <div><dt>ID</dt><dd class="mono">{{ preview.id }}</dd></div>
            <div><dt>说明</dt><dd>{{ preview.description || '—' }}</dd></div>
            <div><dt>setup</dt><dd class="mono">{{ preview.setupCommand || '—' }}</dd></div>
            <div><dt>数据上限</dt><dd>{{ formatSize(preview.dataMaxBytes) }}</dd></div>
          </dl>

          <div class="stat-row">
            <div class="stat"><span class="n ok">{{ totals.added || 0 }}</span><span>新增</span></div>
            <div class="stat"><span class="n warn">{{ totals.replaced || 0 }}</span><span>覆盖</span></div>
            <div class="stat"><span class="n">{{ totals.unchanged || 0 }}</span><span>不变</span></div>
          </div>

          <label v-if="preview.existing" class="overwrite-row rail-overwrite">
            <input v-model="form.overwrite" type="checkbox" :disabled="importing" />
            <span>覆盖同名技能{{ existingVersionNo ? `（生成 v${existingVersionNo + 1}）` : '' }}</span>
          </label>

          <p v-if="conflictMessage" class="rail-error" role="alert">
            {{ conflictMessage }}
            <button class="subtle sm" type="button" :disabled="importing" @click="runPreview">重新预检</button>
          </p>
          <p v-else-if="importError" class="rail-error" role="alert">{{ importError }}</p>
          <p v-if="failedChecks.length" class="rail-hint bad">
            有 {{ failedChecks.length }} 项预检未通过，请先回到来源修正。
          </p>
          <p v-else-if="overwriteBlocked" class="rail-hint warn">勾选「覆盖同名技能」后才能导入。</p>

          <button class="primary rail-go" type="button" :disabled="!canImport" @click="runImport">
            {{ confirmLabel }}
          </button>
          <button class="ghost rail-back" type="button" :disabled="importing" @click="editSource">
            返回修改来源
          </button>
          <p v-if="importing" class="rail-hint">正在写入文件与版本，请稍候…</p>
        </PanelCard>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.import-page {
  padding-bottom: 64px;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--muted);
}
.crumb-back {
  color: var(--primary);
  text-decoration: none;
}
.crumb-back:hover {
  text-decoration: underline;
}
.crumb-sep {
  color: var(--faint);
}
.crumb-current {
  color: var(--text-soft);
}

.import-head {
  margin-top: 2px;
}
.import-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 650;
}
.import-head h1:focus {
  outline: none;
}
.import-sub {
  margin: 0;
  font-size: 12.5px;
  color: var(--muted);
}

.steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 16px 0;
}
.step {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--muted);
}
.step .n {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
}
.step.done .n {
  border-color: var(--st-ok);
  color: var(--st-ok);
}
.step.on {
  color: var(--text);
  font-weight: 600;
}
.step.on .n {
  border-color: var(--text);
  background: var(--text);
  color: #fff;
}
.line {
  width: 26px;
  height: 1px;
  background: var(--border-strong);
}

.source-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 720px;
}
.src-tabs {
  align-self: flex-start;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-soft);
}
.field-input {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: var(--surface);
  font-size: 13px;
}
.field-input:focus {
  outline: 2px solid rgb(47 111 235 / 18%);
  border-color: var(--primary);
}
.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.field-error {
  font-size: 11.5px;
  color: var(--st-bad);
}
.file-pick {
  display: flex;
  align-items: center;
  gap: 10px;
}
.file-picked {
  font-size: 12px;
  color: var(--text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.overwrite-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-soft);
}
.source-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  font-size: 12.5px;
  color: var(--st-bad);
}
.source-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}
.source-foot .hint {
  flex: 1;
  min-width: 240px;
}

.import-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.src-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}
.src-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 10px;
  background: var(--primary-soft);
  color: var(--primary-dark);
}
.src-icon svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentcolor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.src-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.src-text strong {
  font-size: 13.5px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.src-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--muted);
}
.src-meta .dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--faint);
}

.warn-bar {
  margin: 0;
  padding: 10px 14px;
  border: 1px solid #f0d9a8;
  border-radius: 8px;
  background: var(--st-warn-soft, #fff6e3);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--st-warn);
}

.status-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
}
.badge {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}
.b-ok {
  background: #e8f5ee;
  color: #18794e;
}
.b-warn {
  background: var(--st-warn-soft, #fff6e3);
  color: var(--st-warn);
}
.b-muted {
  background: var(--surface-3);
  color: var(--muted);
}

.file-groups {
  max-height: 420px;
  overflow: auto;
  padding: 8px 16px 12px;
}
.file-group + .file-group {
  margin-top: 10px;
}
.file-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 2px;
  font-size: 11.5px;
  color: var(--faint);
}
.file-rows {
  margin: 0;
  padding: 0;
  list-style: none;
}
.file-rows li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 6px;
  border-radius: 6px;
  font-size: 12.5px;
}
.file-rows li:hover {
  background: var(--surface-2);
}
.file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-soft);
}
.file-size {
  flex: none;
  font-size: 11px;
  color: var(--faint);
  font-variant-numeric: tabular-nums;
}

.checks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.check {
  display: flex;
  gap: 10px;
}
.check-dot {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--st-ok);
}
.check.bad .check-dot {
  background: var(--st-bad);
}
.check.info .check-dot {
  background: var(--faint);
}
.check-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 12.5px;
}
.check-text strong {
  font-weight: 600;
  color: var(--text-soft);
}
.check.bad .check-text strong {
  color: var(--st-bad);
}
.check-text span {
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--muted);
  word-break: break-word;
}
.warn-text {
  margin: 12px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--st-warn);
}

.import-rail {
  position: sticky;
  top: 16px;
  min-width: 0;
}
.rail-dl {
  display: grid;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 12.5px;
}
.rail-dl > div {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr);
  gap: 10px;
}
.rail-dl dt {
  color: var(--muted);
}
.rail-dl dd {
  margin: 0;
  color: var(--text-soft);
  word-break: break-word;
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding: 10px 0 14px;
  border-top: 1px solid var(--border);
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: center;
  font-size: 11px;
  color: var(--muted);
}
.stat .n {
  font-size: 17px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--text);
}
.stat .n.ok {
  color: #18794e;
}
.stat .n.warn {
  color: var(--st-warn);
}
.rail-overwrite {
  padding-bottom: 12px;
}
.rail-error {
  margin: 0 0 10px;
  padding: 8px 10px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--st-bad);
}
.rail-hint {
  margin: 0 0 10px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--muted);
}
.rail-hint.warn {
  color: var(--st-warn);
}
.rail-hint.bad {
  color: var(--st-bad);
}
.rail-go {
  width: 100%;
  justify-content: center;
}
.rail-back {
  width: 100%;
  justify-content: center;
  margin-top: 8px;
}

@media (max-width: 1080px) {
  .import-rail {
    position: static;
  }
}
@media (max-width: 820px) {
  .field-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

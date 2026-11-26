<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../../../api/http';
import { formatCount, formatTimestamp } from '../../../utils/format';
import SkillTemplatePicker from './SkillTemplatePicker.vue';

const router = useRouter();

const templates = ref([]);
const recent = ref([]);
const loading = ref(false);
const error = ref('');
const pickerOpen = ref(false);
const pickerReadonly = ref(false);

const categories = computed(() => {
  const seen = [];
  for (const template of templates.value) {
    if (template.category && !seen.includes(template.category)) seen.push(template.category);
  }
  return seen.length ? seen.slice(0, 4) : ['政策口径', '处理流程', '写作规范', '数据分析'];
});

async function loadTemplates() {
  loading.value = true;
  error.value = '';
  try {
    const payload = (await api('/api/admin/skills/templates')) || {};
    templates.value = payload.templates || [];
    recent.value = payload.recent || [];
  } catch (caught) {
    error.value = caught.message;
  } finally {
    loading.value = false;
  }
}

function openPicker(readonly = false) {
  pickerReadonly.value = readonly;
  pickerOpen.value = true;
}

function chooseTemplate(template) {
  pickerOpen.value = false;
  router.push({ path: '/platform/skills/new/editor', query: { templateId: template.id } });
}

function startBlank() {
  router.push('/platform/skills/new/editor');
}

function startImport() {
  router.push('/platform/skills/import');
}

function useRecent(template) {
  chooseTemplate(template);
}

function cancel() {
  router.push('/platform/skills');
}

onMounted(loadTemplates);
</script>

<template>
  <div class="page skills-canvas create-page">
    <header class="create-head">
      <div>
        <h1 tabindex="-1">新建技能</h1>
        <p class="create-sub">先选起点，之后都能改：正文、文件、数据、绑定都在工作台里。</p>
      </div>
      <button class="ghost" type="button" @click="cancel">取消</button>
    </header>

    <div class="steps" aria-label="新建步骤">
      <span class="step on"><span class="n">1</span>选择起点</span>
      <span class="line" aria-hidden="true"></span>
      <span class="step"><span class="n">2</span>填写与检查</span>
      <span class="line" aria-hidden="true"></span>
      <span class="step"><span class="n">3</span>完成</span>
    </div>

    <div class="path-grid">
      <article class="path-card">
        <span class="pc-icon">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M5 2.5h6.5L15 6v11.5H5z" />
            <path d="M11.5 2.5V6H15M7.5 10h5M7.5 13h5" />
          </svg>
        </span>
        <h2 class="pc-title">从空白创建</h2>
        <p class="pc-desc">只写一段 Markdown，适合解释口径、规范与话术。</p>
        <ul class="pc-list">
          <li><i aria-hidden="true">✓</i>约 1 分钟完成</li>
          <li><i aria-hidden="true">✓</i>可稍后补文件与数据</li>
        </ul>
        <button class="pc-go" type="button" @click="startBlank">开始填写<span aria-hidden="true">→</span></button>
      </article>

      <article class="path-card recommended">
        <span class="pc-icon">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M4 3.5h12v13H4z" />
            <path d="M7 7h6M7 10h6M7 13h4" />
          </svg>
        </span>
        <div class="pc-title-row">
          <h2 class="pc-title">从模板创建</h2>
          <span class="badge">推荐</span>
        </div>
        <p class="pc-desc">带结构的骨架与合格标准，先看四类模板再决定。</p>
        <ul class="pc-list">
          <li v-for="category in categories" :key="category"><i aria-hidden="true">·</i>{{ category }}</li>
        </ul>
        <button class="pc-go" type="button" :disabled="loading" @click="openPicker(false)">
          选择模板<span aria-hidden="true">→</span>
        </button>
      </article>

      <article class="path-card">
        <span class="pc-icon">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M3 5.5h5l1.5 2H17v9H3z" />
            <path d="M10 9v5M7.8 11.2 10 13.4l2.2-2.2" />
          </svg>
        </span>
        <h2 class="pc-title">导入项目</h2>
        <p class="pc-desc">从 Git 仓库或 ZIP 导入完整技能包，预检文件树与覆盖差异。</p>
        <ul class="pc-list">
          <li><i aria-hidden="true">✓</i>先预检，确认后才写入</li>
          <li><i aria-hidden="true">✓</i>支持 SKILL.md 项目结构</li>
        </ul>
        <button class="pc-go" type="button" @click="startImport">开始导入<span aria-hidden="true">→</span></button>
      </article>
    </div>

    <section class="recent-panel" aria-label="最近使用的模板">
      <header class="recent-head">
        <strong>最近使用的模板</strong>
        <button class="link-btn" type="button" @click="openPicker(true)">管理模板</button>
      </header>
      <p v-if="error" class="recent-error" role="alert">
        {{ error }}
        <button class="subtle sm" type="button" @click="loadTemplates">重试</button>
      </p>
      <p v-else-if="loading" class="recent-empty">加载模板…</p>
      <p v-else-if="!recent.length" class="recent-empty">
        还没有用过模板。选择一次后会出现在这里，方便下次快速复用。
      </p>
      <ul v-else class="recent-list">
        <li v-for="template in recent" :key="template.id">
          <div class="recent-main">
            <strong>{{ template.title }}</strong>
            <span class="recent-meta">
              {{ template.category || '通用' }} · 使用 {{ template.usedCount || 0 }} 次
              <template v-if="template.lastUsedAt"> · {{ formatTimestamp(template.lastUsedAt) }}</template>
            </span>
          </div>
          <span class="recent-len mono">{{ formatCount(template.bodyLength || 0) }} 字</span>
          <button class="ghost sm" type="button" @click="useRecent(template)">使用</button>
        </li>
      </ul>
    </section>

    <p class="create-tip">
      参考文件、脚本与数据不在这一步填写：保存后进入工作台，在对应页签补充；导入技能包会一次带齐。内置技能也能改，保存正文会生成覆盖版本，不改动原始内置内容。
    </p>

    <SkillTemplatePicker
      :open="pickerOpen"
      :templates="templates"
      :loading="loading"
      :error="error"
      :readonly="pickerReadonly"
      @choose="chooseTemplate"
      @close="pickerOpen = false"
    />
  </div>
</template>

<style scoped>
.create-page {
  padding-bottom: 64px;
}
.create-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 4px;
}
.create-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 650;
}
.create-head h1:focus {
  outline: none;
}
.create-sub {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--muted);
}
.steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 18px 0;
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
.path-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.path-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius, 12px);
  background: var(--surface);
  box-shadow: 0 1px 2px rgb(16 24 40 / 4%);
}
.path-card.recommended {
  border-color: #c9d6f4;
}
.pc-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: var(--surface-3);
  color: var(--text-soft);
}
.pc-icon svg {
  width: 21px;
  height: 21px;
  fill: none;
  stroke: currentcolor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.pc-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pc-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.badge {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary-dark, #2457c4);
  font-size: 10.5px;
  font-weight: 600;
}
.pc-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}
.pc-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 11.5px;
  color: var(--text-soft);
}
.pc-list li {
  display: flex;
  gap: 7px;
}
.pc-list i {
  flex: none;
  font-style: normal;
  font-weight: 700;
  color: var(--st-ok);
}
.pc-go {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: auto;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--primary);
  cursor: pointer;
}
.pc-go:disabled {
  color: var(--faint);
  cursor: default;
}
.recent-panel {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius, 12px);
  background: var(--surface);
}
.recent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 13px;
}
.recent-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.recent-list li {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--surface-2);
}
.recent-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 12.5px;
}
.recent-meta {
  font-size: 11.5px;
  color: var(--muted);
}
.recent-len {
  flex: none;
  font-size: 11.5px;
  color: var(--faint);
}
.recent-empty {
  margin: 0;
  padding: 12px 0;
  font-size: 12px;
  color: var(--muted);
}
.recent-error {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  padding: 8px 0;
  font-size: 12px;
  color: var(--st-bad);
}
.create-tip {
  margin: 16px 0 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--surface-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}
@media (max-width: 1080px) {
  .path-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 820px) {
  .path-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

<script setup>
import { ref } from 'vue';
import { useModalFocus } from '../../../composables/useModalFocus';
import { formatCount, formatTimestamp } from '../../../utils/format';

const props = defineProps({
  open: { type: Boolean, default: false },
  templates: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  readonly: { type: Boolean, default: false },
});

const emit = defineEmits(['choose', 'close']);

const panelRef = ref(null);

useModalFocus(panelRef, {
  active: () => props.open,
  onEscape: () => emit('close'),
});
</script>

<template>
  <div v-if="open" class="modal-mask" @click.self="emit('close')">
    <div
      ref="panelRef"
      class="tpl-panel"
      role="dialog"
      aria-modal="true"
      aria-label="选择模板"
      tabindex="-1"
    >
      <header class="tpl-head">
        <div>
          <h2>{{ readonly ? '技能模板' : '选择模板' }}</h2>
          <p class="hint">
            {{ readonly ? '模板是代码内置资源，本期只读；新建时会带出正文与说明。' : '选择后可继续修改正文、说明与文件，保存才落库。' }}
          </p>
        </div>
        <button class="btn-icon" type="button" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <div class="tpl-body">
        <p v-if="loading" class="hint tpl-state">加载模板…</p>
        <div v-else-if="error" class="tpl-error" role="alert">
          <span>{{ error }}</span>
        </div>
        <ul v-else-if="templates.length" class="tpl-list">
          <li v-for="template in templates" :key="template.id" class="tpl-row">
            <div class="tpl-main">
              <div class="tpl-title">
                <strong>{{ template.title }}</strong>
                <span class="tag">{{ template.category || '通用' }}</span>
              </div>
              <p class="tpl-desc">{{ template.description || '暂无说明' }}</p>
              <p class="tpl-meta">
                <span class="mono">{{ template.id }}</span>
                <span>正文 {{ formatCount(template.bodyLength || 0) }} 字</span>
                <span>使用 {{ template.usedCount || 0 }} 次</span>
                <span v-if="template.lastUsedAt">最近 {{ formatTimestamp(template.lastUsedAt) }}</span>
              </p>
            </div>
            <button
              v-if="!readonly"
              class="ghost sm"
              type="button"
              @click="emit('choose', template)"
            >
              使用
            </button>
          </li>
        </ul>
        <p v-else class="hint tpl-state">还没有可用模板。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 66;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgb(15 23 42 / 45%);
}
.tpl-panel {
  display: flex;
  flex-direction: column;
  width: min(640px, 100%);
  max-height: calc(100vh - 48px);
  border-radius: var(--radius, 12px);
  background: var(--surface, #fff);
  box-shadow: var(--shadow-lift, 0 12px 32px rgb(16 24 40 / 12%));
}
.tpl-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px 12px;
}
.tpl-head h2 {
  margin: 0 0 4px;
  font-size: 17px;
}
.tpl-body {
  padding: 0 22px 18px;
  overflow: auto;
}
.tpl-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.tpl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm, 8px);
  background: var(--surface-2);
}
.tpl-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.tpl-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tpl-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--muted);
}
.tpl-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0;
  font-size: 11.5px;
  color: var(--faint);
}
.tpl-state {
  padding: 24px 0;
  text-align: center;
}
.tpl-error {
  margin: 12px 0;
  padding: 10px 12px;
  border: 1px solid #f3d3d3;
  border-radius: 8px;
  background: var(--st-bad-soft, #fdecec);
  color: var(--st-bad, #c92a2a);
  font-size: 12.5px;
}
.hint {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--muted);
}
</style>

<script setup>
import { computed } from 'vue';
import PanelCard from '../../../../components/ui/PanelCard.vue';
import { toast } from '../../../../stores/toast';
import { formatCount } from '../../../../utils/format';

const props = defineProps({
  detail: { type: Object, required: true },
  writable: { type: Boolean, default: false },
});
const emit = defineEmits(['edit']);

const canEdit = computed(
  () => props.writable && props.detail?.source !== 'builtin'
);
const reason = computed(() => {
  if (!props.writable) return '当前存储只读';
  if (props.detail?.source === 'builtin') return '内置技能尚未落库，无法编辑';
  return '';
});
const hint = computed(() => {
  if (!props.writable) return '当前存储只读，编辑入口已禁用。';
  if (props.detail?.source === 'builtin') {
    return '内置技能可覆盖：保存后会生成自定义版本并标记「已覆盖」。';
  }
  if (props.detail?.source === 'nacos') {
    return 'Nacos 源不提供版本时间线，保存会直接发布到 Nacos。';
  }
  return '';
});
const length = computed(() => String(props.detail?.content || '').length);

async function copyBody() {
  const text = String(props.detail?.content || '');
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    toast('正文已复制', 'success');
  } catch (caught) {
    toast('复制失败，请手动选择正文', 'error');
  }
}
</script>

<template>
  <PanelCard title="正文">
    <template #hint>
      <span class="hint">{{ formatCount(length) }} 字符</span>
    </template>
    <template #actions>
      <div class="body-actions">
        <button class="ghost sm" type="button" :disabled="!length" @click="copyBody">复制</button>
        <button class="primary" type="button" :disabled="!canEdit" :title="reason" @click="emit('edit')">
          编辑正文
        </button>
      </div>
    </template>
    <p v-if="hint" class="body-hint">{{ hint }}</p>
    <pre class="skill-body">{{ detail.content || '（空）' }}</pre>
  </PanelCard>
</template>

<style scoped>
.body-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.body-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--muted);
}
.skill-body {
  margin: 0;
  max-height: 70vh;
  overflow: auto;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

<script setup>
import { nextTick, ref, useId } from 'vue';

const props = defineProps({
  tabs: { type: Array, default: () => [] },
  modelValue: { type: String, default: '' },
  ariaLabel: { type: String, default: '页签' },
});
const emit = defineEmits(['update:modelValue']);

const listEl = ref(null);
const baseId = useId();

function select(tab) {
  if (!tab || tab.disabled || tab.key === props.modelValue) return;
  emit('update:modelValue', tab.key);
}

function focusTab(index) {
  const buttons = listEl.value?.querySelectorAll('[role="tab"]') || [];
  buttons[index]?.focus();
}

function enabledIndex(from, step) {
  const count = props.tabs.length;
  for (let offset = 1; offset <= count; offset += 1) {
    const index = (from + step * offset + count * count) % count;
    if (!props.tabs[index]?.disabled) return index;
  }
  return -1;
}

function onKeydown(event) {
  const current = props.tabs.findIndex((tab) => tab.key === props.modelValue);
  let next = -1;
  if (event.key === 'ArrowRight') next = enabledIndex(current, 1);
  else if (event.key === 'ArrowLeft') next = enabledIndex(current, -1);
  else if (event.key === 'Home') next = enabledIndex(-1, 1);
  else if (event.key === 'End') next = enabledIndex(props.tabs.length, -1);
  else return;
  event.preventDefault();
  if (next < 0) return;
  select(props.tabs[next]);
  const index = next;
  nextTick(() => focusTab(index));
}
</script>

<template>
  <div
    ref="listEl"
    class="seg-tabs"
    role="tablist"
    :aria-label="ariaLabel"
    @keydown="onKeydown"
  >
    <button
      v-for="tab in tabs"
      :id="`${baseId}-tab-${tab.key}`"
      :key="tab.key"
      type="button"
      role="tab"
      :aria-selected="tab.key === modelValue"
      :aria-controls="tab.controls"
      :tabindex="tab.key === modelValue ? 0 : -1"
      :disabled="tab.disabled"
      @click="select(tab)"
    >
      {{ tab.label }}
      <span v-if="tab.count !== undefined && tab.count !== null" class="tab-count">{{ tab.count }}</span>
      <i v-if="tab.dot" class="tab-dot" aria-hidden="true" />
    </button>
  </div>
</template>

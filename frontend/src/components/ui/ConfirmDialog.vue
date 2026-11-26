<script setup>
import { nextTick, ref, useId, watch } from 'vue';

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  confirmText: { type: String, default: '确认' },
  danger: { type: Boolean, default: false },
  requireText: { type: String, default: '' },
  busy: { type: Boolean, default: false },
});

const emit = defineEmits(['update:open', 'confirm']);

const titleId = useId();
const panel = ref(null);
const inputEl = ref(null);
const typed = ref('');
const mismatch = ref(false);
let previousFocus = null;

watch(
  () => props.open,
  async (open) => {
    if (open) {
      previousFocus = document.activeElement;
      typed.value = '';
      mismatch.value = false;
      await nextTick();
      (inputEl.value || panel.value)?.focus();
      document.addEventListener('keydown', onKeydown, true);
    } else {
      document.removeEventListener('keydown', onKeydown, true);
      if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
      previousFocus = null;
    }
  }
);

function close() {
  emit('update:open', false);
}

function focusable() {
  if (!panel.value) return [];
  return Array.from(
    panel.value.querySelectorAll(
      'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'
    )
  ).filter((el) => el.offsetParent !== null);
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopPropagation();
    close();
    return;
  }
  if (event.key !== 'Tab') return;
  const list = focusable();
  if (!list.length) return;
  const first = list[0];
  const last = list[list.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function confirm() {
  if (props.requireText && typed.value.trim() !== props.requireText) {
    mismatch.value = true;
    inputEl.value?.focus();
    return;
  }
  emit('confirm');
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-mask" @click.self="close">
      <div
        ref="panel"
        class="modal modal--confirm"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
      >
        <div class="modal-head">
          <h3 :id="titleId">{{ title }}</h3>
        </div>
        <div class="modal-body">
          <slot />
          <label v-if="requireText" class="field">
            输入 <strong class="mono">{{ requireText }}</strong> 以确认
            <input
              ref="inputEl"
              v-model="typed"
              class="mono"
              :placeholder="requireText"
              autocomplete="off"
              @keyup.enter="confirm"
            />
          </label>
          <p v-if="mismatch" class="error-text">内容不一致，请重新输入</p>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="close">取消</button>
          <button :class="danger ? 'danger' : 'primary'" :disabled="busy" @click="confirm">
            {{ busy ? '处理中…' : confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

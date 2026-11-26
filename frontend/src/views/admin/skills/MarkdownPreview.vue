<script setup>
import { computed } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

const props = defineProps({
  source: { type: String, default: '' },
});

marked.setOptions({ gfm: true, breaks: false });

let hookReady = false;
function ensureHook() {
  if (hookReady) return;
  hookReady = true;
  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (node.tagName === 'A') {
      node.setAttribute('target', '_blank');
      node.setAttribute('rel', 'noopener noreferrer');
    }
  });
}

const html = computed(() => {
  ensureHook();
  const raw = marked.parse(String(props.source || ''), { async: false });
  return DOMPurify.sanitize(raw, {
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'img', 'style', 'link', 'form', 'input'],
    FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick'],
  });
});
</script>

<template>
  <!-- 内容经 DOMPurify 清洗且禁掉 img/script/iframe；样式限定在本组件内。 -->
  <div class="md-preview" v-html="html"></div>
</template>

<style scoped>
.md-preview {
  padding: 16px 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text);
  word-break: break-word;
}
.md-preview :deep(h1),
.md-preview :deep(h2),
.md-preview :deep(h3),
.md-preview :deep(h4) {
  margin: 18px 0 8px;
  line-height: 1.35;
}
.md-preview :deep(h1) {
  font-size: 19px;
}
.md-preview :deep(h2) {
  font-size: 16px;
}
.md-preview :deep(h3) {
  font-size: 14px;
}
.md-preview :deep(p) {
  margin: 8px 0;
}
.md-preview :deep(ul),
.md-preview :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}
.md-preview :deep(li) {
  margin: 3px 0;
}
.md-preview :deep(a) {
  color: var(--primary);
  text-decoration: none;
}
.md-preview :deep(a:hover) {
  text-decoration: underline;
}
.md-preview :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--surface-3);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}
.md-preview :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  overflow: auto;
}
.md-preview :deep(pre code) {
  padding: 0;
  background: transparent;
  font-size: 12px;
}
.md-preview :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 12.5px;
}
.md-preview :deep(th),
.md-preview :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--border);
  text-align: left;
}
.md-preview :deep(th) {
  background: var(--surface-2);
  font-weight: 600;
}
.md-preview :deep(blockquote) {
  margin: 10px 0;
  padding: 6px 12px;
  border-left: 3px solid var(--border-strong);
  color: var(--muted);
}
.md-preview :deep(hr) {
  margin: 16px 0;
  border: 0;
  border-top: 1px solid var(--border);
}
</style>

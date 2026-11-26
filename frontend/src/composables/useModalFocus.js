import { nextTick, onBeforeUnmount, watch } from 'vue';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

function visibleFocusables(panel) {
  return Array.from(panel.querySelectorAll(FOCUSABLE_SELECTOR)).filter((element) => {
    if (element.getClientRects().length === 0) return false;
    return getComputedStyle(element).visibility !== 'hidden';
  });
}

export function useModalFocus(panelRef, options = {}) {
  const { active = true, onEscape, enabled, initialFocus } = options;
  let previousFocus = null;
  let engaged = false;

  const isActive = () => (typeof active === 'function' ? Boolean(active()) : Boolean(active));
  const isEnabled = () => (typeof enabled === 'function' ? Boolean(enabled()) : enabled !== false);

  function resolveInitial(panel) {
    if (typeof initialFocus === 'function') return initialFocus(panel);
    if (typeof initialFocus === 'string') return panel.querySelector(initialFocus);
    return visibleFocusables(panel)[0] || null;
  }

  function onKeydown(event) {
    if (!isEnabled()) return;
    const panel = panelRef.value;
    if (!panel) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onEscape?.();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = visibleFocusables(panel);
    if (!items.length) {
      event.preventDefault();
      panel.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const current = document.activeElement;
    const inside = panel.contains(current);
    if (event.shiftKey) {
      if (!inside || current === first) {
        event.preventDefault();
        last.focus();
      }
      return;
    }
    if (!inside || current === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function engage() {
    if (engaged) return;
    engaged = true;
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.addEventListener('keydown', onKeydown, true);
    nextTick(() => {
      if (!engaged) return;
      const panel = panelRef.value;
      if (!panel) return;
      (resolveInitial(panel) || panel).focus();
    });
  }

  function disengage() {
    if (!engaged) return;
    engaged = false;
    document.removeEventListener('keydown', onKeydown, true);
    if (previousFocus && document.contains(previousFocus) && typeof previousFocus.focus === 'function') {
      previousFocus.focus();
    }
    previousFocus = null;
  }

  watch(isActive, (value) => (value ? engage() : disengage()), { immediate: true });
  onBeforeUnmount(disengage);

  return {
    focusPanel: () => panelRef.value?.focus?.(),
    release: disengage,
  };
}

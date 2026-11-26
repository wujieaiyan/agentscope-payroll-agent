<script setup>
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  icon: {
    type: String,
    default:
      'M7 3.5h7.2L19 8.3V20a.5.5 0 0 1-.5.5h-11A.5.5 0 0 1 7 20V3.5zM14 3.5V8.5H19M10 12.5h5M10 16h5',
  },
});
</script>

<template>
  <div class="empty-state">
    <span class="empty-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24">
        <path :d="icon" />
      </svg>
    </span>
    <h4 v-if="title">{{ title }}</h4>
    <p v-if="description || $slots.description">
      <slot name="description">{{ description }}</slot>
    </p>
    <div v-if="$slots.actions" class="empty-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import PanelCard from '../../../../components/ui/PanelCard.vue';
import EmptyState from '../../../../components/ui/EmptyState.vue';
import AgentAvatars from '../AgentAvatars.vue';

const props = defineProps({
  bindings: { type: Array, default: () => [] },
});

const list = computed(() => props.bindings || []);
</script>

<template>
  <PanelCard title="绑定">
    <template #hint>
      <span class="hint">{{ list.length }} 个智能体</span>
    </template>
    <template #actions>
      <router-link class="link-btn" to="/platform/agents">去智能体页</router-link>
    </template>

    <p class="bindings-tip">删除技能前需先解绑；绑定关系在智能体侧维护。</p>

    <ul v-if="list.length" class="binding-list">
      <li v-for="agent in list" :key="agent.agentId" class="binding-row">
        <AgentAvatars :agents="[agent]" :max="1" />
        <div class="binding-main">
          <div class="binding-name">{{ agent.name || agent.agentId }}</div>
          <div class="binding-desc">{{ agent.description || '未填写说明' }}</div>
        </div>
        <span class="binding-id mono">{{ agent.agentId }}</span>
      </li>
    </ul>
    <EmptyState
      v-else
      title="还没有智能体引用"
      description="在智能体页把该技能加入某个智能体的技能列表后，这里会出现绑定记录。"
    >
      <template #actions>
        <router-link class="link-btn" to="/platform/agents">去智能体页</router-link>
      </template>
    </EmptyState>
  </PanelCard>
</template>

<style scoped>
.bindings-tip {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--muted);
}
.binding-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
.binding-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.binding-row:last-child {
  border-bottom: 0;
}
.binding-main {
  flex: 1;
  min-width: 0;
}
.binding-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.binding-desc {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.binding-id {
  flex: none;
  font-size: 11px;
  color: var(--faint);
}
.link-btn {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--primary);
  font-size: 12px;
  text-decoration: none;
  cursor: pointer;
}
.link-btn:hover {
  text-decoration: underline;
}
</style>

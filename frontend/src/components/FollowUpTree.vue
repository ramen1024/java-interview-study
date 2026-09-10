<script setup lang="ts">
import MarkdownView from '@/components/MarkdownView.vue'
import type { FollowUpVO } from '@/types'

/**
 * 追问链渲染。展开状态由父组件持有，这样父组件可以一键全部展开/收起，
 * 而不需要往递归结构里塞控制信号。
 */
defineProps<{
  nodes: FollowUpVO[]
  expandedKeys: Set<string>
}>()

const emit = defineEmits<{
  toggle: [qKey: string]
}>()
</script>

<template>
  <div class="followup">
    <div v-for="node in nodes" :key="node.qKey" class="followup__node">
      <button class="followup__header" type="button" @click="emit('toggle', node.qKey)">
        <span class="followup__key">{{ node.qKey }}</span>
        <span class="followup__question">{{ node.question }}</span>
        <el-icon class="followup__arrow" :class="{ 'followup__arrow--open': expandedKeys.has(node.qKey) }">
          <ArrowRight />
        </el-icon>
      </button>

      <div v-show="expandedKeys.has(node.qKey)" class="followup__answer">
        <MarkdownView :content="node.answerMd" />

        <!-- 递归渲染下一层追问，没有子节点时自然什么都不渲染 -->
        <FollowUpTree
          v-if="node.children.length > 0"
          :nodes="node.children"
          :expanded-keys="expandedKeys"
          @toggle="emit('toggle', $event)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.followup__node + .followup__node {
  margin-top: 8px;
}

.followup__header {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--jis-followup-border);
  border-radius: var(--jis-radius-sm);
  background: var(--jis-followup);
  color: var(--jis-text);
  font-size: 14px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition: filter 0.15s ease;
}

.followup__header:hover {
  filter: brightness(0.98);
}

.followup__key {
  flex-shrink: 0;
  padding: 0 7px;
  border-radius: 4px;
  background: var(--jis-accent);
  color: #fff;
  font-size: 11.5px;
  font-weight: 700;
  line-height: 19px;
}

.followup__question {
  flex: 1;
  line-height: 1.6;
}

.followup__arrow {
  flex-shrink: 0;
  margin-top: 3px;
  color: var(--jis-text-muted);
  transition: transform 0.18s ease;
}

.followup__arrow--open {
  transform: rotate(90deg);
}

.followup__answer {
  margin: 6px 0 0 14px;
  padding: 12px 14px;
  border-left: 2px solid var(--jis-followup-border);
  color: var(--jis-text-secondary);
  font-size: 14px;
}

/* 子树再缩进一层，视觉上形成追问的层级感 */
.followup__answer :deep(.followup) {
  margin-top: 12px;
}
</style>

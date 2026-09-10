<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import type { KnowledgePointListItemVO } from '@/types'

const props = defineProps<{
  item: KnowledgePointListItemVO
  /** 是否显示「下次复习」时间，复习队列与知识体系用得到 */
  showDue?: boolean
}>()

const router = useRouter()

const mastery = computed(() => {
  switch (props.item.masteryState) {
    case 1:
      return { label: '学习中', type: 'warning' as const }
    case 2:
      return { label: '复习中', type: 'primary' as const }
    case 3:
      return { label: '重新学习', type: 'danger' as const }
    default:
      return { label: '未复习', type: 'info' as const }
  }
})

const difficultyStars = computed(() => '★'.repeat(props.item.difficulty))

const frequencyLabel = computed(() =>
  props.item.frequency >= 3 ? '高频' : props.item.frequency === 2 ? '常见' : '低频',
)

const dueText = computed(() => {
  if (!props.item.dueAt) {
    return ''
  }

  const due = new Date(props.item.dueAt)
  const now = new Date()
  const days = Math.floor((due.getTime() - now.getTime()) / 86400000)

  if (days < 0) {
    return `已逾期 ${Math.abs(days)} 天`
  }
  if (days === 0) {
    return '今天到期'
  }

  return `${days} 天后复习`
})

function open(): void {
  void router.push({ name: 'card-detail', params: { slug: props.item.slug } })
}
</script>

<template>
  <button class="card-item" type="button" @click="open">
    <div class="card-item__main">
      <div class="card-item__title">{{ item.title }}</div>
      <div class="card-item__meta">
        <span v-if="item.moduleName" class="card-item__module">{{ item.moduleName }}</span>
        <span v-for="tag in item.tags.slice(0, 4)" :key="tag" class="card-item__tag">{{ tag }}</span>
      </div>
    </div>

    <div class="card-item__side">
      <el-tag :type="mastery.type" size="small" effect="light">{{ mastery.label }}</el-tag>
      <span class="card-item__stars" :title="`难度 ${item.difficulty}/3`">{{ difficultyStars }}</span>
      <span class="card-item__freq">{{ frequencyLabel }}</span>
      <span v-if="item.questionCount > 0" class="card-item__quiz">{{ item.questionCount }} 题</span>
      <span v-if="showDue && dueText" class="card-item__due">{{ dueText }}</span>
    </div>
  </button>
</template>

<style scoped>
.card-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius);
  background: var(--jis-surface);
  text-align: left;
  cursor: pointer;
  /* 不做 hover 位移：卡片很大，指针停在边缘时位移会让元素
     在指针下反复进出悬停状态，产生抖动并影响点击命中 */
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.card-item:hover {
  border-color: var(--jis-accent);
  box-shadow: var(--jis-shadow-hover);
}

.card-item__main {
  min-width: 0;
  flex: 1;
}

.card-item__title {
  font-size: 14.5px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--jis-text);
}

.card-item__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  font-size: 12px;
  color: var(--jis-text-muted);
}

.card-item__module {
  color: var(--jis-accent-strong);
}

.card-item__tag {
  padding: 0 6px;
  border: 1px solid var(--jis-border);
  border-radius: 4px;
}

.card-item__side {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--jis-text-muted);
}

.card-item__stars {
  color: #f59e0b;
  letter-spacing: -1px;
}

.card-item__due {
  color: var(--jis-accent-strong);
}

@media (max-width: 720px) {
  .card-item {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .card-item__side {
    flex-wrap: wrap;
  }
}
</style>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchFavorites } from '@/api/content'
import CardListItem from '@/components/CardListItem.vue'
import type { KnowledgePointListItemVO } from '@/types'

const loading = ref(true)
const cards = ref<KnowledgePointListItemVO[]>([])

onMounted(async () => {
  try {
    cards.value = await fetchFavorites()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="jis-page">
    <div class="jis-page-header">
      <h2 class="jis-page-title">我的收藏</h2>
      <p class="jis-page-subtitle">在卡片详情页点「收藏」可以把知识点收进来，方便集中复习。</p>
    </div>

    <div v-loading="loading" class="list">
      <div v-if="!loading && cards.length === 0" class="jis-card jis-empty">
        还没有收藏任何知识点
      </div>

      <CardListItem v-for="card in cards" :key="card.slug" :item="card" show-due />
    </div>
  </div>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}
</style>

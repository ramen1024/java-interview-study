<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { searchCards } from '@/api/content'
import CardListItem from '@/components/CardListItem.vue'
import type { KnowledgePointListItemVO } from '@/types'

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const loading = ref(false)
const results = ref<KnowledgePointListItemVO[]>([])

/**
 * 搜索接口返回的是 SearchHitVO，字段与列表项不完全一致。
 * 这里只取列表渲染需要的部分，映射成统一类型复用 CardListItem。
 */
async function run(value: string): Promise<void> {
  const trimmed = value.trim()
  keyword.value = trimmed

  if (!trimmed) {
    results.value = []
    return
  }

  loading.value = true
  try {
    const hits = await searchCards(trimmed)
    results.value = hits.map((hit) => ({
      slug: hit.slug,
      title: hit.title,
      moduleSlug: null,
      moduleName: hit.moduleName,
      tags: hit.tags,
      difficulty: hit.difficulty,
      frequency: hit.frequency,
      questionCount: 0,
      masteryState: null,
      dueAt: null,
    }))
  } finally {
    loading.value = false
  }
}

function search(): void {
  if (keyword.value.trim()) {
    void router.replace({ query: { keyword: keyword.value.trim() } })
  }
}

watch(
  () => route.query.keyword,
  (value) => void run(typeof value === 'string' ? value : ''),
  { immediate: true },
)

onMounted(() => {
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
})
</script>

<template>
  <div class="jis-page">
    <div class="jis-page-header">
      <h2 class="jis-page-title">搜索</h2>
      <p class="jis-page-subtitle">
        基于 MySQL 全文索引（ngram 分词），先做必需词精确匹配，命中不足时放宽为相关度召回。
      </p>
    </div>

    <div class="searchbox">
      <el-input
        v-model="keyword"
        size="large"
        placeholder="例如：缓存穿透、AQS、MVCC"
        clearable
        @keyup.enter="search"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" size="large" @click="search">搜索</el-button>
    </div>

    <div v-loading="loading" class="results">
      <p v-if="keyword && !loading" class="jis-muted results__count">
        「{{ keyword }}」共 {{ results.length }} 条结果
      </p>

      <div v-if="!loading && keyword && results.length === 0" class="jis-card jis-empty">
        没有匹配的知识点，换个说法试试
      </div>

      <CardListItem v-for="item in results" :key="item.slug" :item="item" />
    </div>
  </div>
</template>

<style scoped>
.searchbox {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.results {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}

.results__count {
  margin: 0 0 2px;
  font-size: 13px;
}
</style>

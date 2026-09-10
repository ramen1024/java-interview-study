<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { fetchHotKeywords, searchCards } from '@/api/content'
import { useUiStore } from '@/stores/ui'
import type { SearchHitVO } from '@/types'

const router = useRouter()
const ui = useUiStore()

const inputRef = ref<{ focus: () => void } | null>(null)
const keyword = ref('')
const results = ref<SearchHitVO[]>([])
const hotKeywords = ref<string[]>([])
const searching = ref(false)

let debounceTimer: ReturnType<typeof setTimeout> | undefined

async function runSearch(value: string): Promise<void> {
  const trimmed = value.trim()
  if (!trimmed) {
    results.value = []
    return
  }

  searching.value = true
  try {
    results.value = await searchCards(trimmed)
  } catch {
    results.value = []
  } finally {
    searching.value = false
  }
}

// 输入防抖：每敲一个字就查一次全文索引，既浪费也容易让结果闪烁
watch(keyword, (value) => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => void runSearch(value), 220)
})

watch(
  () => ui.searchVisible,
  async (visible) => {
    if (!visible) {
      return
    }

    await nextTick()
    inputRef.value?.focus()

    if (hotKeywords.value.length === 0) {
      try {
        hotKeywords.value = await fetchHotKeywords()
      } catch {
        hotKeywords.value = []
      }
    }
  },
)

function openCard(slug: string): void {
  ui.closeSearch()
  keyword.value = ''
  results.value = []
  void router.push({ name: 'card-detail', params: { slug } })
}

function useKeyword(value: string): void {
  keyword.value = value
}

function openSearchPage(): void {
  const trimmed = keyword.value.trim()
  if (!trimmed) {
    ElMessage.info('先输入要搜索的内容')
    return
  }

  ui.closeSearch()
  void router.push({ name: 'search', query: { keyword: trimmed } })
  keyword.value = ''
  results.value = []
}

function frequencyLabel(frequency: number): string {
  return frequency >= 3 ? '高频' : frequency === 2 ? '常见' : '低频'
}
</script>

<template>
  <el-dialog
    v-model="ui.searchVisible"
    width="600px"
    top="10vh"
    :show-close="false"
    class="search-dialog"
    append-to-body
    @closed="keyword = ''"
  >
    <template #header>
      <el-input
        ref="inputRef"
        v-model="keyword"
        placeholder="搜索知识点、标签，或按 Ctrl+K 呼出"
        size="large"
        clearable
        @keyup.enter="openSearchPage"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
    </template>

    <div v-loading="searching" class="search-body">
      <template v-if="keyword.trim()">
        <button
          v-for="hit in results"
          :key="hit.slug"
          class="hit"
          type="button"
          @click="openCard(hit.slug)"
        >
          <div class="hit__head">
            <span class="hit__title">{{ hit.title }}</span>
            <span class="hit__freq">{{ frequencyLabel(hit.frequency) }}</span>
          </div>
          <div class="hit__meta">
            {{ hit.moduleName }}
            <span v-for="tag in hit.tags.slice(0, 3)" :key="tag" class="hit__tag">{{ tag }}</span>
          </div>
          <p class="hit__snippet">{{ hit.snippet }}</p>
        </button>

        <div v-if="!searching && results.length === 0" class="jis-empty">
          没有匹配的知识点，换个词试试
        </div>

        <div v-if="results.length > 0" class="search-more">
          <el-button text type="primary" @click="openSearchPage">查看完整结果页</el-button>
        </div>
      </template>

      <template v-else>
        <div v-if="hotKeywords.length > 0" class="hot">
          <div class="hot__title">最近大家在搜</div>
          <div class="hot__list">
            <button
              v-for="word in hotKeywords"
              :key="word"
              class="hot__item"
              type="button"
              @click="useKeyword(word)"
            >
              {{ word }}
            </button>
          </div>
        </div>
        <div v-else class="jis-empty">输入关键词开始搜索</div>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.search-body {
  max-height: 56vh;
  overflow-y: auto;
}

.hit {
  display: block;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--jis-radius-sm);
  background: none;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.hit:hover {
  background: var(--jis-surface-muted);
  border-color: var(--jis-border);
}

.hit__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.hit__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--jis-text);
}

.hit__freq {
  flex-shrink: 0;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-size: 11px;
}

.hit__meta {
  margin-top: 3px;
  color: var(--jis-text-muted);
  font-size: 12px;
}

.hit__tag {
  margin-left: 8px;
  padding: 0 6px;
  border: 1px solid var(--jis-border);
  border-radius: 4px;
}

.hit__snippet {
  margin: 5px 0 0;
  color: var(--jis-text-secondary);
  font-size: 12.5px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.search-more {
  padding-top: 6px;
  text-align: center;
  border-top: 1px solid var(--jis-border);
  margin-top: 6px;
}

.hot__title {
  margin-bottom: 10px;
  color: var(--jis-text-muted);
  font-size: 12px;
}

.hot__list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hot__item {
  padding: 5px 12px;
  border: 1px solid var(--jis-border);
  border-radius: 999px;
  background: var(--jis-surface);
  color: var(--jis-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.hot__item:hover {
  border-color: var(--jis-accent);
  color: var(--jis-accent);
}
</style>

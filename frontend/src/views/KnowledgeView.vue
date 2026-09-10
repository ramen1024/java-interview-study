<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchCards, fetchModuleTree } from '@/api/content'
import CardListItem from '@/components/CardListItem.vue'
import type { KnowledgePointListItemVO, ModuleTreeVO } from '@/types'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const modules = ref<ModuleTreeVO[]>([])
const cards = ref<KnowledgePointListItemVO[]>([])
const total = ref(0)

const selectedModule = ref<string | undefined>(undefined)
const sortBy = ref<string>('default')
const difficulty = ref<number | undefined>(undefined)
const page = ref(1)
const size = ref(20)

const flattenedModules = computed(() => {
  const result: { slug: string; label: string; count: number; level: number }[] = []

  for (const module of modules.value) {
    result.push({ slug: module.slug, label: module.name, count: module.cardCount, level: 1 })
    for (const child of module.children) {
      result.push({ slug: child.slug, label: child.name, count: child.cardCount, level: 2 })
    }
  }

  return result
})

const currentModuleName = computed(() => {
  if (!selectedModule.value) {
    return '全部知识点'
  }

  return flattenedModules.value.find((module) => module.slug === selectedModule.value)?.label ?? '知识点'
})

async function loadCards(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchCards({
      moduleSlug: selectedModule.value,
      sortBy: sortBy.value === 'default' ? undefined : sortBy.value,
      difficulty: difficulty.value,
      page: page.value,
      size: size.value,
    })

    cards.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function selectModule(slug: string | undefined): void {
  selectedModule.value = slug
  page.value = 1
  void router.replace({ query: slug ? { module: slug } : {} })
}

// 从仪表盘点模块名跳进来时带着 module 查询参数
watch(
  () => route.query.module,
  (value) => {
    const slug = typeof value === 'string' ? value : undefined
    if (slug !== selectedModule.value) {
      selectedModule.value = slug
      page.value = 1
      void loadCards()
    }
  },
)

watch([sortBy, difficulty], () => {
  page.value = 1
  void loadCards()
})

watch(page, () => void loadCards())

onMounted(async () => {
  modules.value = await fetchModuleTree()
  selectedModule.value = typeof route.query.module === 'string' ? route.query.module : undefined
  await loadCards()
})
</script>

<template>
  <div class="jis-page jis-page--wide">
    <div class="knowledge">
      <aside class="tree jis-card">
        <h3 class="jis-section-title">
          <el-icon><Collection /></el-icon>
          知识体系
        </h3>

        <button
          class="tree__item"
          :class="{ 'tree__item--active': !selectedModule }"
          type="button"
          @click="selectModule(undefined)"
        >
          <span class="tree__label">全部知识点</span>
        </button>

        <div v-for="module in modules" :key="module.slug" class="tree__group">
          <button
            class="tree__item"
            :class="{ 'tree__item--active': selectedModule === module.slug }"
            type="button"
            @click="selectModule(module.slug)"
          >
            <span class="tree__label">{{ module.name }}</span>
            <span class="tree__count">{{ module.cardCount }}</span>
          </button>

          <button
            v-for="child in module.children"
            :key="child.slug"
            class="tree__item tree__item--child"
            :class="{ 'tree__item--active': selectedModule === child.slug }"
            type="button"
            @click="selectModule(child.slug)"
          >
            <span class="tree__label">{{ child.name }}</span>
            <span class="tree__count">{{ child.cardCount }}</span>
          </button>
        </div>
      </aside>

      <section class="list">
        <div class="list__header jis-card">
          <div>
            <h2 class="jis-page-title">{{ currentModuleName }}</h2>
            <p class="jis-page-subtitle">共 {{ total }} 个知识点</p>
          </div>

          <div class="list__filters">
            <el-select v-model="sortBy" size="small" style="width: 128px">
              <el-option label="默认顺序" value="default" />
              <el-option label="面试热度" value="frequency" />
              <el-option label="由易到难" value="difficulty" />
              <el-option label="按标题" value="title" />
            </el-select>

            <el-select v-model="difficulty" size="small" clearable placeholder="难度" style="width: 104px">
              <el-option label="简单" :value="1" />
              <el-option label="中等" :value="2" />
              <el-option label="困难" :value="3" />
            </el-select>
          </div>
        </div>

        <div v-loading="loading" class="list__body">
          <div v-if="!loading && cards.length === 0" class="jis-empty">
            这个模块下还没有知识点，去 content/ 里添加 Markdown 卡片后重新导入
          </div>

          <CardListItem v-for="card in cards" :key="card.slug" :item="card" show-due />
        </div>

        <el-pagination
          v-if="total > size"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          class="list__pager"
          background
        />
      </section>
    </div>
  </div>
</template>

<style scoped>
.knowledge {
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.tree {
  position: sticky;
  top: 76px;
  padding: 16px 12px;
}

.tree__group {
  margin-top: 4px;
}

.tree__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: var(--jis-radius-sm);
  background: none;
  color: var(--jis-text-secondary);
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
}

.tree__item:hover {
  background: var(--jis-surface-muted);
  color: var(--jis-text);
}

.tree__item--active {
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-weight: 620;
}

.tree__item--child {
  padding-left: 22px;
  font-size: 13px;
}

.tree__label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree__count {
  flex-shrink: 0;
  color: var(--jis-text-muted);
  font-size: 11.5px;
}

.list__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
  padding: 18px 20px;
}

.list__filters {
  display: flex;
  gap: 8px;
}

.list__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 120px;
}

.list__pager {
  margin-top: 18px;
  justify-content: center;
}

@media (max-width: 900px) {
  .knowledge {
    grid-template-columns: minmax(0, 1fr);
  }

  .tree {
    position: static;
  }
}
</style>

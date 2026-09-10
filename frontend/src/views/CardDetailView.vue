<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchCard, saveNote, setFavorite } from '@/api/content'
import { rateCard, resetProgress } from '@/api/review'
import FollowUpTree from '@/components/FollowUpTree.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import type { FollowUpVO, KnowledgePointView } from '@/types'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const view = ref<KnowledgePointView | null>(null)
const expandedKeys = ref(new Set<string>())
const noteDraft = ref('')
const noteSaving = ref(false)
const ratingSlug = ref<string | null>(null)
/** 记录开始看这张卡的时间，评分时作为耗时上报 */
const openedAt = ref(Date.now())

const card = computed(() => view.value?.card ?? null)
const userState = computed(() => view.value?.userState ?? null)

const allFollowUpKeys = computed(() => {
  const keys: string[] = []
  const walk = (nodes: FollowUpVO[]): void => {
    for (const node of nodes) {
      keys.push(node.qKey)
      walk(node.children)
    }
  }
  walk(card.value?.followUps ?? [])

  return keys
})

const allExpanded = computed(
  () => allFollowUpKeys.value.length > 0 && expandedKeys.value.size === allFollowUpKeys.value.length,
)

const masteryLabel = computed(() => {
  switch (userState.value?.masteryState) {
    case 1:
      return '学习中'
    case 2:
      return '复习中'
    case 3:
      return '重新学习'
    default:
      return '未复习'
  }
})

const masteryType = computed(() => {
  switch (userState.value?.masteryState) {
    case 1:
      return 'warning'
    case 2:
      return 'primary'
    case 3:
      return 'danger'
    default:
      return 'info'
  }
})

const RATINGS = [
  { value: 1, label: '不会', hint: '完全没想起来', type: 'danger' as const },
  { value: 2, label: '模糊', hint: '想起来了但很吃力', type: 'warning' as const },
  { value: 3, label: '会讲', hint: '能正常讲清楚', type: 'primary' as const },
  { value: 4, label: '轻松', hint: '脱口而出', type: 'success' as const },
]

async function load(slug: string): Promise<void> {
  loading.value = true
  expandedKeys.value = new Set()
  try {
    const result = await fetchCard(slug)
    view.value = result
    noteDraft.value = result.userState.noteMd
    openedAt.value = Date.now()
  } catch {
    view.value = null
  } finally {
    loading.value = false
  }
}

function toggleFollowUp(qKey: string): void {
  const next = new Set(expandedKeys.value)
  if (next.has(qKey)) {
    next.delete(qKey)
  } else {
    next.add(qKey)
  }
  expandedKeys.value = next
}

function toggleAll(): void {
  expandedKeys.value = allExpanded.value
    ? new Set()
    : new Set(allFollowUpKeys.value)
}

async function toggleFavorite(): Promise<void> {
  if (!card.value || !userState.value) {
    return
  }

  const next = !userState.value.favorite
  const result = await setFavorite(card.value.slug, next)
  userState.value.favorite = result
  ElMessage.success(result ? '已加入收藏' : '已取消收藏')
}

async function persistNote(): Promise<void> {
  if (!card.value) {
    return
  }

  noteSaving.value = true
  try {
    await saveNote(card.value.slug, noteDraft.value)
    if (userState.value) {
      userState.value.noteMd = noteDraft.value
    }
    ElMessage.success('笔记已保存')
  } finally {
    noteSaving.value = false
  }
}

async function rate(rating: number): Promise<void> {
  if (!card.value) {
    return
  }

  ratingSlug.value = card.value.slug
  try {
    const result = await rateCard(card.value.slug, rating, Date.now() - openedAt.value)

    if (userState.value) {
      userState.value.masteryState = result.state
      userState.value.dueAt = result.dueAt
      userState.value.stability = result.stabilityAfter
      userState.value.reps = (userState.value.reps ?? 0) + 1
    }

    const detail = result.reintroduceSoon
      ? '这张卡会在本轮稍后再次出现'
      : `下次复习：${result.intervalDays} 天后（${result.dueAt.slice(0, 10)}）`

    ElMessage.success(`${result.ratingLabel} · 稳定性 ${result.stabilityAfter.toFixed(2)} 天 · ${detail}`)
  } finally {
    ratingSlug.value = null
  }
}

async function handleReset(): Promise<void> {
  if (!card.value) {
    return
  }

  try {
    await ElMessageBox.confirm(
      '重置后这张卡会回到「未复习」状态。复习日志会保留，用于统计。',
      '重置复习进度',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  await resetProgress(card.value.slug)
  if (userState.value) {
    userState.value.masteryState = null
    userState.value.dueAt = null
    userState.value.stability = null
    userState.value.reps = 0
    userState.value.lapses = 0
  }
  ElMessage.success('已重置')
}

watch(
  () => route.params.slug,
  (slug) => {
    if (typeof slug === 'string') {
      void load(slug)
    }
  },
  { immediate: true },
)

onMounted(() => {
  window.scrollTo({ top: 0 })
})
</script>

<template>
  <div class="jis-page">
    <div v-loading="loading" class="detail">
      <template v-if="card">
        <header class="header jis-card">
          <div class="header__top">
            <el-button text size="small" @click="router.back()">
              <el-icon><ArrowLeft /></el-icon>
              返回
            </el-button>

            <div class="header__actions">
              <el-tag :type="masteryType" effect="light" size="small">{{ masteryLabel }}</el-tag>
              <el-tag size="small" effect="plain">{{ card.moduleName }}</el-tag>

              <el-button
                size="small"
                :type="userState?.favorite ? 'warning' : 'default'"
                @click="toggleFavorite"
              >
                <el-icon>
                  <component :is="userState?.favorite ? 'StarFilled' : 'Star'" />
                </el-icon>
                {{ userState?.favorite ? '已收藏' : '收藏' }}
              </el-button>

              <el-button v-if="userState?.reps" size="small" @click="handleReset">
                <el-icon><RefreshLeft /></el-icon>
                重置进度
              </el-button>
            </div>
          </div>

          <h1 class="header__title">{{ card.title }}</h1>

          <div class="header__meta">
            <span v-for="tag in card.tags" :key="tag" class="header__tag">{{ tag }}</span>
            <span class="header__sep">·</span>
            <span>难度 {{ '★'.repeat(card.difficulty) }}</span>
            <span class="header__sep">·</span>
            <span>{{ card.frequency >= 3 ? '面试高频' : card.frequency === 2 ? '面试常见' : '低频' }}</span>
            <template v-if="userState?.stability">
              <span class="header__sep">·</span>
              <span>记忆稳定性 {{ userState.stability.toFixed(1) }} 天</span>
            </template>
            <template v-if="userState?.lapses">
              <span class="header__sep">·</span>
              <span>遗忘过 {{ userState.lapses }} 次</span>
            </template>
          </div>

          <div class="rate">
            <span class="rate__label">速评掌握度：</span>
            <el-button
              v-for="option in RATINGS"
              :key="option.value"
              :type="option.type"
              plain
              size="small"
              :loading="ratingSlug === card.slug"
              @click="rate(option.value)"
            >
              {{ option.label }}
            </el-button>
            <span class="rate__hint">
              评分会影响这张卡下次出现的间隔
            </span>
          </div>
        </header>

        <section class="block block--elevator">
          <div class="block__label">
            <el-icon><Microphone /></el-icon>
            电梯版回答
            <span class="block__label-hint">30 秒说清结论，面试就这样开场</span>
          </div>
          <MarkdownView :content="card.elevatorAnswer" />
        </section>

        <section v-if="card.detailMd" class="block">
          <div class="block__label">
            <el-icon><Reading /></el-icon>
            展开讲解
          </div>
          <MarkdownView :content="card.detailMd" />
        </section>

        <section v-if="card.followUps.length > 0" class="block block--followup">
          <div class="block__label">
            <el-icon><ChatLineSquare /></el-icon>
            面试官追问链
            <span class="block__label-hint">
              先自己试着答，再展开对照参考答案——这正是普通八股题库没有的部分
            </span>
            <el-button text size="small" class="block__action" @click="toggleAll">
              {{ allExpanded ? '全部收起' : '全部展开' }}
            </el-button>
          </div>
          <FollowUpTree
            :nodes="card.followUps"
            :expanded-keys="expandedKeys"
            @toggle="toggleFollowUp"
          />
        </section>

        <div class="grid">
          <section v-if="card.pitfallsMd" class="block block--pitfall">
            <div class="block__label">
              <el-icon><WarningFilled /></el-icon>
              常见坑
            </div>
            <MarkdownView :content="card.pitfallsMd" />
          </section>

          <section v-if="card.bonusMd" class="block block--bonus">
            <div class="block__label">
              <el-icon><TrophyBase /></el-icon>
              加分点
            </div>
            <MarkdownView :content="card.bonusMd" />
          </section>
        </div>

        <section v-if="card.versionDiffMd" class="block block--version">
          <div class="block__label">
            <el-icon><Switch /></el-icon>
            版本差异
          </div>
          <MarkdownView :content="card.versionDiffMd" />
        </section>

        <section v-if="card.related.length > 0" class="block">
          <div class="block__label">
            <el-icon><Link /></el-icon>
            相关知识点
          </div>
          <div class="related">
            <button
              v-for="item in card.related"
              :key="item.slug"
              class="related__item"
              type="button"
              @click="router.push({ name: 'card-detail', params: { slug: item.slug } })"
            >
              <span class="related__type">{{ item.relationType === 'PREREQUISITE' ? '前置' : item.relationType === 'DEEPEN' ? '深入' : item.relationType === 'CONTRAST' ? '对比' : '相关' }}</span>
              <span class="related__title">{{ item.title }}</span>
            </button>
          </div>
        </section>

        <section class="block">
          <div class="block__label">
            <el-icon><EditPen /></el-icon>
            我的笔记
            <span class="block__label-hint">用人话记下你的记忆钩子，比背原文更有效</span>
          </div>
          <el-input
            v-model="noteDraft"
            type="textarea"
            :rows="5"
            placeholder="支持 Markdown，例如：&#10;- 扰动函数 = 高 16 位异或到低 16 位&#10;- 树化双条件：链表 ≥ 8 **且** 数组 ≥ 64"
          />
          <div class="note__footer">
            <span v-if="userState?.noteUpdatedAt" class="jis-muted">
              上次保存：{{ userState.noteUpdatedAt.replace('T', ' ').slice(0, 16) }}
            </span>
            <el-button type="primary" size="small" :loading="noteSaving" @click="persistNote">
              保存笔记
            </el-button>
          </div>
        </section>

        <div v-if="card.questionCount > 0" class="cta">
          <span>这张卡有 {{ card.questionCount }} 道配套自测题</span>
          <el-button
            type="primary"
            size="small"
            @click="router.push({ name: 'quiz', query: { moduleSlug: card.moduleSlug ?? undefined } })"
          >
            去刷题
          </el-button>
        </div>
      </template>

      <div v-else-if="!loading" class="jis-empty">
        知识点不存在，它可能已被从 content/ 中删除
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 60vh;
}

.header__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.header__title {
  margin: 0 0 10px;
  font-size: 22px;
  font-weight: 680;
  line-height: 1.45;
  letter-spacing: -0.01em;
}

.header__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  color: var(--jis-text-muted);
  font-size: 12.5px;
}

.header__tag {
  padding: 1px 8px;
  border: 1px solid var(--jis-border);
  border-radius: 4px;
}

.header__sep {
  color: var(--jis-border);
}

.rate {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--jis-border);
}

.rate__label {
  color: var(--jis-text-secondary);
  font-size: 13px;
}

.rate__hint {
  color: var(--jis-text-muted);
  font-size: 12px;
}

.block {
  padding: 18px 20px;
  border: 1px solid var(--jis-border);
  border-left: 3px solid var(--jis-border);
  border-radius: var(--jis-radius);
  background: var(--jis-surface);
  box-shadow: var(--jis-shadow);
}

.block__label {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--jis-border);
  font-size: 14px;
  font-weight: 650;
}

.block__label-hint {
  flex: 1;
  color: var(--jis-text-muted);
  font-size: 12px;
  font-weight: 400;
}

.block__action {
  flex-shrink: 0;
}

.block--elevator {
  border-left-color: var(--jis-elevator-border);
  background: linear-gradient(180deg, var(--jis-elevator), var(--jis-surface) 78%);
}

.block--followup {
  border-left-color: var(--jis-followup-border);
}

.block--pitfall {
  border-left-color: var(--jis-pitfall-border);
  background: linear-gradient(180deg, var(--jis-pitfall), var(--jis-surface) 72%);
}

.block--bonus {
  border-left-color: var(--jis-bonus-border);
  background: linear-gradient(180deg, var(--jis-bonus), var(--jis-surface) 72%);
}

.block--version {
  border-left-color: var(--jis-version-border);
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.related {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
}

.related__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface-muted);
  color: var(--jis-text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.related__item:hover {
  border-color: var(--jis-accent);
}

.related__type {
  padding: 0 6px;
  border-radius: 4px;
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-size: 11px;
}

.note__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  font-size: 12px;
}

.cta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border: 1px dashed var(--jis-border);
  border-radius: var(--jis-radius);
  color: var(--jis-text-secondary);
  font-size: 13.5px;
}

@media (max-width: 820px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

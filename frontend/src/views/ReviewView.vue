<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchCard } from '@/api/content'
import { fetchReviewQueue, rateCard } from '@/api/review'
import FollowUpTree from '@/components/FollowUpTree.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import type {
  FollowUpVO,
  KnowledgePointDetailVO,
  RateResultVO,
  ReviewQueueVO,
} from '@/types'

const router = useRouter()

const loading = ref(true)
const queue = ref<ReviewQueueVO | null>(null)
const index = ref(0)
const revealed = ref(false)
const detail = ref<KnowledgePointDetailVO | null>(null)
const detailLoading = ref(false)
const expandedKeys = ref(new Set<string>())
const rating = ref(false)
const sessionResults = ref<RateResultVO[]>([])
const elapsed = ref(Date.now())
const showDetail = ref(false)

const items = computed(() => queue.value?.items ?? [])
const current = computed(() => items.value[index.value] ?? null)
const finished = computed(() => queue.value !== null && index.value >= items.value.length)
const progress = computed(() =>
  items.value.length === 0 ? 0 : Math.round((index.value / items.value.length) * 100),
)

const RATINGS = [
  { value: 1, label: '不会', key: '1', hint: '完全没想起来', type: 'danger' as const },
  { value: 2, label: '模糊', key: '2', hint: '想起来了但很吃力', type: 'warning' as const },
  { value: 3, label: '会讲', key: '3', hint: '能正常讲清楚', type: 'primary' as const },
  { value: 4, label: '轻松', key: '4', hint: '脱口而出', type: 'success' as const },
]

const allFollowUpKeys = computed(() => {
  const keys: string[] = []
  const walk = (nodes: FollowUpVO[]): void => {
    for (const node of nodes) {
      keys.push(node.qKey)
      walk(node.children)
    }
  }
  walk(detail.value?.followUps ?? [])

  return keys
})

const allExpanded = computed(
  () => allFollowUpKeys.value.length > 0 && expandedKeys.value.size === allFollowUpKeys.value.length,
)

const averageInterval = computed(() => {
  const planned = sessionResults.value.filter((result) => !result.reintroduceSoon)
  if (planned.length === 0) {
    return 0
  }

  return Math.round(
    planned.reduce((sum, result) => sum + result.intervalDays, 0) / planned.length,
  )
})

async function loadQueue(): Promise<void> {
  loading.value = true
  try {
    queue.value = await fetchReviewQueue({ limit: 50 })
  } finally {
    loading.value = false
  }

  if (items.value.length > 0) {
    await loadCurrentCard()
  }
}

async function loadCurrentCard(): Promise<void> {
  const card = current.value
  if (!card) {
    detail.value = null
    return
  }

  detailLoading.value = true
  expandedKeys.value = new Set()
  showDetail.value = false
  elapsed.value = Date.now()

  try {
    const result = await fetchCard(card.slug)
    detail.value = result.card
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function reveal(): void {
  if (!revealed.value) {
    revealed.value = true
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

function toggleAllFollowUps(): void {
  expandedKeys.value = allExpanded.value ? new Set() : new Set(allFollowUpKeys.value)
}

async function submitRating(value: number): Promise<void> {
  const card = current.value
  if (!card || rating.value) {
    return
  }

  rating.value = true
  try {
    const result = await rateCard(card.slug, value, Date.now() - elapsed.value)
    sessionResults.value = [...sessionResults.value, result]

    index.value += 1
    revealed.value = false

    if (index.value < items.value.length) {
      await loadCurrentCard()
    } else {
      detail.value = null
    }
  } finally {
    rating.value = false
  }
}

function handleKeydown(event: KeyboardEvent): void {
  // 输入框里按空格是在打字，不能当成「显示答案」
  const target = event.target as HTMLElement | null
  const typing = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA'

  if (!revealed.value && !typing && (event.code === 'Space' || event.key === 'Enter')) {
    event.preventDefault()
    reveal()
    return
  }

  if (revealed.value && !typing && ['1', '2', '3', '4'].includes(event.key)) {
    event.preventDefault()
    void submitRating(Number(event.key))
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  void loadQueue()
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="jis-page">
    <div v-loading="loading" class="review">
      <!-- 加载完成但没有待复习内容 -->
      <div v-if="queue && items.length === 0" class="jis-card empty-state">
        <el-icon class="empty-state__icon"><CircleCheckFilled /></el-icon>
        <h2 class="empty-state__title">今日复习队列已清空</h2>
        <p class="empty-state__text">
          到期的卡片都复习完了。间隔重复的关键是「到点了再来」，
          现在可以去刷几道自测题，或者提前看看新知识点。
        </p>
        <div class="empty-state__actions">
          <el-button type="primary" @click="router.push({ name: 'quiz' })">去做自测</el-button>
          <el-button @click="router.push({ name: 'knowledge' })">浏览知识体系</el-button>
        </div>
      </div>

      <!-- 一轮结束 -->
      <div v-else-if="finished" class="jis-card empty-state">
        <el-icon class="empty-state__icon"><TrophyBase /></el-icon>
        <h2 class="empty-state__title">本轮完成 {{ sessionResults.length }} 张卡片</h2>
        <p class="empty-state__text">
          <template v-if="averageInterval > 0">
            这些卡片的平均下次间隔是 <strong>{{ averageInterval }}</strong> 天。
            间隔会随着每次成功回忆逐步拉长，这正是 FSRS 在做的事。
          </template>
          <template v-else>
            刚才的卡片大多安排在本轮内重现，稍后刷新队列还会看到它们。
          </template>
        </p>
        <div class="result-tags">
          <el-tag
            v-for="result in sessionResults"
            :key="result.rating + result.dueAt + result.ratingLabel"
            size="small"
            effect="plain"
          >
            {{ result.ratingLabel }} ·
            {{ result.reintroduceSoon ? '本轮重现' : `${result.intervalDays} 天` }}
          </el-tag>
        </div>
        <div class="empty-state__actions">
          <el-button type="primary" @click="loadQueue()">刷新队列</el-button>
          <el-button @click="router.push({ name: 'dashboard' })">回仪表盘</el-button>
        </div>
      </div>

      <!-- 复习进行中 -->
      <template v-else-if="current">
        <div class="progress-bar">
          <div class="progress-bar__info">
            <span>第 {{ index + 1 }} / {{ items.length }} 张</span>
            <span class="jis-muted">
              到期 {{ queue?.dueCount ?? 0 }} 张 · 新卡 {{ queue?.newCount ?? 0 }} 张 ·
              今日还剩 {{ queue?.remainingNewQuota ?? 0 }} 个新卡名额
            </span>
          </div>
          <el-progress :percentage="progress" :show-text="false" :stroke-width="5" />
        </div>

        <div class="prompt jis-card">
          <div class="prompt__meta">
            <el-tag size="small" effect="plain">{{ current.moduleName }}</el-tag>
            <span v-if="current.masteryState === null" class="prompt__new">新卡</span>
            <span class="jis-muted">{{ current.dueAt ? `到期 ${current.dueAt.slice(0, 10)}` : '' }}</span>
          </div>

          <h2 class="prompt__title">{{ current.title }}</h2>

          <p v-if="!revealed" class="prompt__hint-text">
            先用嘴讲一遍：结论是什么、为什么、有没有反例。讲完再对照答案。
          </p>

          <div class="prompt__actions">
            <el-button v-if="!revealed" type="primary" @click="reveal">
              显示参考答案
              <kbd class="prompt__kbd">空格</kbd>
            </el-button>
            <el-button v-else type="primary" plain @click="revealed = false">
              重新遮住答案
            </el-button>
          </div>
        </div>

        <template v-if="revealed">
          <div v-loading="detailLoading" class="answer">
            <section class="block block--elevator">
              <div class="block__label">
                <el-icon><Microphone /></el-icon>
                电梯版回答
              </div>
              <MarkdownView v-if="detail" :content="detail.elevatorAnswer" />
              <div v-else class="jis-muted">加载中…</div>
            </section>

            <div v-if="detail?.detailMd" class="answer__toggle">
              <el-button text type="primary" @click="showDetail = !showDetail">
                {{ showDetail ? '收起展开讲解' : '展开讲解（原理与源码）' }}
              </el-button>
            </div>

            <section v-if="showDetail && detail?.detailMd" class="block">
              <MarkdownView :content="detail.detailMd" />
            </section>

            <section v-if="detail && detail.followUps.length > 0" class="block block--followup">
              <div class="block__label">
                <el-icon><ChatLineSquare /></el-icon>
                追问链
                <span class="block__label-hint">面试官大概率会继续追这些</span>
                <el-button text size="small" @click="toggleAllFollowUps">
                  {{ allExpanded ? '全部收起' : '全部展开' }}
                </el-button>
              </div>
              <FollowUpTree
                :nodes="detail.followUps"
                :expanded-keys="expandedKeys"
                @toggle="toggleFollowUp"
              />
            </section>
          </div>

          <div class="rating">
            <div class="rating__label">这张卡你答得怎么样？</div>
            <div class="rating__buttons">
              <button
                v-for="option in RATINGS"
                :key="option.value"
                class="rating__button"
                :class="`rating__button--${option.type}`"
                type="button"
                :disabled="rating"
                @click="submitRating(option.value)"
              >
                <span class="rating__button-label">
                  {{ option.label }}
                  <kbd>{{ option.key }}</kbd>
                </span>
                <span class="rating__button-hint">{{ option.hint }}</span>
              </button>
            </div>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.review {
  min-height: 60vh;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 56px 24px;
  text-align: center;
}

.empty-state__icon {
  font-size: 42px;
  color: var(--jis-accent);
}

.empty-state__title {
  margin: 4px 0 0;
  font-size: 19px;
  font-weight: 660;
}

.empty-state__text {
  max-width: 520px;
  margin: 0;
  color: var(--jis-text-secondary);
  font-size: 13.5px;
}

.empty-state__text strong {
  color: var(--jis-accent-strong);
}

.empty-state__actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
  max-width: 560px;
  margin-top: 8px;
}

.progress-bar {
  margin-bottom: 14px;
}

.progress-bar__info {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
  font-size: 13px;
}

.prompt {
  margin-bottom: 16px;
}

.prompt__meta {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 12px;
  font-size: 12.5px;
}

.prompt__new {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-size: 11.5px;
  font-weight: 600;
}

.prompt__title {
  margin: 0 0 12px;
  font-size: 20px;
  font-weight: 670;
  line-height: 1.5;
}

.prompt__hint-text {
  margin: 0 0 12px;
  padding: 12px 14px;
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface-muted);
  color: var(--jis-text-secondary);
  font-size: 13.5px;
  line-height: 1.8;
}

.prompt__actions {
  display: flex;
  gap: 10px;
}

.prompt__kbd {
  margin-left: 6px;
  padding: 0 5px;
  border: 1px solid currentcolor;
  border-radius: 4px;
  font-size: 11px;
  opacity: 0.75;
}

.answer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.answer__toggle {
  text-align: center;
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

.block--elevator {
  border-left-color: var(--jis-elevator-border);
  background: linear-gradient(180deg, var(--jis-elevator), var(--jis-surface) 78%);
}

.block--followup {
  border-left-color: var(--jis-followup-border);
}

.rating {
  margin-top: 18px;
  padding: 18px 20px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius);
  background: var(--jis-surface);
}

.rating__label {
  margin-bottom: 12px;
  color: var(--jis-text-secondary);
  font-size: 13.5px;
}

.rating__buttons {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.rating__button {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface);
  text-align: left;
  cursor: pointer;
  /* 刻意不用 transition: all，也不做 hover 位移：
     transform 会让按钮在指针下移动，指针停在边缘时会出现
     「悬停→移开→回弹」的抖动，元素变成移动靶，自动化和误触都会受影响 */
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.rating__button:hover:not(:disabled) {
  background: var(--jis-surface-muted);
}

.rating__button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.rating__button-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
  font-weight: 640;
}

.rating__button-label kbd {
  padding: 0 5px;
  border: 1px solid var(--jis-border);
  border-radius: 4px;
  background: var(--jis-surface-muted);
  color: var(--jis-text-muted);
  font-size: 11px;
  font-weight: 400;
}

.rating__button-hint {
  color: var(--jis-text-muted);
  font-size: 11.5px;
}

.rating__button--danger:hover:not(:disabled) {
  border-color: #ef4444;
}
.rating__button--danger .rating__button-label {
  color: #ef4444;
}

.rating__button--warning:hover:not(:disabled) {
  border-color: #f59e0b;
}
.rating__button--warning .rating__button-label {
  color: #f59e0b;
}

.rating__button--primary:hover:not(:disabled) {
  border-color: var(--jis-accent);
}
.rating__button--primary .rating__button-label {
  color: var(--jis-accent);
}

.rating__button--success:hover:not(:disabled) {
  border-color: #10b981;
}
.rating__button--success .rating__button-label {
  color: #10b981;
}

@media (max-width: 720px) {
  .rating__buttons {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

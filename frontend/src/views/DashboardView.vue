<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchHeatmap, fetchModuleMastery, fetchOverview } from '@/api/stats'
import HeatmapGrid from '@/components/HeatmapGrid.vue'
import { useAuthStore } from '@/stores/auth'
import type { HeatmapVO, ModuleMasteryVO, OverviewVO } from '@/types'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const overview = ref<OverviewVO | null>(null)
const heatmap = ref<HeatmapVO | null>(null)
const modules = ref<ModuleMasteryVO[]>([])

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) {
    return '夜深了'
  }
  if (hour < 12) {
    return '早上好'
  }
  if (hour < 18) {
    return '下午好'
  }

  return '晚上好'
})

const hasWorkToDo = computed(
  () => (overview.value?.dueCount ?? 0) > 0 || (overview.value?.remainingNewQuota ?? 0) > 0,
)

const masteryPercent = computed(() => {
  const total = overview.value?.totalCards ?? 0
  if (total === 0) {
    return 0
  }

  return Math.round(((overview.value?.masteredCards ?? 0) * 100) / total)
})

const sortedModules = computed(() =>
  [...modules.value].filter((module) => module.total > 0).sort((a, b) => b.total - a.total),
)

onMounted(async () => {
  try {
    const [overviewData, heatmapData, moduleData] = await Promise.all([
      fetchOverview(),
      fetchHeatmap(),
      fetchModuleMastery(),
    ])

    overview.value = overviewData
    heatmap.value = heatmapData
    modules.value = moduleData
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="jis-page">
    <div class="hero">
      <div>
        <h2 class="hero__title">{{ greeting }}，{{ auth.displayName }}</h2>
        <p class="hero__subtitle">
          <template v-if="hasWorkToDo">
            今天有
            <strong>{{ overview?.dueCount ?? 0 }}</strong>
            张卡片到期，还可以引入
            <strong>{{ Math.min(overview?.remainingNewQuota ?? 0, overview?.newCount ?? 0) }}</strong>
            张新卡。
          </template>
          <template v-else-if="overview && overview.totalCards > 0">
            今天的复习队列已经清空了，可以去做几道自测巩固一下。
          </template>
          <template v-else> 还没有内容，先去「设置」里导入一次知识点卡片。 </template>
        </p>
      </div>

      <div class="hero__actions">
        <el-button type="primary" size="large" @click="router.push({ name: 'review' })">
          <el-icon><Refresh /></el-icon>
          开始复习
        </el-button>
        <el-button size="large" @click="router.push({ name: 'quiz' })">
          <el-icon><EditPen /></el-icon>
          做几道题
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="stats">
      <div class="stat">
        <div class="stat__label">待复习</div>
        <div class="stat__value">{{ overview?.dueCount ?? 0 }}</div>
        <div class="stat__hint">还有 {{ overview?.newCount ?? 0 }} 张新卡未开始</div>
      </div>

      <div class="stat">
        <div class="stat__label">今日已复习</div>
        <div class="stat__value">{{ overview?.reviewedToday ?? 0 }}</div>
        <div class="stat__hint">
          今日答题 {{ overview?.quizToday ?? 0 }} 道
          <template v-if="overview?.accuracyToday !== null && overview?.accuracyToday !== undefined">
            ，正确率 {{ overview.accuracyToday }}%
          </template>
        </div>
      </div>

      <div class="stat">
        <div class="stat__label">连续打卡</div>
        <div class="stat__value">{{ overview?.streakDays ?? 0 }}<small> 天</small></div>
        <div class="stat__hint">每天复习一点，间隔会越拉越长</div>
      </div>

      <div class="stat">
        <div class="stat__label">已掌握</div>
        <div class="stat__value">{{ overview?.masteredCards ?? 0 }}<small> / {{ overview?.totalCards ?? 0 }}</small></div>
        <div class="stat__hint">占全部卡片 {{ masteryPercent }}%</div>
      </div>
    </div>

    <div class="panels">
      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><Collection /></el-icon>
          各模块掌握情况
        </h3>

        <div v-if="sortedModules.length === 0" class="jis-empty">还没有可统计的内容</div>

        <div v-else class="modules">
          <div v-for="module in sortedModules" :key="module.slug" class="module">
            <div class="module__head">
              <button
                class="module__name"
                type="button"
                @click="router.push({ name: 'knowledge', query: { module: module.slug } })"
              >
                {{ module.name }}
              </button>
              <span class="module__count">
                {{ module.mastered }} / {{ module.total }} 已掌握
              </span>
            </div>
            <el-progress
              :percentage="module.masteryRate"
              :stroke-width="7"
              :show-text="false"
              color="var(--jis-accent)"
            />
          </div>
        </div>
      </section>

      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><Calendar /></el-icon>
          学习热力图
        </h3>

        <HeatmapGrid v-if="heatmap" :heatmap="heatmap" />
        <div v-else class="jis-empty">暂无学习记录</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 20px;
  padding: 22px 24px;
  border-radius: var(--jis-radius);
  background:
    radial-gradient(circle at 92% 15%, rgba(168, 85, 247, 0.16), transparent 55%),
    linear-gradient(120deg, var(--jis-accent-soft), transparent 65%),
    var(--jis-surface);
  border: 1px solid var(--jis-border);
}

.hero__title {
  margin: 0 0 6px;
  font-size: 21px;
  font-weight: 680;
}

.hero__subtitle {
  margin: 0;
  color: var(--jis-text-secondary);
  font-size: 13.5px;
}

.hero__subtitle strong {
  color: var(--jis-accent-strong);
  font-size: 15px;
}

.hero__actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat {
  padding: 16px 18px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius);
  background: var(--jis-surface);
  box-shadow: var(--jis-shadow);
}

.stat__label {
  color: var(--jis-text-muted);
  font-size: 12.5px;
}

.stat__value {
  margin: 4px 0 6px;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
}

.stat__value small {
  font-size: 14px;
  font-weight: 500;
  color: var(--jis-text-muted);
}

.stat__hint {
  color: var(--jis-text-muted);
  font-size: 12px;
}

.panels {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
  gap: 16px;
}

.modules {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.module__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.module__name {
  padding: 0;
  border: none;
  background: none;
  color: var(--jis-text);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
}

.module__name:hover {
  color: var(--jis-accent);
}

.module__count {
  color: var(--jis-text-muted);
  font-size: 12px;
}

@media (max-width: 1080px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .panels {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .hero {
    padding: 18px;
  }

  .hero__actions {
    width: 100%;
  }

  .hero__actions :deep(.el-button) {
    flex: 1;
  }
}
</style>

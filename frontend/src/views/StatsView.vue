<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { fetchHeatmap, fetchModuleMastery, fetchOverview } from '@/api/stats'
import HeatmapGrid from '@/components/HeatmapGrid.vue'
import MasteryRadar from '@/components/MasteryRadar.vue'
import type { HeatmapVO, ModuleMasteryVO, OverviewVO } from '@/types'

const loading = ref(true)
const year = ref(new Date().getFullYear())
const overview = ref<OverviewVO | null>(null)
const heatmap = ref<HeatmapVO | null>(null)
const modules = ref<ModuleMasteryVO[]>([])

const yearOptions = computed(() => {
  const current = new Date().getFullYear()
  // 从首次使用年份到今年就够，再早也不可能有记录
  return Array.from({ length: 4 }, (_, index) => current - index)
})

const startedRate = computed(() => {
  const total = overview.value?.totalCards ?? 0
  if (total === 0) {
    return 0
  }

  return Math.round(((overview.value?.trackedCards ?? 0) * 100) / total)
})

async function loadHeatmap(): Promise<void> {
  heatmap.value = await fetchHeatmap(year.value)
}

onMounted(async () => {
  try {
    const [overviewData, heatmapData, moduleData] = await Promise.all([
      fetchOverview(),
      fetchHeatmap(year.value),
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
  <div class="jis-page ">
    <div class="jis-page-header">
      <h2 class="jis-page-title">学习统计</h2>
      <p class="jis-page-subtitle">
        「已掌握」的判定标准是记忆稳定性达到 21 天——这个阈值可以在后端
        jis.review.mastered-stability-days 调整。
      </p>
    </div>

    <div v-loading="loading" class="content">
      <div class="cards">
        <div class="metric jis-card">
          <div class="metric__label">已开始复习</div>
          <div class="metric__value">{{ overview?.trackedCards ?? 0 }} / {{ overview?.totalCards ?? 0 }}</div>
          <el-progress :percentage="startedRate" :show-text="false" :stroke-width="6" />
        </div>

        <div class="metric jis-card">
          <div class="metric__label">已掌握</div>
          <div class="metric__value">{{ overview?.masteredCards ?? 0 }} 张</div>
          <div class="metric__hint jis-muted">稳定性 ≥ 21 天</div>
        </div>

        <div class="metric jis-card">
          <div class="metric__label">连续打卡</div>
          <div class="metric__value">{{ overview?.streakDays ?? 0 }} 天</div>
          <div class="metric__hint jis-muted">今天不学也不会立刻归零</div>
        </div>

        <div class="metric jis-card">
          <div class="metric__label">今日正确率</div>
          <div class="metric__value">
            {{ overview?.accuracyToday === null || overview?.accuracyToday === undefined ? '—' : `${overview.accuracyToday}%` }}
          </div>
          <div class="metric__hint jis-muted">今日答题 {{ overview?.quizToday ?? 0 }} 道</div>
        </div>
      </div>

      <section class="jis-card">
        <div class="panel-head">
          <h3 class="jis-section-title">
            <el-icon><DataLine /></el-icon>
            模块掌握度
          </h3>
          <el-button text size="small" @click="$router.push({ name: 'knowledge' })">
            去学习
          </el-button>
        </div>
        <MasteryRadar :modules="modules" />
      </section>

      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><Collection /></el-icon>
          各模块明细
        </h3>

        <el-table :data="modules" style="width: 100%" row-key="slug" default-expand-all>
          <el-table-column prop="name" label="模块" min-width="180" />
          <el-table-column prop="total" label="卡片数" width="90" align="right" />
          <el-table-column prop="started" label="已开始" width="90" align="right" />
          <el-table-column prop="mastered" label="已掌握" width="90" align="right" />
          <el-table-column label="掌握率" min-width="160">
            <template #default="{ row }">
              <el-progress
                :percentage="row.masteryRate"
                :stroke-width="6"
                :show-text="false"
                color="var(--jis-accent)"
              />
              <span class="rate-text">{{ row.masteryRate }}%</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="jis-card">
        <div class="panel-head">
          <h3 class="jis-section-title">
            <el-icon><Calendar /></el-icon>
            学习热力图
          </h3>
          <el-select v-model="year" size="small" style="width: 96px" @change="loadHeatmap">
            <el-option v-for="option in yearOptions" :key="option" :label="`${option} 年`" :value="option" />
          </el-select>
        </div>

        <HeatmapGrid v-if="heatmap" :heatmap="heatmap" />
        <div v-else class="jis-empty">这一年还没有学习记录</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 300px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric__label {
  color: var(--jis-text-muted);
  font-size: 12.5px;
  margin-bottom: 6px;
}

.metric__value {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 8px;
  letter-spacing: -0.01em;
}

.metric__hint {
  font-size: 12px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rate-text {
  margin-left: 8px;
  color: var(--jis-text-muted);
  font-size: 12px;
}

@media (max-width: 1080px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

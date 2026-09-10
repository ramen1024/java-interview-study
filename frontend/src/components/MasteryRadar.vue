<script setup lang="ts">
import * as echarts from 'echarts'
import { onMounted, onUnmounted, ref, watch } from 'vue'

import { useUiStore } from '@/stores/ui'
import type { ModuleMasteryVO } from '@/types'

const props = defineProps<{
  modules: ModuleMasteryVO[]
}>()

const ui = useUiStore()
const container = ref<HTMLElement | null>(null)

let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function render(): void {
  if (!container.value) {
    return
  }

  // 模块数量太少时雷达图会退化成三角形甚至一条线，没有信息量
  const data = props.modules.filter((module) => module.total > 0)
  if (data.length < 3) {
    disposeChart()
    return
  }

  chart = chart ?? echarts.init(container.value)
  const isDark = ui.theme === 'dark'
  const axisColor = isDark ? '#7b8296' : '#8b93a7'
  const splitColor = isDark ? '#2c313d' : '#e6e8f0'

  chart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: () => {
        const lines = data.map(
          (module) =>
            `${module.name}：掌握 ${module.mastered}/${module.total}（${module.masteryRate}%）`,
        )

        return lines.join('<br/>')
      },
    },
    radar: {
      indicator: data.map((module) => ({ name: module.name, max: 100 })),
      radius: '66%',
      axisName: { color: axisColor, fontSize: 12 },
      splitLine: { lineStyle: { color: splitColor } },
      splitArea: {
        areaStyle: {
          color: isDark
            ? ['rgba(99,102,241,0.02)', 'rgba(99,102,241,0.06)']
            : ['rgba(99,102,241,0.02)', 'rgba(99,102,241,0.05)'],
        },
      },
      axisLine: { lineStyle: { color: splitColor } },
    },
    series: [
      {
        type: 'radar',
        symbolSize: 5,
        data: [
          {
            value: data.map((module) => module.masteryRate),
            name: '掌握率',
            areaStyle: { color: 'rgba(99,102,241,0.28)' },
            lineStyle: { color: '#6366f1', width: 2 },
            itemStyle: { color: '#6366f1' },
          },
        ],
      },
    ],
  })
}

function disposeChart(): void {
  chart?.dispose()
  chart = null
}

onMounted(() => {
  render()

  if (container.value) {
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(container.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  disposeChart()
})

watch(() => props.modules, render, { deep: true })
// 切换主题要重画，否则轴线颜色会停留在旧主题上
watch(() => ui.theme, render)
</script>

<template>
  <div v-if="modules.filter((module) => module.total > 0).length >= 3" ref="container" class="radar" />
  <div v-else class="jis-empty">
    至少要有 3 个模块包含卡片才能画出掌握度雷达图
  </div>
</template>

<style scoped>
.radar {
  width: 100%;
  height: 320px;
}
</style>

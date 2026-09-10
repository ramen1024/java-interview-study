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
  ensureResizeObserver()
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

/**
 * 观察容器尺寸变化，窗口缩放时让 echarts 重新布局。
 *
 * 必须在容器真正挂载后才建立观察，所以由 render() 调用而不是
 * 在 onMounted 里直接做——那时容器受 v-if 控制尚未存在。
 * 用 chart 是否为空做幂等判断，重复调用不会叠加多个观察者。
 */
function ensureResizeObserver(): void {
  if (resizeObserver || !container.value) {
    return
  }

  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(container.value)
}

onMounted(render)

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  container.value = null
  disposeChart()
})

// flush: 'post' 是必需的，不是可选优化。
//
// 容器 div 受 v-if 控制，数据没到时它根本不在 DOM 里、ref 为 null。
// 而 watcher 默认在 'pre' 时机执行——回调跑在组件重新渲染**之前**，
// 此时 ref 仍是 null，render() 会提前返回，之后再也没有机会被调用，
// 结果是容器存在、尺寸正常、但里面永远是空的（echarts 从未初始化）。
// 'post' 保证回调在 DOM 更新之后执行，ref 此时已挂上。
watch(() => props.modules, render, { deep: true, flush: 'post' })

// 主题切换只改颜色，不涉及容器挂载，但同样放在 DOM 更新后执行更稳妥
watch(() => ui.theme, render, { flush: 'post' })
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

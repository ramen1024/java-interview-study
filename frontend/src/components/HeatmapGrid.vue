<script setup lang="ts">
import { computed } from 'vue'

import type { HeatmapVO } from '@/types'

const props = defineProps<{
  heatmap: HeatmapVO
}>()

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']

interface Cell {
  key: string
  date: string | null
  count: number
  level: number
  tooltip: string
}

const MONTH_LABELS = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月']

const weeks = computed<Cell[][]>(() => {
  const year = props.heatmap.year
  const countByDate = new Map(props.heatmap.days.map((day) => [day.date, day]))

  const firstDay = new Date(year, 0, 1)
  const lastDay = new Date(year, 11, 31)

  // 从第一周的周日开始铺，保证每列固定是同一星期的七天
  const gridStart = new Date(firstDay)
  gridStart.setDate(gridStart.getDate() - gridStart.getDay())

  const result: Cell[][] = []
  const cursor = new Date(gridStart)

  while (cursor <= lastDay) {
    const column: Cell[] = []

    for (let weekday = 0; weekday < 7; weekday++) {
      const inRange = cursor >= firstDay && cursor <= lastDay
      const date = formatDate(cursor)
      const record = countByDate.get(date)

      column.push({
        key: date,
        date: inRange ? date : null,
        count: record?.count ?? 0,
        level: record?.level ?? 0,
        tooltip: inRange
          ? `${date}：复习 ${record?.reviewCount ?? 0} 次，答题 ${record?.quizCount ?? 0} 道`
          : '',
      })

      cursor.setDate(cursor.getDate() + 1)
    }

    result.push(column)
  }

  return result
})

/** 月份标签出现的列索引，用于在网格上方标注月份。 */
const monthColumns = computed(() => {
  const markers: { column: number; label: string }[] = []
  let lastMonth = -1

  weeks.value.forEach((column, index) => {
    const firstDate = column.find((cell) => cell.date)?.date
    if (!firstDate) {
      return
    }

    const month = Number(firstDate.slice(5, 7)) - 1
    if (month !== lastMonth) {
      markers.push({ column: index, label: MONTH_LABELS[month] ?? '' })
      lastMonth = month
    }
  })

  return markers
})

function formatDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')

  return `${date.getFullYear()}-${month}-${day}`
}
</script>

<template>
  <div class="heatmap">
    <div class="heatmap__summary">
      {{ heatmap.year }} 年共学习
      <strong>{{ heatmap.totalCount }}</strong>
      次，
      <strong>{{ heatmap.activeDays }}</strong>
      天有记录
    </div>

    <div class="heatmap__scroll">
      <div class="heatmap__months" :style="{ gridTemplateColumns: `repeat(${weeks.length}, 12px)` }">
        <span
          v-for="marker in monthColumns"
          :key="marker.column"
          :style="{ gridColumnStart: marker.column + 1 }"
          class="heatmap__month"
        >
          {{ marker.label }}
        </span>
      </div>

      <div class="heatmap__body">
        <div class="heatmap__weekdays">
          <span v-for="label in WEEKDAY_LABELS" :key="label">{{ label }}</span>
        </div>

        <div class="heatmap__grid" :style="{ gridTemplateColumns: `repeat(${weeks.length}, 12px)` }">
          <template v-for="(column, columnIndex) in weeks" :key="columnIndex">
            <el-tooltip
              v-for="cell in column"
              :key="cell.key"
              :content="cell.tooltip"
              :disabled="!cell.date"
              placement="top"
            >
              <span
                class="heatmap__cell"
                :class="[`heatmap__cell--level-${cell.level}`, { 'heatmap__cell--empty': !cell.date }]"
              />
            </el-tooltip>
          </template>
        </div>
      </div>
    </div>

    <div class="heatmap__legend">
      <span>少</span>
      <span class="heatmap__cell heatmap__cell--level-0" />
      <span class="heatmap__cell heatmap__cell--level-1" />
      <span class="heatmap__cell heatmap__cell--level-2" />
      <span class="heatmap__cell heatmap__cell--level-3" />
      <span class="heatmap__cell heatmap__cell--level-4" />
      <span>多</span>
    </div>
  </div>
</template>

<style scoped>
.heatmap__summary {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--jis-text-secondary);
}

.heatmap__summary strong {
  color: var(--jis-accent-strong);
}

.heatmap__scroll {
  overflow-x: auto;
  padding-bottom: 4px;
}

.heatmap__months {
  display: grid;
  margin-left: 22px;
  margin-bottom: 3px;
  font-size: 10.5px;
  color: var(--jis-text-muted);
  min-width: max-content;
}

.heatmap__month {
  white-space: nowrap;
}

.heatmap__body {
  display: flex;
  gap: 6px;
}

.heatmap__weekdays {
  display: grid;
  grid-template-rows: repeat(7, 12px);
  gap: 3px;
  font-size: 10px;
  line-height: 12px;
  color: var(--jis-text-muted);
  text-align: right;
}

.heatmap__grid {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: repeat(7, 12px);
  gap: 3px;
  min-width: max-content;
}

.heatmap__cell {
  display: block;
  width: 12px;
  height: 12px;
  border-radius: 2.5px;
  background: var(--jis-border);
}

.heatmap__cell--empty {
  visibility: hidden;
}

.heatmap__cell--level-0 {
  background: var(--jis-surface-muted);
  border: 1px solid var(--jis-border);
}

.heatmap__cell--level-1 {
  background: #c7d2fe;
}

.heatmap__cell--level-2 {
  background: #a5b4fc;
}

.heatmap__cell--level-3 {
  background: #818cf8;
}

.heatmap__cell--level-4 {
  background: #4f46e5;
}

html.dark .heatmap__cell--level-1 {
  background: #3730a3;
}

html.dark .heatmap__cell--level-2 {
  background: #4f46e5;
}

html.dark .heatmap__cell--level-3 {
  background: #6366f1;
}

html.dark .heatmap__cell--level-4 {
  background: #a5b4fc;
}

.heatmap__legend {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  justify-content: flex-end;
  font-size: 11px;
  color: var(--jis-text-muted);
}
</style>

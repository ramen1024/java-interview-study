import { request } from '@/api/http'
import type { HeatmapVO, ModuleMasteryVO, OverviewVO } from '@/types'

export function fetchOverview(): Promise<OverviewVO> {
  return request<OverviewVO>({
    url: '/stats/overview',
    method: 'get',
  })
}

export function fetchHeatmap(year?: number): Promise<HeatmapVO> {
  return request<HeatmapVO>({
    url: '/stats/heatmap',
    method: 'get',
    params: { year },
  })
}

export function fetchModuleMastery(): Promise<ModuleMasteryVO[]> {
  return request<ModuleMasteryVO[]>({
    url: '/stats/modules',
    method: 'get',
  })
}

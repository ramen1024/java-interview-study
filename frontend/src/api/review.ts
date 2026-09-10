import { request } from '@/api/http'
import type { RateResultVO, ReviewQueueVO } from '@/types'

export function fetchReviewQueue(params: {
  moduleSlug?: string
  limit?: number
}): Promise<ReviewQueueVO> {
  return request<ReviewQueueVO>({
    url: '/review/queue',
    method: 'get',
    params,
  })
}

/**
 * 提交自评。rating：1 不会 / 2 模糊 / 3 会讲 / 4 轻松。
 */
export function rateCard(
  slug: string,
  rating: number,
  durationMs: number,
): Promise<RateResultVO> {
  return request<RateResultVO>({
    url: `/review/cards/${slug}/rate`,
    method: 'post',
    data: { rating, durationMs },
  })
}

export function resetProgress(slug: string): Promise<void> {
  return request<void>({
    url: `/review/cards/${slug}/progress`,
    method: 'delete',
  })
}

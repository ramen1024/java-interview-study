import { request } from '@/api/http'
import type { QuizQuestionVO, QuizSubmitResultVO } from '@/types'

export interface QuizAnswerPayload {
  qKey: string
  answer?: string
  blanks?: string[]
}

export function drawQuestions(params: {
  moduleSlug?: string
  type?: string
  count?: number
}): Promise<QuizQuestionVO[]> {
  return request<QuizQuestionVO[]>({
    url: '/quiz/questions',
    method: 'get',
    params,
  })
}

export function submitAnswers(
  answers: QuizAnswerPayload[],
  durationSec: number,
): Promise<QuizSubmitResultVO> {
  return request<QuizSubmitResultVO>({
    url: '/quiz/submit',
    method: 'post',
    data: { answers, durationSec },
  })
}

export function fetchWrongBook(): Promise<QuizQuestionVO[]> {
  return request<QuizQuestionVO[]>({
    url: '/quiz/wrong',
    method: 'get',
  })
}

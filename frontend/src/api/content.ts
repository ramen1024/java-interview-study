import { request } from '@/api/http'
import type {
  ImportResultVO,
  KnowledgePointListItemVO,
  KnowledgePointQuery,
  KnowledgePointView,
  ModuleTreeVO,
  PageResult,
  SearchHitVO,
} from '@/types'

export function fetchModuleTree(): Promise<ModuleTreeVO[]> {
  return request<ModuleTreeVO[]>({
    url: '/content/modules',
    method: 'get',
  })
}

export function fetchCards(
  query: KnowledgePointQuery,
): Promise<PageResult<KnowledgePointListItemVO>> {
  return request<PageResult<KnowledgePointListItemVO>>({
    url: '/content/cards',
    method: 'get',
    params: query,
  })
}

export function fetchCard(slug: string): Promise<KnowledgePointView> {
  return request<KnowledgePointView>({
    url: `/content/cards/${slug}`,
    method: 'get',
  })
}

export function searchCards(keyword: string): Promise<SearchHitVO[]> {
  return request<SearchHitVO[]>({
    url: '/content/search',
    method: 'get',
    params: { keyword },
  })
}

export function fetchHotKeywords(): Promise<string[]> {
  return request<string[]>({
    url: '/content/search/hot',
    method: 'get',
  })
}

export function setFavorite(slug: string, favorite: boolean): Promise<boolean> {
  return request<boolean>({
    url: `/content/cards/${slug}/favorite`,
    method: 'put',
    params: { favorite },
  })
}

export function fetchFavorites(): Promise<KnowledgePointListItemVO[]> {
  return request<KnowledgePointListItemVO[]>({
    url: '/content/favorites',
    method: 'get',
  })
}

export function saveNote(slug: string, contentMd: string): Promise<void> {
  return request<void>({
    url: `/content/cards/${slug}/note`,
    method: 'put',
    data: { contentMd },
  })
}

/** 手动触发内容导入，改完 Markdown 不必重启后端。 */
export function reimportContent(): Promise<ImportResultVO> {
  return request<ImportResultVO>({
    url: '/admin/content/import',
    method: 'post',
  })
}

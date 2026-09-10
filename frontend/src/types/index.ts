/**
 * 与后端 DTO 一一对应的类型定义。
 *
 * 约定：所有 id 类字段都是字符串。后端刻意把 Long 序列化成 String，
 * 因为 BIGINT 超出 JavaScript 的安全整数范围；但内容类接口一律用
 * 业务键（slug / qKey）标识资源，所以这里几乎看不到 id。
 */

/** 后端统一响应体。HTTP 状态恒为 200，业务结果看 code。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

/* ------------------------------------------------------------------ 认证 */

export interface UserVO {
  id: string
  username: string
  nickname: string
  email: string
  role: string
  createTime: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

/* ------------------------------------------------------------------ 内容 */

export interface ModuleTreeVO {
  slug: string
  name: string
  description: string
  icon: string
  sort: number
  cardCount: number
  children: ModuleTreeVO[]
}

export interface KnowledgePointListItemVO {
  slug: string
  title: string
  moduleSlug: string | null
  moduleName: string | null
  tags: string[]
  difficulty: number
  frequency: number
  questionCount: number
  /** 0 未复习 / 1 学习中 / 2 复习中 / 3 重新学习；null 表示没有复习记录 */
  masteryState: number | null
  dueAt: string | null
}

export interface FollowUpVO {
  qKey: string
  question: string
  answerMd: string
  depth: number
  children: FollowUpVO[]
}

export interface RelatedKpVO {
  slug: string
  title: string
  moduleName: string | null
  relationType: string
  difficulty: number
  frequency: number
}

export interface KnowledgePointDetailVO {
  slug: string
  title: string
  moduleSlug: string | null
  moduleName: string | null
  tags: string[]
  difficulty: number
  frequency: number
  elevatorAnswer: string
  detailMd: string
  followUps: FollowUpVO[]
  pitfallsMd: string
  bonusMd: string
  versionDiffMd: string
  related: RelatedKpVO[]
  questionCount: number
  updatedAt: string | null
}

export interface UserCardStateVO {
  favorite: boolean
  noteMd: string
  noteUpdatedAt: string | null
  masteryState: number | null
  dueAt: string | null
  stability: number | null
  reps: number | null
  lapses: number | null
}

export interface KnowledgePointView {
  card: KnowledgePointDetailVO
  userState: UserCardStateVO
}

export interface KnowledgePointQuery {
  moduleSlug?: string
  tag?: string
  difficulty?: number
  frequency?: number
  sortBy?: string
  page?: number
  size?: number
}

export interface SearchHitVO {
  slug: string
  title: string
  moduleName: string | null
  tags: string[]
  difficulty: number
  frequency: number
  snippet: string
}

/* ------------------------------------------------------------------ 复习 */

export interface ReviewQueueVO {
  items: KnowledgePointListItemVO[]
  dueCount: number
  newCount: number
  remainingNewQuota: number
}

export interface RateResultVO {
  rating: number
  ratingLabel: string
  state: number
  stateLabel: string
  stabilityBefore: number | null
  stabilityAfter: number
  difficultyAfter: number
  intervalDays: number
  reintroduceSoon: boolean
  dueAt: string
}

/* ------------------------------------------------------------------ 自测 */

export interface QuizOption {
  key: string
  text: string
}

export interface QuizQuestionVO {
  qKey: string
  type: string
  typeLabel: string
  stemMd: string
  options: QuizOption[] | null
  blanksCount: number | null
  difficulty: number
  kpSlug: string | null
  kpTitle: string | null
  moduleName: string | null
  /** 抽题时为 null，判分后才有值 */
  answer: string | null
  blanks: string[][] | null
  analysisMd: string | null
}

export interface QuizResultVO {
  qKey: string
  type: string
  correct: boolean
  userAnswer: string | null
  correctAnswer: string | null
  blankResults: boolean[] | null
  blanks: string[][] | null
  analysisMd: string | null
}

export interface QuizSubmitResultVO {
  total: number
  correctCount: number
  accuracy: number
  results: QuizResultVO[]
}

/* ------------------------------------------------------------------ 统计 */

export interface OverviewVO {
  dueCount: number
  newCount: number
  remainingNewQuota: number
  reviewedToday: number
  quizToday: number
  accuracyToday: number | null
  streakDays: number
  totalCards: number
  trackedCards: number
  masteredCards: number
}

export interface HeatmapDayVO {
  date: string
  count: number
  reviewCount: number
  quizCount: number
  level: number
}

export interface HeatmapVO {
  year: number
  totalCount: number
  activeDays: number
  days: HeatmapDayVO[]
}

export interface ModuleMasteryVO {
  slug: string
  name: string
  total: number
  started: number
  mastered: number
  masteryRate: number
  children: ModuleMasteryVO[]
}

/* ------------------------------------------------------------------ 导入 */

export interface ImportResultVO {
  moduleCount: number
  cardCount: number
  followUpCount: number
  questionCount: number
  relationCount: number
  elapsedMs: number
  warnings: string[]
  errors: string[]
}

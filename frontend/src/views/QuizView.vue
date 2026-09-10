<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchModuleTree } from '@/api/content'
import { drawQuestions, submitAnswers } from '@/api/quiz'
import type { QuizAnswerPayload } from '@/api/quiz'
import {
  renderClozeReview,
  renderClozeStem,
  renderMarkdown,
} from '@/composables/useMarkdown'
import type { ModuleTreeVO, QuizQuestionVO, QuizSubmitResultVO } from '@/types'

const route = useRoute()
const router = useRouter()

type Stage = 'setup' | 'answering' | 'reviewing'

const stage = ref<Stage>('setup')
const modules = ref<ModuleTreeVO[]>([])
const drawing = ref(false)
const submitting = ref(false)

const setup = ref({
  moduleSlug: undefined as string | undefined,
  type: undefined as string | undefined,
  count: 10,
})

const questions = ref<QuizQuestionVO[]>([])
/** 选择题与判断题的作答：qKey → 选项字母 */
const choices = ref<Record<string, string>>({})
const result = ref<QuizSubmitResultVO | null>(null)
const startedAt = ref(Date.now())
const clueContainer = ref<HTMLElement | null>(null)

/** 挖空题的作答：qKey → 各空填写值。 */
const clozeValues = ref<Record<string, string[]>>({})

const QUESTION_TYPES = [
  { label: '混合题型', value: undefined },
  { label: '单选', value: 'CHOICE' },
  { label: '多选', value: 'MULTI' },
  { label: '判断', value: 'JUDGE' },
  { label: '代码挖空', value: 'CLOZE' },
]

const moduleOptions = computed(() => {
  const options: { label: string; value: string }[] = []

  for (const module of modules.value) {
    options.push({ label: module.name, value: module.slug })
    for (const child of module.children) {
      options.push({ label: `　${child.name}`, value: child.slug })
    }
  }

  return options
})

const resultByKey = computed(() => {
  const map = new Map<string, QuizSubmitResultVO['results'][number]>()
  result.value?.results.forEach((item) => map.set(item.qKey, item))

  return map
})

function renderStem(question: QuizQuestionVO): string {
  return question.type === 'CLOZE' ? renderClozeStem(question.stemMd) : renderMarkdown(question.stemMd)
}

function clozeHtml(question: QuizQuestionVO): string {
  const graded = resultByKey.value.get(question.qKey)
  if (!graded) {
    return ''
  }

  return renderClozeReview(
    question.stemMd,
    (graded.userAnswer ?? '').split(' | '),
    graded.blankResults ?? [],
  )
}

/**
 * 挖空题的输入框是渲染进 HTML 字符串的，无法用 v-model 绑定，
 * 所以在容器上做事件委托：输入事件会冒泡到容器，在这里写回响应式状态。
 *
 * 如果改成在 computed 里 querySelectorAll 直接读 DOM，会踩一个隐蔽的坑：
 * computed 没有响应式依赖，只算一次就被永久缓存，计数永远停在初始值。
 */
function handleClozeInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const order = Number(input.dataset.blank ?? '0')
  if (order <= 0) {
    return
  }

  const qKey = input.closest<HTMLElement>('[data-cloze]')?.dataset.cloze
  if (!qKey) {
    return
  }

  const current = [...(clozeValues.value[qKey] ?? [])]
  current[order - 1] = input.value

  clozeValues.value = { ...clozeValues.value, [qKey]: current }
}

function collectClozeAnswers(): Map<string, string[]> {
  const answers = new Map<string, string[]>()

  for (const question of questions.value) {
    if (question.type !== 'CLOZE') {
      continue
    }

    answers.set(
      question.qKey,
      (clozeValues.value[question.qKey] ?? []).map((value) => (value ?? '').trim()),
    )
  }

  return answers
}

async function start(): Promise<void> {
  drawing.value = true
  try {
    const drawn = await drawQuestions({
      moduleSlug: setup.value.moduleSlug,
      type: setup.value.type,
      count: setup.value.count,
    })

    if (drawn.length === 0) {
      ElMessage.warning('这个筛选条件下没有题目，换个范围试试')
      return
    }

    questions.value = drawn
    choices.value = {}
    clozeValues.value = {}
    result.value = null
    startedAt.value = Date.now()
    stage.value = 'answering'

    await nextTick()
    focusFirstBlank()
  } finally {
    drawing.value = false
  }
}

function focusFirstBlank(): void {
  clueContainer.value?.querySelector<HTMLInputElement>('input[data-blank]')?.focus()
}

function selectOption(qKey: string, key: string): void {
  choices.value = { ...choices.value, [qKey]: key }
}

function toggleMultiOption(qKey: string, key: string): void {
  const current = new Set((choices.value[qKey] ?? '').split(''))
  if (current.has(key)) {
    current.delete(key)
  } else {
    current.add(key)
  }

  choices.value = { ...choices.value, [qKey]: [...current].sort().join('') }
}

function isSelected(qKey: string, key: string): boolean {
  return (choices.value[qKey] ?? '').includes(key)
}

const answeredCount = computed(() => {
  const clozeAnswers = collectClozeAnswers()

  return questions.value.filter((question) => {
    if (question.type === 'CLOZE') {
      const blanks = clozeAnswers.get(question.qKey) ?? []

      return blanks.length > 0 && blanks.every((value) => Boolean(value))
    }

    return Boolean(choices.value[question.qKey])
  }).length
})

async function submit(): Promise<void> {
  const clozeAnswers = collectClozeAnswers()
  const payload: QuizAnswerPayload[] = questions.value.map((question) => {
    if (question.type === 'CLOZE') {
      return { qKey: question.qKey, blanks: clozeAnswers.get(question.qKey) ?? [] }
    }

    return { qKey: question.qKey, answer: choices.value[question.qKey] ?? '' }
  })

  submitting.value = true
  try {
    result.value = await submitAnswers(payload, Math.round((Date.now() - startedAt.value) / 1000))
    stage.value = 'reviewing'
  } finally {
    submitting.value = false
  }
}

function restart(): void {
  questions.value = []
  choices.value = {}
  clozeValues.value = {}
  result.value = null
  stage.value = 'setup'
}

async function practiceWrong(): Promise<void> {
  setup.value.type = undefined
  setup.value.moduleSlug = undefined
  await start()
}

function isRight(qKey: string): boolean {
  return resultByKey.value.get(qKey)?.correct === true
}

function correctAnswerText(question: QuizQuestionVO): string {
  const graded = resultByKey.value.get(question.qKey)
  if (!graded) {
    return ''
  }

  if (question.type === 'CLOZE') {
    return (graded.blanks ?? []).map((accepted) => accepted.join(' / ')).join('　|　')
  }

  return graded.correctAnswer ?? ''
}

onMounted(async () => {
  modules.value = await fetchModuleTree()
  if (typeof route.query.moduleSlug === 'string') {
    setup.value.moduleSlug = route.query.moduleSlug
  }
})
</script>

<template>
  <div class="jis-page">
    <!-- 抽题设置 -->
    <section v-if="stage === 'setup'" class="jis-card setup">
      <h2 class="jis-page-title">自测</h2>
      <p class="jis-page-subtitle">
        从题库随机抽题，提交后统一判分并给出解析。多选题忽略作答顺序，
        挖空题忽略大小写与首尾空格。
      </p>

      <div class="setup__form">
        <div class="setup__field">
          <label>范围</label>
          <el-select v-model="setup.moduleSlug" clearable placeholder="全部模块" style="width: 100%">
            <el-option
              v-for="option in moduleOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>

        <div class="setup__field">
          <label>题型</label>
          <el-select v-model="setup.type" placeholder="混合题型" style="width: 100%">
            <el-option
              v-for="option in QUESTION_TYPES"
              :key="option.value ?? 'all'"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>

        <div class="setup__field">
          <label>题量</label>
          <el-slider v-model="setup.count" :min="1" :max="30" show-input :show-input-controls="false" />
        </div>
      </div>

      <div class="setup__actions">
        <el-button type="primary" size="large" :loading="drawing" @click="start">
          开始抽题
        </el-button>
        <el-button size="large" @click="router.push({ name: 'wrong-book' })">
          去错题本
        </el-button>
      </div>
    </section>

    <!-- 答题 -->
    <template v-else>
      <div class="quiz-header jis-card">
        <div>
          <h2 class="jis-page-title">
            {{ stage === 'answering' ? '答题中' : '判分结果' }}
          </h2>
          <p class="jis-page-subtitle">
            <template v-if="stage === 'answering'">
              共 {{ questions.length }} 题，已作答 {{ answeredCount }} 题
            </template>
            <template v-else-if="result">
              答对 {{ result.correctCount }} / {{ result.total }}，正确率
              <strong>{{ result.accuracy }}%</strong>
            </template>
          </p>
        </div>

        <div class="quiz-header__actions">
          <el-button v-if="stage === 'answering'" @click="restart">放弃本组</el-button>
          <el-button v-if="stage === 'reviewing'" @click="practiceWrong">再练一组</el-button>
          <el-button v-if="stage === 'reviewing'" @click="router.push({ name: 'wrong-book' })">
            看错题本
          </el-button>
        </div>
      </div>

      <div ref="clueContainer" class="questions" @input="handleClozeInput">
        <article
          v-for="(question, questionIndex) in questions"
          :key="question.qKey"
          class="question jis-card"
          :class="{
            'question--right': stage === 'reviewing' && isRight(question.qKey),
            'question--wrong': stage === 'reviewing' && !isRight(question.qKey),
          }"
        >
          <div class="question__head">
            <span class="question__index">{{ questionIndex + 1 }}</span>
            <el-tag size="small" effect="plain">{{ question.typeLabel }}</el-tag>
            <span class="question__module jis-muted">{{ question.moduleName }}</span>
            <span class="question__difficulty jis-muted">难度 {{ '★'.repeat(question.difficulty) }}</span>
            <el-icon v-if="stage === 'reviewing'" class="question__mark">
              <component :is="isRight(question.qKey) ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
            </el-icon>
          </div>

          <!-- 题干：挖空题在判分后换成静态展示 -->
          <div
            v-if="question.type !== 'CLOZE' || stage === 'answering'"
            class="question__stem md-body"
            :data-cloze="question.qKey"
            v-html="renderStem(question)"
          />
          <div v-else class="question__stem md-body" v-html="clozeHtml(question)" />

          <!-- 选择题 -->
          <div v-if="question.options" class="options">
            <button
              v-for="option in question.options"
              :key="option.key"
              class="option"
              :class="{
                'option--selected': isSelected(question.qKey, option.key),
                'option--right': stage === 'reviewing' && correctAnswerText(question).includes(option.key),
              }"
              type="button"
              :disabled="stage === 'reviewing'"
              @click="question.type === 'MULTI'
                ? toggleMultiOption(question.qKey, option.key)
                : selectOption(question.qKey, option.key)"
            >
              <span class="option__key">{{ option.key }}</span>
              <span class="option__text">{{ option.text }}</span>
            </button>
          </div>

          <!-- 判断题 -->
          <div v-else-if="question.type === 'JUDGE'" class="options options--judge">
            <button
              v-for="option in [
                { key: 'T', text: '正确' },
                { key: 'F', text: '错误' },
              ]"
              :key="option.key"
              class="option"
              :class="{
                'option--selected': isSelected(question.qKey, option.key),
                'option--right': stage === 'reviewing' && correctAnswerText(question) === option.key,
              }"
              type="button"
              :disabled="stage === 'reviewing'"
              @click="selectOption(question.qKey, option.key)"
            >
              <span class="option__key">{{ option.key }}</span>
              <span class="option__text">{{ option.text }}</span>
            </button>
          </div>

          <!-- 判分后的解析 -->
          <div v-if="stage === 'reviewing'" class="analysis">
            <div class="analysis__row">
              <span class="analysis__label">你的作答</span>
              <span class="analysis__value">
                {{ resultByKey.get(question.qKey)?.userAnswer || '（未作答）' }}
              </span>
            </div>
            <div class="analysis__row">
              <span class="analysis__label">正确答案</span>
              <span class="analysis__value analysis__value--right">{{ correctAnswerText(question) }}</span>
            </div>
            <div v-if="question.kpSlug" class="analysis__row">
              <span class="analysis__label">关联知识点</span>
              <button
                class="analysis__link"
                type="button"
                @click="router.push({ name: 'card-detail', params: { slug: question.kpSlug } })"
              >
                {{ question.kpTitle }}
              </button>
            </div>
            <div v-if="question.analysisMd" class="analysis__body">
              <span class="analysis__label">解析</span>
              <div class="md-body" v-html="renderMarkdown(question.analysisMd)" />
            </div>
          </div>
        </article>
      </div>

      <div v-if="stage === 'answering'" class="submit-bar">
        <span class="jis-muted">
          已作答 {{ answeredCount }} / {{ questions.length }} 题，未作答的题会按错误计分
        </span>
        <el-button type="primary" size="large" :loading="submitting" @click="submit">
          提交判分
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.setup__form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 20px;
  margin: 22px 0;
}

.setup__field label {
  display: block;
  margin-bottom: 7px;
  color: var(--jis-text-secondary);
  font-size: 13px;
}

.setup__actions {
  display: flex;
  gap: 10px;
}

.quiz-header {
  position: sticky;
  top: 60px;
  z-index: 5;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.quiz-header__actions {
  display: flex;
  gap: 8px;
}

.questions {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.question {
  border-left: 3px solid var(--jis-border);
}

.question--right {
  border-left-color: #10b981;
}

.question--wrong {
  border-left-color: #ef4444;
}

.question__head {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 12px;
  font-size: 12.5px;
}

.question__index {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-size: 12px;
  font-weight: 650;
}

.question__mark {
  margin-left: auto;
  font-size: 18px;
}

.question--right .question__mark {
  color: #10b981;
}

.question--wrong .question__mark {
  color: #ef4444;
}

.question__stem {
  margin-bottom: 14px;
  font-size: 14.5px;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.options--judge {
  flex-direction: row;
}

.options--judge .option {
  flex: 1;
}

.option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface);
  color: var(--jis-text);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: all 0.14s ease;
}

.option:hover:not(:disabled) {
  border-color: var(--jis-accent);
  background: var(--jis-surface-muted);
}

.option:disabled {
  cursor: default;
}

.option--selected {
  border-color: var(--jis-accent);
  background: var(--jis-accent-soft);
}

.option--right {
  border-color: #10b981;
  background: rgba(16, 185, 129, 0.09);
}

.option__key {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  border: 1px solid var(--jis-border);
  border-radius: 5px;
  font-size: 12px;
  font-weight: 650;
}

.option__text {
  flex: 1;
  line-height: 1.6;
}

.analysis {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--jis-border);
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 13.5px;
}

.analysis__row {
  display: flex;
  gap: 10px;
}

.analysis__label {
  flex-shrink: 0;
  width: 68px;
  color: var(--jis-text-muted);
  font-size: 12.5px;
}

.analysis__value--right {
  color: #10b981;
  font-weight: 620;
}

.analysis__link {
  padding: 0;
  border: none;
  background: none;
  color: var(--jis-accent);
  font-size: 13.5px;
  cursor: pointer;
}

.analysis__body {
  display: flex;
  gap: 10px;
}

.analysis__body .md-body {
  flex: 1;
  font-size: 13.5px;
}

.submit-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 18px;
  padding: 14px 18px;
  border: 1px solid var(--jis-border);
  border-radius: var(--jis-radius);
  background: color-mix(in srgb, var(--jis-surface) 92%, transparent);
  backdrop-filter: blur(8px);
  box-shadow: var(--jis-shadow);
  font-size: 13px;
}

/* 挖空题输入框由渲染字符串注入，样式必须放在 :deep 里 */
.question__stem :deep(.cloze-input) {
  display: inline-block;
  width: 108px;
  margin: 0 2px;
  padding: 1px 7px;
  border: none;
  border-bottom: 1.5px solid var(--jis-accent);
  border-radius: 3px 3px 0 0;
  background: var(--jis-accent-soft);
  color: var(--jis-text);
  font-family: inherit;
  font-size: 0.92em;
  text-align: center;
  outline: none;
}

.question__stem :deep(.cloze-input:focus) {
  background: color-mix(in srgb, var(--jis-accent) 20%, transparent);
}

.question__stem :deep(.cloze-result) {
  display: inline-block;
  margin: 0 2px;
  padding: 0 7px;
  border-radius: 4px;
  font-size: 0.92em;
  font-weight: 600;
}

.question__stem :deep(.cloze-result--right) {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
}

.question__stem :deep(.cloze-result--wrong) {
  background: rgba(239, 68, 68, 0.13);
  color: #ef4444;
}
</style>

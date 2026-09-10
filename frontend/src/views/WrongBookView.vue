<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchWrongBook } from '@/api/quiz'
import MarkdownView from '@/components/MarkdownView.vue'
import type { QuizQuestionVO } from '@/types'

const router = useRouter()

const loading = ref(true)
const questions = ref<QuizQuestionVO[]>([])

function correctAnswerText(question: QuizQuestionVO): string {
  if (question.type === 'CLOZE') {
    return (question.blanks ?? []).map((accepted) => accepted.join(' / ')).join('　|　')
  }

  return question.answer ?? ''
}

onMounted(async () => {
  try {
    questions.value = await fetchWrongBook()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="jis-page">
    <div class="header">
      <div>
        <h2 class="jis-page-title">错题本</h2>
        <p class="jis-page-subtitle">
          只收录「最近一次作答错误」的题目。答对一次就会自动移出，
          所以这份清单始终是当下真正薄弱的题。
        </p>
      </div>
      <el-button @click="router.push({ name: 'quiz' })">
        <el-icon><EditPen /></el-icon>
        去自测
      </el-button>
    </div>

    <div v-loading="loading" class="list">
      <div v-if="!loading && questions.length === 0" class="jis-card jis-empty">
        <el-icon class="empty-icon"><CircleCheckFilled /></el-icon>
        <p>错题本是空的，说明最近答的题都对了</p>
      </div>

      <article v-for="question in questions" :key="question.qKey" class="question jis-card">
        <div class="question__head">
          <el-tag size="small" effect="plain" type="danger">{{ question.typeLabel }}</el-tag>
          <span class="jis-muted">{{ question.moduleName }}</span>
          <span class="jis-muted">难度 {{ '★'.repeat(question.difficulty) }}</span>
          <button
            v-if="question.kpSlug"
            class="question__link"
            type="button"
            @click="router.push({ name: 'card-detail', params: { slug: question.kpSlug } })"
          >
            看知识点
          </button>
        </div>

        <div class="question__stem">
          <MarkdownView :content="question.stemMd" />
        </div>

        <div v-if="question.options" class="options">
          <div v-for="option in question.options" :key="option.key" class="option">
            <span class="option__key">{{ option.key }}</span>
            <span>{{ option.text }}</span>
          </div>
        </div>

        <div class="answer">
          <span class="answer__label">正确答案</span>
          <span class="answer__value">{{ correctAnswerText(question) }}</span>
        </div>

        <div v-if="question.analysisMd" class="analysis">
          <span class="answer__label">解析</span>
          <MarkdownView :content="question.analysisMd" />
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 200px;
}

.empty-icon {
  font-size: 38px;
  color: #10b981;
}

.question__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  font-size: 12.5px;
}

.question__link {
  margin-left: auto;
  padding: 0;
  border: none;
  background: none;
  color: var(--jis-accent);
  font-size: 12.5px;
  cursor: pointer;
}

.question__stem {
  margin-bottom: 12px;
  font-size: 14.5px;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}

.option {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  font-size: 13.5px;
  color: var(--jis-text-secondary);
}

.option__key {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border: 1px solid var(--jis-border);
  border-radius: 4px;
  font-size: 11.5px;
  font-weight: 650;
}

.answer,
.analysis {
  display: flex;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px dashed var(--jis-border);
  font-size: 13.5px;
}

.answer__label {
  flex-shrink: 0;
  width: 62px;
  color: var(--jis-text-muted);
  font-size: 12.5px;
}

.answer__value {
  color: #10b981;
  font-weight: 620;
}

.analysis {
  margin-top: 10px;
}

.analysis :deep(.md-body) {
  flex: 1;
  font-size: 13.5px;
}
</style>

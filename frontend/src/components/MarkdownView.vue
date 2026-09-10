<script setup lang="ts">
import { computed } from 'vue'

import { renderMarkdown } from '@/composables/useMarkdown'

const props = defineProps<{
  content: string | null | undefined
}>()

const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <!-- 内容由 markdown-it 渲染且已禁用原始 HTML，此处 v-html 是安全的 -->
  <div class="md-body" v-html="html" />
</template>

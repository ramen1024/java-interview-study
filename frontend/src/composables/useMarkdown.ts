import hljs from 'highlight.js/lib/common'
import MarkdownIt from 'markdown-it'

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function highlight(code: string, language: string): string {
  if (language && hljs.getLanguage(language)) {
    try {
      return hljs.highlight(code, { language, ignoreIllegals: true }).value
    } catch {
      // 高亮失败不应影响内容展示，降级为转义后的纯文本
    }
  }

  return escapeHtml(code)
}

/**
 * 全站共用的 Markdown 渲染器。
 *
 * html: false —— 内容来自本仓库，但仍然关掉原始 HTML 解析：
 * 少一个注入面不花任何代价，而我们的内容本来也不依赖内联 HTML。
 *
 * breaks: true —— 单换行即换行。卡片正文里有不少「一行一个要点」的写法，
 * 按严格 Markdown 会挤成一行，读写都不直观。
 */
const renderer = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight,
})

export function renderMarkdown(content: string | null | undefined): string {
  if (!content || !content.trim()) {
    return ''
  }

  return renderer.render(content)
}

/**
 * 挖空题占位符。题干里写成 {@code {{1}}}、{@code {{2}}}。
 */
export const CLOZE_PLACEHOLDER = /\{\{(\d+)}}/g

/**
 * 渲染期的占位符替身。
 *
 * <p>不能在渲染后的 HTML 上直接找 {@code {{1}}}：题干里的占位符位于代码块内部，
 * 而 highlight.js 会把 {@code {{1}}} 切成 {@code {{}} 加一个
 * {@code <span class="hljs-number">1</span>}}，占位符在 HTML 里不再连续，
 * 正则永远匹配不到，输入框就渲染不出来。
 *
 * <p>所以先在 Markdown 原文里换成一段**高亮安全的纯字母数字标记**，
 * 渲染完再把它换回输入框。首尾都用字母，避免标记与相邻标识符或数字粘连后
 * 被正则误读；也不能用下划线，那会被 Markdown 当成强调语法。
 */
const CLOZE_MARKER_PREFIX = 'JISBLANK'
const CLOZE_MARKER_SUFFIX = 'ZZ'

function tokenizeCloze(content: string | null | undefined): string {
  return (content ?? '').replace(
    CLOZE_PLACEHOLDER,
    (_match, index: string) => `${CLOZE_MARKER_PREFIX}${index}${CLOZE_MARKER_SUFFIX}`,
  )
}

function replaceClozeMarkers(
  html: string,
  build: (order: number) => string,
): string {
  return html.replace(
    new RegExp(`${CLOZE_MARKER_PREFIX}(\\d+)${CLOZE_MARKER_SUFFIX}`, 'g'),
    (_match, index: string) => build(Number(index)),
  )
}

/**
 * 把挖空题题干渲染成「Markdown + 输入框」的 HTML。
 */
export function renderClozeStem(content: string | null | undefined): string {
  return replaceClozeMarkers(renderMarkdown(tokenizeCloze(content)), (order) => {
    return (
      `<input class="cloze-input" type="text" data-blank="${order}" ` +
      `autocomplete="off" spellcheck="false" placeholder="${order}" />`
    )
  })
}

/**
 * 判分后把输入框换成「你的答案 + 对错」的静态展示。
 *
 * @param answers  用户各空的作答
 * @param results  各空对错，与 answers 一一对应
 */
export function renderClozeReview(
  content: string | null | undefined,
  answers: string[],
  results: boolean[],
): string {
  return replaceClozeMarkers(renderMarkdown(tokenizeCloze(content)), (order) => {
    const answer = answers[order - 1] ?? ''
    const correct = results[order - 1] === true

    return (
      `<span class="cloze-result ${correct ? 'cloze-result--right' : 'cloze-result--wrong'}">` +
      `${correct ? '✓' : '✗'} ${escapeHtml(answer) || '（未作答）'}` +
      `</span>`
    )
  })
}

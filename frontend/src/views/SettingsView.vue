<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref } from 'vue'

import { reimportContent } from '@/api/content'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import type { ImportResultVO } from '@/types'

const auth = useAuthStore()
const ui = useUiStore()

const importing = ref(false)
const importResult = ref<ImportResultVO | null>(null)

const userInitial = computed(() => auth.displayName.charAt(0).toUpperCase() || '?')

async function runImport(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将从仓库的 content/ 目录重新解析并导入所有 Markdown 卡片。导入是幂等的，且不会影响任何学习进度。',
      '重新导入内容',
      { confirmButtonText: '开始导入', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }

  importing.value = true
  try {
    importResult.value = await reimportContent()

    const result = importResult.value
    if (result.errors.length > 0) {
      ElMessage.warning(`导入完成，但有 ${result.errors.length} 个文件被跳过`)
    } else {
      ElMessage.success('导入完成，所有文件都已生效')
    }
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="jis-page">
    <div class="jis-page-header">
      <h2 class="jis-page-title">设置</h2>
      <p class="jis-page-subtitle">账号信息、外观偏好与内容管理</p>
    </div>

    <div class="sections">
      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><User /></el-icon>
          账号
        </h3>
        <div class="account">
          <span class="account__avatar">{{ userInitial }}</span>
          <div>
            <div class="account__name">{{ auth.displayName }}</div>
            <div class="account__meta jis-muted">
              @{{ auth.user?.username }} · 注册于
              {{ auth.user?.createTime?.slice(0, 10) ?? '—' }}
            </div>
          </div>
        </div>
      </section>

      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><Brush /></el-icon>
          外观
        </h3>
        <div class="row">
          <div>
            <div class="row__label">深色模式</div>
            <div class="row__hint jis-muted">首次访问跟随系统，之后以你的选择为准</div>
          </div>
          <el-switch
            :model-value="ui.theme === 'dark'"
            @update:model-value="ui.toggleTheme()"
          />
        </div>
      </section>

      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><FolderOpened /></el-icon>
          内容管理
        </h3>
        <p class="row__hint jis-muted section-text">
          知识点以 Markdown 存放在仓库的 <code>content/</code> 目录，
          由导入器解析后写入数据库。改完 Markdown 后在这里点一下即可生效，
          不需要重启后端。导入只写内容表，绝不会影响复习进度、笔记与收藏。
        </p>

        <el-button type="primary" :loading="importing" @click="runImport">
          <el-icon><Refresh /></el-icon>
          重新导入内容
        </el-button>

        <div v-if="importResult" class="import-result">
          <div class="import-result__row">
            模块 {{ importResult.moduleCount }} · 卡片 {{ importResult.cardCount }} ·
            追问 {{ importResult.followUpCount }} · 题目 {{ importResult.questionCount }} ·
            关联 {{ importResult.relationCount }} · 耗时 {{ importResult.elapsedMs }} ms
          </div>

          <el-alert
            v-if="importResult.errors.length > 0"
            type="error"
            :closable="false"
            title="以下文件被跳过，请修正后重新导入"
            class="import-result__alert"
          >
            <ul class="import-result__list">
              <li v-for="(error, index) in importResult.errors" :key="index">{{ error }}</li>
            </ul>
          </el-alert>

          <el-alert
            v-if="importResult.warnings.length > 0"
            type="warning"
            :closable="false"
            title="导入提示"
            class="import-result__alert"
          >
            <ul class="import-result__list">
              <li v-for="(warning, index) in importResult.warnings" :key="index">
                {{ warning.split('\n')[0] }}
              </li>
            </ul>
          </el-alert>
        </div>
      </section>

      <section class="jis-card">
        <h3 class="jis-section-title">
          <el-icon><InfoFilled /></el-icon>
          关于
        </h3>
        <p class="row__hint jis-muted section-text">
          这个站点本身就是一个 Java 后端项目：Spring Boot 3 + MyBatis-Plus + MySQL + Redis，
          前端 Vue 3 + Element Plus。复习调度用的是官方 FSRS-6 模型，
          中文搜索走 MySQL ngram 全文索引。
          完整的接口文档在
          <a href="/swagger-ui.html" target="_blank" rel="noopener">Swagger UI</a>
          （需后端运行在 8081 端口）。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.sections {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.account {
  display: flex;
  align-items: center;
  gap: 14px;
}

.account__avatar {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--jis-accent-soft);
  color: var(--jis-accent-strong);
  font-size: 19px;
  font-weight: 680;
}

.account__name {
  font-size: 15px;
  font-weight: 640;
}

.account__meta {
  font-size: 12.5px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.row__label {
  font-size: 14px;
  font-weight: 600;
}

.row__hint {
  font-size: 12.5px;
  margin-top: 2px;
}

.section-text {
  margin: 0 0 14px;
  line-height: 1.85;
}

code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--jis-surface-muted);
  border: 1px solid var(--jis-border);
  font-size: 12.5px;
}

.import-result {
  margin-top: 16px;
}

.import-result__row {
  padding: 10px 14px;
  border-radius: var(--jis-radius-sm);
  background: var(--jis-surface-muted);
  font-size: 13px;
  color: var(--jis-text-secondary);
}

.import-result__alert {
  margin-top: 10px;
}

.import-result__list {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.8;
}
</style>

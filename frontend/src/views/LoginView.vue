<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { register } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const mode = ref<'login' | 'register'>('login')
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  nickname: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{3,20}$/,
      message: '3~20 位字母、数字或下划线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6~32 位', trigger: 'blur' },
  ],
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    if (mode.value === 'register') {
      await register({
        username: form.username,
        password: form.password,
        nickname: form.nickname || undefined,
      })
      ElMessage.success('注册成功，正在为你登录')
    }

    await auth.login(form.username, form.password)

    const redirect = (route.query.redirect as string | undefined) ?? '/'
    await router.push(redirect)
  } catch {
    // 错误提示已由请求层统一处理
  } finally {
    submitting.value = false
  }
}

function switchMode(next: 'login' | 'register'): void {
  mode.value = next
  formRef.value?.clearValidate()
}
</script>

<template>
  <div class="login">
    <div class="login__panel">
      <div class="login__brand">
        <div class="login__logo">J</div>
        <h1 class="login__title">Java 面试学习站</h1>
        <p class="login__subtitle">
          六段式知识点卡片 · 面试官追问链 · 间隔重复复习 · 自测闭环
        </p>
      </div>

      <el-card shadow="never" class="login__card">
        <el-tabs :model-value="mode" stretch @update:model-value="switchMode($event as 'login' | 'register')">
          <el-tab-pane label="登录" name="login" />
          <el-tab-pane label="注册" name="register" />
        </el-tabs>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="3~20 位字母、数字或下划线" />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="6~32 位"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-form-item v-if="mode === 'register'" label="昵称（可选）">
            <el-input v-model="form.nickname" placeholder="不填就用用户名" />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="login__submit"
            :loading="submitting"
            @click="submit"
          >
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
        </el-form>
      </el-card>

      <button class="login__theme" type="button" @click="ui.toggleTheme()">
        <el-icon><component :is="ui.theme === 'dark' ? 'Sunny' : 'Moon'" /></el-icon>
        <span>{{ ui.theme === 'dark' ? '切换浅色' : '切换深色' }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(circle at 15% 12%, rgba(99, 102, 241, 0.13), transparent 42%),
    radial-gradient(circle at 85% 88%, rgba(168, 85, 247, 0.11), transparent 45%),
    var(--jis-bg);
}

.login__panel {
  width: 100%;
  max-width: 410px;
}

.login__brand {
  margin-bottom: 22px;
  text-align: center;
}

.login__logo {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin: 0 auto 14px;
  border-radius: 15px;
  background: linear-gradient(135deg, var(--jis-accent), #a855f7);
  color: #fff;
  font-size: 25px;
  font-weight: 700;
  box-shadow: 0 8px 22px rgba(99, 102, 241, 0.32);
}

.login__title {
  margin: 0 0 6px;
  font-size: 21px;
  font-weight: 680;
}

.login__subtitle {
  margin: 0;
  color: var(--jis-text-muted);
  font-size: 12.5px;
  line-height: 1.7;
}

.login__card {
  border-radius: var(--jis-radius);
  border-color: var(--jis-border);
}

.login__submit {
  width: 100%;
  margin-top: 4px;
}

.login__theme {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 16px auto 0;
  padding: 6px 14px;
  border: 1px solid var(--jis-border);
  border-radius: 999px;
  background: var(--jis-surface);
  color: var(--jis-text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.login__theme:hover {
  border-color: var(--jis-accent);
  color: var(--jis-accent);
}

:deep(.el-tabs__item) {
  font-size: 14px;
}
</style>

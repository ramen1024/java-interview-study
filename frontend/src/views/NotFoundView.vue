<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <div class="not-found__code">404</div>
    <p class="not-found__text">这个页面不存在，可能是链接过期了</p>
    <el-button type="primary" @click="router.push({ name: 'dashboard' })">回到仪表盘</el-button>
  </div>
</template>

<style scoped>
.not-found {
  display: grid;
  place-items: center;
  min-height: 100vh;
  gap: 8px;
  text-align: center;
}

.not-found__code {
  font-size: 64px;
  font-weight: 700;
  color: var(--jis-accent);
  letter-spacing: -2px;
}

.not-found__text {
  margin: 0 0 12px;
  color: var(--jis-text-secondary);
}
</style>

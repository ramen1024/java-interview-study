import * as ElementPlusIcons from '@element-plus/icons-vue'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from '@/App.vue'
import router from '@/router'

import 'element-plus/dist/index.css'
// 暗色模式变量：Element Plus 通过 html.dark 切换，需显式引入
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/index.css'

const app = createApp(App)

// 图标全量注册，模板里可以直接 <el-icon><Search /></el-icon>
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}

// pinia 必须先于 router 安装：路由守卫里会用到 store
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')

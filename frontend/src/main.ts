import { createApp } from 'vue'
import { ElButton, ElPagination } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/pagination/style/css'
import App from './App.vue'
import { router } from './router'

createApp(App).use(router).use(ElButton).use(ElPagination).mount('#app')

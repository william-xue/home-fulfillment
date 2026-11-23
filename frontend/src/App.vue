<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { logout, message, profile } from './api'
import './workbench.css'

const route = useRoute()
const router = useRouter()
const error = ref('')
const roleNames = { OPS: '运营', WAREHOUSE: '仓管', SUPPORT: '客服' }
watch(profile, (value) => {
  if (!value && !route.meta.public) void router.replace({ path: '/login', query: { next: route.fullPath } })
})

async function signOut() {
  error.value = ''
  try { await logout(); await router.replace('/login') }
  catch (e) { error.value = message(e) }
}
</script>

<template>
  <RouterView v-if="route.meta.public" />
  <div v-else-if="profile" class="shell">
    <aside class="rail">
      <div class="brand"><span class="brand-mark">履</span><div><strong>供应链工作台</strong><small>FULFILLMENT / LOCAL</small></div></div>
      <nav aria-label="业务导航">
        <p class="eyebrow">业务工作台</p>
        <RouterLink v-if="profile.role === 'OPS'" to="/operations">运营汇总</RouterLink>
        <RouterLink v-if="profile.role !== 'WAREHOUSE'" to="/orders">渠道订单</RouterLink>
        <RouterLink v-if="profile.role !== 'WAREHOUSE'" to="/shipments">模拟运单</RouterLink>
        <template v-if="profile.role !== 'SUPPORT'">
          <RouterLink to="/purchase-orders" :class="{ selected: route.path.startsWith('/purchase-orders') || route.path.startsWith('/receipts') }">采购单</RouterLink>
          <RouterLink to="/warehouse/outbound">出库作业</RouterLink>
          <RouterLink to="/inventory">库存台账</RouterLink>
        </template>
      </nav>
      <div class="rail-foot"><span>本机演示</span><strong>V1 / 供应链履约</strong></div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span>{{ route.meta.orders ? '订单' : '仓储' }} / {{ route.meta.title }}</span>
        <div class="account"><span>{{ profile.username }} · {{ roleNames[profile.role] }} <template v-if="profile.role === 'WAREHOUSE'">· {{ profile.warehouseCodes.join('、') }}</template></span>
          <el-button size="small" @click="signOut">退出</el-button></div>
      </header>
      <main class="content">
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <RouterView v-slot="{ Component }"><component :is="Component" :key="route.path" /></RouterView>
      </main>
    </div>
  </div>
</template>

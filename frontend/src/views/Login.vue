<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, message } from '../api'

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const prefilled = ref(false)
const route = useRoute()
const router = useRouter()
const orderEntry = /^\/(?:orders|shipments|operations)(?:[/?]|$)/

if (import.meta.env.DEV) {
  onMounted(async () => {
    try {
      const response = await fetch('/__local-demo-login', { mode: 'same-origin', cache: 'no-store' })
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('application/json')) return
      const demo: { username: string; password: string } = await response.json()
      if (username.value || password.value || busy.value) return
      const next = route.query.next
      const actor = route.query.demo
      username.value = actor === 'warehouse-gz' || actor === 'support' ? actor
        : typeof next === 'string' && (orderEntry.test(next) || /^\/purchase-orders\/new(?:\?|$)/.test(next)) ? 'ops' : demo.username
      password.value = demo.password
      prefilled.value = true
    } catch {
      // Manual login remains available when local demo autofill is unavailable.
    }
  })
}

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const account = await login(username.value,password.value)
    password.value = ''
    const next = route.query.next
    await router.replace(typeof next === 'string' && /^\/(?:purchase-orders|receipts|inventory|orders|shipments|operations|warehouse\/outbound)(?:[/?]|$)/.test(next)
      ? next : account.role === 'WAREHOUSE' ? '/purchase-orders' : '/orders')
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
</script>

<template>
  <main class="login">
    <form class="surface login-card flow" @submit.prevent="submit">
      <p class="eyebrow">FULFILLMENT / LOCAL</p>
      <h1>供应链业务工作台</h1>
      <p class="lede">登录后按角色查看采购、履约和运输作业。</p>
      <label class="field"><span>用户名</span><input v-model.trim="username" autocomplete="username" required maxlength="64"></label>
      <label class="field"><span>密码</span><input v-model="password" type="password" autocomplete="current-password" required maxlength="72"></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <el-button native-type="submit" type="primary" :loading="busy">登录</el-button>
      <p v-if="prefilled" class="muted">已填好本机演示账号和密码，直接点「登录」即可。</p>
    </form>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { command, get, message, pendingCommand, type Entry } from '../api'
import OrderLineFields from './OrderLineFields.vue'
import { channels, type ItemInput, type Order } from './order'

const path = '/orders'
const router = useRouter()
const channel = ref<keyof typeof channels>('DIRECT')
const external = ref('')
const now = new Date()
const paidAt = ref(new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0,16))
const lines = ref<ItemInput[]>([{ skuCode: '', quantity: 1 }])
const skus = ref<Entry[]>([])
const busy = ref(false)
const pending = ref(Boolean(pendingCommand(path)))
const error = ref('')

onMounted(async () => {
  try { skus.value = await get<Entry[]>('/catalog/skus') } catch (e) { error.value = message(e) }
})
async function submit(retry = false) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await command<Order>(path,retry ? undefined : {
      channel: channel.value, channelOrderNo: external.value, paidAt: new Date(paidAt.value).toISOString(), lines: lines.value,
    })
    await router.push(`${path}/${result.orderNo}`)
  } catch (e) { error.value = message(e) }
  finally { busy.value = false; pending.value = Boolean(pendingCommand(path)) }
}
</script>

<template>
  <div class="flow">
    <RouterLink to="/orders">← 渠道订单</RouterLink>
    <h1>录入已支付订单</h1><p class="lede">本机模拟渠道已支付事实，不对接真实支付，也不自动分配库存。</p>
    <form class="surface flow" @submit.prevent="submit()">
      <div class="filters">
        <label class="field"><span>渠道</span><select v-model="channel" :disabled="busy || pending">
          <option v-for="(label, code) in channels" :key="code" :value="code">{{ label }}</option></select></label>
        <label class="field"><span>渠道单号</span><input v-model.trim="external" placeholder="SAMPLE-001" required maxlength="64" :disabled="busy || pending"></label>
        <label class="field"><span>支付时间（本机时区）</span><input v-model="paidAt" type="datetime-local" required :disabled="busy || pending"></label>
      </div>
      <OrderLineFields v-model="lines" :skus="skus" :locked="busy || pending" />
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div>
        <el-button v-if="pending" :loading="busy" @click="submit(true)">重试原请求</el-button>
        <el-button v-else type="primary" native-type="submit" :loading="busy">确认接单</el-button>
      </div>
    </form>
  </div>
</template>

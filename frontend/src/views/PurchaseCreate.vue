<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { command, get, message, pendingCommand, type Entry } from '../api'
import OrderLineFields from './OrderLineFields.vue'
import type { ItemInput } from './order'

const path = '/purchase-orders'
const router = useRouter()
const warehouses = ref<Entry[]>([])
const skus = ref<Entry[]>([])
const warehouseCode = ref('')
const supplierName = ref('')
const lines = ref<ItemInput[]>([{ skuCode: '', quantity: 1 }])
const busy = ref(false)
const pending = ref(Boolean(pendingCommand(path)))
const error = ref('')

onMounted(async () => {
  try {
    [warehouses.value,skus.value] = await Promise.all([get<Entry[]>('/catalog/warehouses'),get<Entry[]>('/catalog/skus')])
  } catch (e) { error.value = message(e) }
})
async function submit(retry = false) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await command<{ poNo: string }>(path,retry ? undefined : {
      warehouseCode: warehouseCode.value, supplierName: supplierName.value, lines: lines.value,
    })
    await router.push(`${path}/${result.poNo}`)
  } catch (e) { error.value = message(e) }
  finally { busy.value = false; pending.value = Boolean(pendingCommand(path)) }
}
</script>

<template>
  <div class="flow">
    <RouterLink to="/purchase-orders">← 采购单列表</RouterLink>
    <h1>新建采购单</h1><p class="lede">新单只记录计划采购量；仓管收货和上架后才形成可分配库存。</p>
    <form class="surface flow" @submit.prevent="submit()">
      <div class="filters">
        <label class="field"><span>收货仓</span><select v-model="warehouseCode" required :disabled="busy || pending">
          <option value="" disabled>请选择仓库</option>
          <option v-for="w in warehouses" :key="w.code" :value="w.code">{{ w.name }}</option>
        </select></label>
        <label class="field"><span>供应商</span><input v-model.trim="supplierName" required maxlength="120" :disabled="busy || pending"></label>
      </div>
      <OrderLineFields v-model="lines" :skus="skus" :locked="busy || pending" />
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div>
        <el-button v-if="pending" :loading="busy" @click="submit(true)">重试原请求</el-button>
        <el-button v-else type="primary" native-type="submit" :loading="busy">创建采购单</el-button>
      </div>
    </form>
  </div>
</template>

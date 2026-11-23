<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { command, get, message, pendingCommand, type Bin } from '../api'
import { poStatus, type Line, type PurchaseDetail, type ReceiptDetail } from './inbound'

const route = useRoute()
const router = useRouter()
const path = `/purchase-orders/${encodeURIComponent(String(route.params.poNo))}`
const commandPath = `${path}/receipts`
const po = ref<PurchaseDetail>()
const bins = ref<Bin[]>([])
const line = ref<Line>()
const binCode = ref('')
const quantity = ref(1)
const busy = ref(false)
const error = ref('')
const pending = ref(Boolean(pendingCommand(commandPath)))

onMounted(async () => {
  try {
    const detail = await get<PurchaseDetail>(path)
    po.value = detail
    if (detail.allowedActions.includes('RECEIVE')) {
      bins.value = (await get<Bin[]>(`/catalog/warehouses/${detail.warehouseCode}/bins`)).filter(b => b.type === 'RECEIVING')
      binCode.value = bins.value[0]?.code || ''
    }
  } catch (e) { error.value = message(e) }
})

async function receive(retry = false) {
  if (busy.value || (!retry && !line.value)) return
  busy.value = true
  error.value = ''
  try {
    const result = await command<ReceiptDetail>(commandPath,
      retry ? undefined : { lineNo: line.value!.lineNo, binCode: binCode.value, quantity: quantity.value })
    await router.push(`/receipts/${result.receiptNo}`)
  } catch (e) { error.value = message(e) }
  finally { busy.value = false; pending.value = Boolean(pendingCommand(commandPath)) }
}
</script>

<template>
  <div class="flow">
    <RouterLink to="/purchase-orders">← 采购单列表</RouterLink>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <el-button v-if="pending" :loading="busy" @click="receive(true)">重试原请求</el-button>
    <template v-if="po">
      <div class="section-head"><h1>{{ po.poNo }}</h1><span class="status-chip">{{ poStatus[po.status] ?? po.status }}</span></div>
      <p class="lede">{{ po.warehouseCode }} · {{ po.supplierName }}</p>
      <section class="surface">
        <div class="section-head"><h2>采购明细</h2><span class="muted">余欠 = 订购 − 已收</span></div>
        <div class="table-wrap"><table><thead><tr><th>行</th><th>SKU</th><th>订购</th><th>已收</th><th>余欠</th><th>作业</th></tr></thead>
          <tbody><tr v-for="row in po.lines" :key="row.lineNo"><td>{{ row.lineNo }}</td><td>{{ row.skuCode }}</td>
            <td>{{ row.orderedQty }}</td><td>{{ row.receivedQty }}</td><td>{{ row.remainingQty }}</td>
            <td><el-button v-if="po.allowedActions.includes('RECEIVE') && row.remainingQty > 0" :disabled="busy || pending"
              @click="line = row; quantity = 1">登记收货</el-button><span v-else class="muted">—</span></td></tr></tbody></table></div>
      </section>
      <section v-if="line" class="surface flow">
        <h2>登记收货 · {{ line.skuCode }}</h2>
        <form class="filters" @submit.prevent="receive()">
          <label class="field"><span>暂存位</span><select v-model="binCode" required><option v-for="bin in bins" :key="bin.code" :value="bin.code">{{ bin.code }}</option></select></label>
          <label class="field"><span>本次收货量</span><input v-model.number="quantity" type="number" required min="1" max="1000000" step="1"></label>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="pending">确认收货</el-button>
        </form>
      </section>
      <section class="surface flow">
        <h2>收货历史</h2>
        <p v-if="!po.receiptNos.length" class="empty">尚未登记收货。</p>
        <RouterLink v-for="no in po.receiptNos" :key="no" :to="`/receipts/${no}`" class="mono">{{ no }}</RouterLink>
      </section>
    </template>
  </div>
</template>

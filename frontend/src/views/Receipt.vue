<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { command, get, message, pendingCommand, type Bin } from '../api'
import type { ReceiptDetail, PutAwayFact } from './inbound'

const route = useRoute()
const path = `/receipts/${encodeURIComponent(String(route.params.receiptNo))}`
const commandPath = `${path}/put-aways`
const receipt = ref<ReceiptDetail>()
const bins = ref<Bin[]>([])
const destinationBinCode = ref('')
const quantity = ref(1)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const pending = ref(Boolean(pendingCommand(commandPath)))

async function load() {
  receipt.value = await get<ReceiptDetail>(path)
  if (receipt.value.allowedActions.includes('PUT_AWAY') && !bins.value.length) {
    bins.value = (await get<Bin[]>(`/catalog/warehouses/${receipt.value.warehouseCode}/bins`)).filter(b => b.type === 'PICKABLE')
    destinationBinCode.value = bins.value[0]?.code || ''
  }
}

onMounted(async () => { try { await load() } catch (e) { error.value = message(e) } })

async function putAway(retry = false) {
  if (busy.value || !receipt.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await command<PutAwayFact>(commandPath, retry ? undefined : {
      destinationBinCode: destinationBinCode.value, quantity: quantity.value,
    })
    notice.value = `已上架 ${result.quantity} 件 · ${result.putAwayNo}`
    await load()
  } catch (e) { error.value = message(e) }
  finally { busy.value = false; pending.value = Boolean(pendingCommand(commandPath)) }
}
</script>

<template>
  <div class="flow">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <el-button v-if="pending" :loading="busy" @click="putAway(true)">重试原请求</el-button>
    <template v-if="receipt">
      <RouterLink :to="`/purchase-orders/${receipt.poNo}`">← {{ receipt.poNo }}</RouterLink>
      <div><p class="eyebrow">收货单</p><h1>{{ receipt.receiptNo }}</h1></div>
      <p class="lede">{{ receipt.warehouseCode }} · {{ receipt.skuCode }} · 暂存位 {{ receipt.binCode }}</p>
      <section class="surface">
        <div class="section-head"><h2>入库进度</h2><RouterLink :to="{path:'/inventory',query:{bizNo:receipt.receiptNo}}">查收货流水</RouterLink></div>
        <table><thead><tr><th>实际收货</th><th>已上架</th><th>待上架</th></tr></thead>
          <tbody><tr><td>{{ receipt.quantity }}</td><td>{{ receipt.putAwayQty }}</td><td>{{ receipt.remainingQty }}</td></tr></tbody></table>
      </section>
      <p v-if="notice" class="success" role="status">{{ notice }}</p>
      <section v-if="receipt.allowedActions.includes('PUT_AWAY')" class="surface flow">
        <h2>从暂存位上架</h2>
        <form class="filters" @submit.prevent="putAway()">
          <label class="field"><span>目标可拣位</span><select v-model="destinationBinCode" required><option v-for="bin in bins" :key="bin.code" :value="bin.code">{{ bin.code }}</option></select></label>
          <label class="field"><span>本次上架量</span><input v-model.number="quantity" type="number" required min="1" max="1000000" step="1"></label>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="pending">确认上架</el-button>
        </form>
      </section>
      <section class="surface">
        <div class="section-head"><h2>上架记录</h2></div>
        <div class="table-wrap"><table><thead><tr><th>上架单号</th><th>目标可拣位</th><th>本次数量</th><th>关联流水</th></tr></thead>
          <tbody><tr v-for="fact in receipt.putAways" :key="fact.putAwayNo"><td class="mono">{{ fact.putAwayNo }}</td>
            <td>{{ fact.destinationBinCode }}</td><td>{{ fact.quantity }}</td>
            <td><RouterLink :to="{path:'/inventory',query:{bizNo:fact.putAwayNo}}">查双边流水</RouterLink></td>
          </tr></tbody></table></div>
        <p v-if="!receipt.putAways.length" class="empty">尚未上架。收货完成不代表已经可拣。</p>
      </section>
    </template>
  </div>
</template>

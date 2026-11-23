<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { get, message, type Entry, type Page } from '../api'

interface Balance { warehouseCode: string; skuCode: string; binCode: string; binType: string; onHand: number; reserved: number; available: number }
interface Movement { warehouseCode: string; skuCode: string; binCode: string; movementType: string; bizNo: string;
  deltaOnHand: number; deltaReserved: number; onHandAfter: number; reservedAfter: number; occurredAt: string }
const types: Record<string,string> = {
  RECEIVE: '收货入暂存', PUT_AWAY_OUT: '上架 · 源位移出', PUT_AWAY_IN: '上架 · 目标位移入',
  RESERVE: '订单预占', RELEASE: '取消 · 释放预占',
  DISPATCH: '确认出库',
}
const typeOf = (type: string) => types[type] ?? type
const signed = (qty: number) => qty > 0 ? `+${qty}` : String(qty)
const route = useRoute()
const router = useRouter()
const filters = reactive({ warehouseCode: '', skuCode: '', binCode: '', bizNo: String(route.query.bizNo ?? '') })
const applied = reactive({ ...filters })
const warehouses = ref<Entry[]>([])
const skus = ref<Entry[]>([])
const balances = ref<Page<Balance>>()
const movements = ref<Page<Movement>>()
const error = ref('')
const busy = ref(false)

async function refresh(balancePage = 1, movementPage = 1) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const { bizNo, ...scope } = applied
  try {
    const [balanceResult, movementResult] = await Promise.all([
      get<Page<Balance>>('/inventory/balances', { ...scope, page: balancePage }),
      get<Page<Movement>>('/inventory/movements', { ...scope, bizNo, page: movementPage }),
    ])
    balances.value = balanceResult
    movements.value = movementResult
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}

async function search() {
  Object.assign(applied,filters)
  if (filters.bizNo !== String(route.query.bizNo ?? '')) {
    await router.replace({ query: filters.bizNo ? { bizNo: filters.bizNo } : {} })
  } else await refresh()
}

watch(() => route.query.bizNo, biz => {
  filters.bizNo = String(biz ?? '')
  Object.assign(applied,filters)
  void refresh()
})

onMounted(async () => {
  try {
    [warehouses.value,skus.value] = await Promise.all([get<Entry[]>('/catalog/warehouses'),get<Entry[]>('/catalog/skus')])
    await refresh()
  } catch (e) { error.value = message(e) }
})
</script>

<template>
  <div class="flow">
    <h1>库存台账</h1><p class="lede">余额回答「现在还有多少」，流水回答「哪张单据改了库存」。暂存位不参与可分配。</p>
    <form class="surface filters" @submit.prevent="search">
      <label class="field"><span>仓库</span><select v-model="filters.warehouseCode"><option value="">全部授权仓</option><option v-for="w in warehouses" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label class="field"><span>商品</span><select v-model="filters.skuCode"><option value="">全部商品</option><option v-for="s in skus" :key="s.code" :value="s.code">{{ s.code }}</option></select></label>
      <label class="field"><span>库位</span><input v-model.trim="filters.binCode" placeholder="例如 RECV-01"></label>
      <label class="field"><span>流水业务号</span><input v-model.trim="filters.bizNo" placeholder="RC-… 或 PA-…"></label>
      <el-button native-type="submit" type="primary" :loading="busy">查询</el-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="surface">
      <div class="section-head"><h2>库位余额</h2><span class="muted">账面现存，含待出库</span></div>
      <div class="table-wrap"><table><thead><tr><th>仓</th><th>SKU</th><th>库位</th><th>现存</th><th>预占</th><th>可分配</th></tr></thead>
        <tbody><tr v-for="b in balances?.items" :key="`${b.warehouseCode}:${b.skuCode}:${b.binCode}`">
          <td>{{ b.warehouseCode }}</td><td>{{ b.skuCode }}</td><td>{{ b.binCode }} <span class="muted">· {{ b.binType === 'RECEIVING' ? '暂存' : '可拣' }}</span></td>
          <td>{{ b.onHand }}</td><td>{{ b.reserved }}</td><td>{{ b.available }}</td></tr></tbody></table></div>
      <p v-if="!balances?.items.length" class="empty">暂无余额记录。首笔收货后生成。</p>
      <el-pagination v-if="balances && balances.total > balances.size" class="pager" layout="prev, pager, next"
        :current-page="balances.page" :page-size="balances.size" :total="balances.total" @current-change="(page: number) => refresh(page,movements?.page)" />
    </section>
    <section class="surface">
      <div class="section-head"><h2>库存流水</h2><span v-if="applied.bizNo" class="muted mono">{{ applied.bizNo }}</span></div>
      <div class="table-wrap"><table><thead><tr><th>操作</th><th>业务号</th><th>仓 / 库位</th><th>现存变化</th><th>变化后现存</th><th>预占变化</th><th>时间</th></tr></thead>
        <tbody><tr v-for="m in movements?.items" :key="`${m.bizNo}:${m.binCode}:${m.movementType}`">
          <td>{{ typeOf(m.movementType) }}</td><td class="mono">{{ m.bizNo }}</td><td>{{ m.warehouseCode }} / {{ m.binCode }}</td>
          <td>{{ signed(m.deltaOnHand) }}</td><td>{{ m.onHandAfter }}</td><td>{{ signed(m.deltaReserved) }}</td>
          <td>{{ new Date(m.occurredAt).toLocaleString('zh-CN') }}</td></tr></tbody></table></div>
      <p v-if="!movements?.items.length" class="empty">没有符合条件的流水。</p>
      <el-pagination v-if="movements && movements.total > movements.size" class="pager" layout="prev, pager, next"
        :current-page="movements.page" :page-size="movements.size" :total="movements.total" @current-change="(page: number) => refresh(balances?.page,page)" />
    </section>
  </div>
</template>

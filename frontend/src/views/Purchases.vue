<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { get, message, profile, type Page, type Entry } from '../api'
import { poStatus, type PurchaseSummary } from './inbound'

const filters = reactive({ poNo: '', status: '', warehouseCode: '' })
const warehouses = ref<Entry[]>([])
const result = ref<Page<PurchaseSummary>>()
const loading = ref(false)
const error = ref('')

async function load(page = 1) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { result.value = await get<Page<PurchaseSummary>>('/purchase-orders', { ...filters, page }) }
  catch (e) { error.value = message(e) }
  finally { loading.value = false }
}

onMounted(async () => {
  try { warehouses.value = await get<Entry[]>('/catalog/warehouses'); await load() }
  catch (e) { error.value = message(e) }
})
</script>

<template>
  <div class="flow">
    <div class="section-head"><h1>采购单</h1><RouterLink v-if="profile?.role === 'OPS'" to="/purchase-orders/new">新建采购单</RouterLink></div>
    <p class="lede">从采购行查待收数量，登记实际到货，再去收货单做上架。</p>
    <form class="surface filters" @submit.prevent="load()">
      <label class="field"><span>采购单号</span><input v-model.trim="filters.poNo" placeholder="PO-HOME-001"></label>
      <label class="field"><span>仓库</span><select v-model="filters.warehouseCode"><option value="">全部授权仓</option><option v-for="w in warehouses" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label class="field"><span>收货状态</span><select v-model="filters.status"><option value="">全部</option><option v-for="(label,status) in poStatus" :key="status" :value="status">{{ label }}</option></select></label>
      <el-button native-type="submit" type="primary" :loading="loading">查询</el-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="surface">
      <div class="section-head"><h2>采购单列表</h2><span v-if="result" class="muted">共 {{ result.total }} 张</span></div>
      <div class="table-wrap">
        <table><thead><tr><th>采购单号</th><th>供应商</th><th>仓库</th><th>状态</th></tr></thead>
          <tbody><tr v-for="po in result?.items" :key="po.poNo"><td><RouterLink :to="`/purchase-orders/${po.poNo}`">{{ po.poNo }}</RouterLink></td>
            <td>{{ po.supplierName }}</td><td>{{ po.warehouseCode }}</td><td><span class="status-chip">{{ poStatus[po.status] ?? po.status }}</span></td></tr></tbody>
        </table>
      </div>
      <p v-if="!result?.items.length" class="empty">{{ loading ? '正在查询…' : '没有符合条件的采购单。' }}</p>
      <el-pagination v-if="result && result.total > result.size" class="pager" layout="prev, pager, next"
        :current-page="result.page" :page-size="result.size" :total="result.total" @current-change="load" />
    </section>
  </div>
</template>

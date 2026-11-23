<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { get, message, profile, type Page } from '../api'
import { deliveryLabels, type ShipmentSummary } from './order'

const filters=reactive({warehouseCode:'',status:'',fulfillmentNo:'',shipmentNo:''})
const applied={...filters}
const result=ref<Page<ShipmentSummary>>()
const busy=ref(false)
const error=ref('')
async function load(page=1) {
  if (busy.value) return
  busy.value=true
  error.value=''
  try { result.value=await get<Page<ShipmentSummary>>('/shipments',{...applied,page}) }
  catch(e) { error.value=message(e) }
  finally { busy.value=false }
}
function search() { Object.assign(applied,filters); return load() }
onMounted(()=>load())
</script>

<template>
  <div class="flow">
    <div class="section-head"><h1>模拟运单</h1>
      <RouterLink v-if="profile?.role==='OPS'" :to="{path:'/warehouse/outbound',query:{status:'DISPATCHED'}}">查看已出库待建单</RouterLink></div>
    <p class="lede">运输记录不修改库存。仅本机模拟，不对接承运商。</p>
    <form class="surface filters" @submit.prevent="search">
      <label class="field"><span>仓库</span><input v-model.trim="filters.warehouseCode" placeholder="SH 或 GZ"></label>
      <label class="field"><span>状态</span><select v-model="filters.status"><option value="">全部</option>
        <option v-for="s in ['CREATED','IN_TRANSIT','DELIVERED']" :key="s" :value="s">{{ deliveryLabels[s] }}</option></select></label>
      <label class="field"><span>履约单号</span><input v-model.trim="filters.fulfillmentNo" placeholder="FO-…"></label>
      <label class="field"><span>运单号</span><input v-model.trim="filters.shipmentNo" placeholder="TS-…"></label>
      <el-button native-type="submit" type="primary" :loading="busy">查询</el-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="surface">
      <div class="section-head"><h2>运单列表</h2><span v-if="result" class="muted">共 {{ result.total }} 张</span></div>
      <div class="table-wrap"><table>
        <thead><tr><th>运单号</th><th>关联履约单</th><th>仓库</th><th>承运商</th><th>状态</th></tr></thead>
        <tbody><tr v-for="row in result?.items" :key="row.shipmentNo">
          <td><RouterLink class="mono" :to="`/shipments/${row.shipmentNo}`">{{ row.shipmentNo }}</RouterLink></td>
          <td class="mono">{{ row.fulfillmentNo }}</td><td>{{ row.warehouseCode }}</td><td>{{ row.carrier }}</td>
          <td><span class="status-chip">{{ deliveryLabels[row.status] }}</span></td>
        </tr></tbody>
      </table></div>
      <p v-if="!result?.items.length" class="empty">{{ busy?'正在查询…':'无匹配运单。履约单出库后，运营可创建模拟运单。' }}</p>
      <el-pagination v-if="result && result.total>result.size" class="pager" layout="prev, pager, next"
        :current-page="result.page" :page-size="result.size" :total="result.total" @current-change="load" />
    </section>
  </div>
</template>

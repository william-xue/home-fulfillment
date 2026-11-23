<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { get, message, profile, type Page } from '../api'
import { fulfillmentLabels, type FulfillmentSummary } from './order'

const route=useRoute()
const filters=reactive({warehouseCode:'',status:String(route.query.status??''),orderNo:''})
const applied={...filters}
const options=['READY_TO_PICK','PICKING','PICKED','DISPATCHED','CANCELLED']
const label=(state:string)=>state==='DISPATCHED'?'已出库':fulfillmentLabels[state]
const result=ref<Page<FulfillmentSummary>>()
const busy=ref(false)
const error=ref('')
async function load(page=1) {
  if (busy.value) return
  busy.value=true
  error.value=''
  try { result.value=await get<Page<FulfillmentSummary>>('/fulfillments',{...applied,page}) }
  catch(e) { error.value=message(e) }
  finally { busy.value=false }
}
function search() { Object.assign(applied,filters); return load() }
onMounted(()=>load())
</script>

<template>
  <div class="flow">
    <h1>出库作业</h1><p class="lede">按履约单拣货和确认出库。拣货不扣库存；全部拣完才允许出库。</p>
    <form class="surface filters" @submit.prevent="search">
      <label class="field"><span>仓库</span><select v-model="filters.warehouseCode"><option value="">全部授权仓</option>
        <option v-for="w in profile?.warehouseCodes" :key="w" :value="w">{{ w }}</option></select></label>
      <label class="field"><span>作业状态</span><select v-model="filters.status"><option value="">全部</option>
        <option v-for="s in options" :key="s" :value="s">{{ label(s) }}</option></select></label>
      <label class="field"><span>订单号</span><input v-model.trim="filters.orderNo" placeholder="CO-…"></label>
      <el-button native-type="submit" type="primary" :loading="busy">查询</el-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="surface">
      <div class="section-head"><h2>履约单</h2><span v-if="result" class="muted">共 {{ result.total }} 张</span></div>
      <div class="table-wrap"><table>
        <thead><tr><th>履约单号</th><th>关联订单</th><th>仓库</th><th>作业状态</th></tr></thead>
        <tbody><tr v-for="row in result?.items" :key="row.fulfillmentNo">
          <td><RouterLink class="mono" :to="`/warehouse/outbound/${row.fulfillmentNo}`">{{ row.fulfillmentNo }}</RouterLink></td>
          <td class="mono">{{ row.orderNo }}</td><td>{{ row.warehouseCode }}</td><td><span class="status-chip">{{ label(row.status) }}</span></td>
        </tr></tbody>
      </table></div>
      <p v-if="!result?.items.length" class="empty">{{ busy?'正在查询…':'无匹配履约单。订单路由成功后，本仓作业会显示在这里。' }}</p>
      <el-pagination v-if="result && result.total>result.size" class="pager" layout="prev, pager, next"
        :current-page="result.page" :page-size="result.size" :total="result.total" @current-change="load" />
    </section>
  </div>
</template>

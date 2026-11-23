<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { command, get, message, pendingCommand, type Page } from '../api'

interface Summary { shortageCount: number; unshippedFulfillmentCount: number; failedEventCount: number; asOf: string }
interface EventRow {
  eventId: string; eventType: string; dispatchNo: string; fulfillmentNo: string; status: string
  attempt: number; lastError: string | null; nextAttemptAt: string; occurredAt: string
}
const labels: Record<string,string>={PENDING:'待投递',PUBLISHING:'投递中',PUBLISHED:'Broker 已确认',FAILED:'投递失败'}
const filters=reactive({ status:'',dispatchNo:'' })
const applied={...filters}
const summary=ref<Summary>()
const result=ref<Page<EventRow>>()
const busy=ref(false)
const retryPending=ref<string[]>([])
const error=ref('')
const retryPath=(id:string)=>`/operations/outbox/${id}/retry`

function pendingRows() {
  retryPending.value=(result.value?.items??[]).filter(row=>pendingCommand(retryPath(row.eventId))).map(row=>row.eventId)
}
async function refresh(page=1) {
  const [stats,events]=await Promise.all([
    get<Summary>('/operations/summary'),get<Page<EventRow>>('/operations/outbox',{...applied,page}),
  ])
  summary.value=stats; result.value=events; pendingRows()
}
async function load(page=1) {
  if (busy.value) return
  busy.value=true; error.value=''
  try { await refresh(page) }
  catch(e) { error.value=message(e) }
  finally { busy.value=false }
}
function search() { Object.assign(applied,filters); return load() }
onMounted(()=>load())
async function retry(row:EventRow) {
  if (busy.value) return
  busy.value=true; error.value=''
  try {
    await command<EventRow>(retryPath(row.eventId),retryPending.value.includes(row.eventId)?undefined:null)
    await refresh(result.value?.page)
  } catch(e) { error.value=message(e) }
  finally { busy.value=false; pendingRows() }
}
</script>

<template>
  <div class="flow">
    <h1>运营汇总</h1><p class="lede">观察缺货、未出库作业和出库事件。Broker 已确认不等于运单已建。</p>
    <section class="surface flow" aria-label="汇总">
      <div class="section-head"><h2>业务与投递汇总</h2><el-button :loading="busy" @click="load(result?.page)">刷新</el-button></div>
      <template v-if="summary">
        <div class="table-wrap"><table>
          <thead><tr><th>缺货订单</th><th>未出库履约单</th><th>发布失败事件</th></tr></thead>
          <tbody><tr><td>{{ summary.shortageCount }}</td><td>{{ summary.unshippedFulfillmentCount }}</td><td>{{ summary.failedEventCount }}</td></tr></tbody>
        </table></div>
        <p class="muted">统计时间：{{ new Date(summary.asOf).toLocaleString('zh-CN') }} · 汇总最多缓存 30 秒。</p>
      </template>
    </section>
    <form class="surface filters" @submit.prevent="search">
      <label class="field"><span>投递状态</span><select v-model="filters.status"><option value="">全部</option>
        <option v-for="(label,code) in labels" :key="code" :value="code">{{ label }}</option></select></label>
      <label class="field"><span>出库号</span><input v-model.trim="filters.dispatchNo" placeholder="DS-…"></label>
      <el-button native-type="submit" type="primary" :loading="busy">查询</el-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="surface flow">
      <div class="section-head"><h2>出库事件</h2><span v-if="result" class="muted">共 {{ result.total }} 条</span></div>
      <p class="muted">这里列出发布状态；是否已生成运单，请点履约详情查看。</p>
      <div class="table-wrap"><table>
        <thead><tr><th>事件 / 出库号</th><th>履约单</th><th>状态</th><th>本轮尝试</th><th>下次投递 / 错误</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in result?.items" :key="row.eventId">
          <td><p>{{ row.eventType }}</p><p class="mono">{{ row.eventId }}</p><p class="mono">{{ row.dispatchNo }}</p></td>
          <td><RouterLink class="mono" :to="`/warehouse/outbound/${row.fulfillmentNo}`">{{ row.fulfillmentNo }}</RouterLink></td>
          <td><span class="status-chip">{{ labels[row.status] }}</span></td><td>{{ row.attempt }}</td>
          <td><p v-if="row.status==='PENDING'">{{ new Date(row.nextAttemptAt).toLocaleString('zh-CN') }}</p>
            <p v-if="row.lastError" class="muted">{{ row.lastError }}</p></td>
          <td><el-button v-if="row.status==='FAILED' || retryPending.includes(row.eventId)" :disabled="busy" @click="retry(row)">
            {{ retryPending.includes(row.eventId)?'重试原请求':'重新投递' }}
          </el-button><span v-else class="muted">—</span></td>
        </tr></tbody>
      </table></div>
      <p v-if="!result?.items.length" class="empty">无匹配事件。V5 升级前的出库不补发。</p>
      <el-pagination v-if="result && result.total>result.size" class="pager" layout="prev, pager, next"
        :current-page="result.page" :page-size="result.size" :total="result.total" @current-change="load" />
    </section>
  </div>
</template>

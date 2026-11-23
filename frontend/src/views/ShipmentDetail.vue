<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { command, get, message, pendingCommand, profile } from '../api'
import { deliveryLabels, type ShipmentDetail } from './order'

const route=useRoute()
const path=`/shipments/${encodeURIComponent(String(route.params.shipmentNo))}`
const eventPath=`${path}/events`
const detail=ref<ShipmentDetail>()
const kind=ref('IN_TRANSIT')
const nextSource=()=>`EV-${crypto.randomUUID()}`
const source=ref(nextSource())
const occurred=ref('')
const pending=ref(Boolean(pendingCommand(eventPath)))
const busy=ref(false)
const error=ref('')

async function refresh() { detail.value=await get<ShipmentDetail>(path) }
onMounted(async()=>{ try { await refresh() } catch(e) { error.value=message(e) } })
async function register(retry=false) {
  if (busy.value) return
  busy.value=true
  error.value=''
  try {
    await command<ShipmentDetail>(eventPath,retry ? undefined : {
      type:kind.value,sourceEventNo:source.value,occurredAt:(occurred.value ? new Date(occurred.value) : new Date()).toISOString(),
    })
    source.value=nextSource()
    occurred.value=''
    await refresh()
  } catch(e) { error.value=message(e) }
  finally { busy.value=false; pending.value=Boolean(pendingCommand(eventPath)) }
}
</script>

<template>
  <div class="flow">
    <RouterLink to="/shipments">← 模拟运单</RouterLink>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="pending"><el-button :loading="busy" @click="register(true)">重试原轨迹请求</el-button></div>
    <template v-if="detail">
      <div class="section-head"><h1>{{ detail.shipmentNo }}</h1><span class="status-chip">{{ deliveryLabels[detail.status] }}</span></div>
      <p class="lede">{{ detail.warehouseCode }} · {{ detail.carrier }} / {{ detail.trackingNo }}</p>
      <section class="surface flow">
        <h2>来源与状态</h2>
        <p>履约单
          <RouterLink v-if="profile?.role==='OPS'" class="mono" :to="`/warehouse/outbound/${detail.fulfillmentNo}`">{{ detail.fulfillmentNo }}</RouterLink>
          <span v-else class="mono">{{ detail.fulfillmentNo }}</span>
        </p>
        <p>出库号 <span class="mono">{{ detail.dispatchNo }}</span></p>
        <p v-if="detail.deliveredAt">签收时间 {{ new Date(detail.deliveredAt).toLocaleString('zh-CN') }}。已签收，不再接受新轨迹。</p>
      </section>
      <section v-if="detail.allowedActions.includes('ADD_TRACKING_EVENT')" class="surface flow">
        <h2>登记轨迹</h2>
        <form class="filters" @submit.prevent="register()">
          <label class="field"><span>事件</span><select v-model="kind" :disabled="busy || pending">
            <option value="IN_TRANSIT">在途</option><option value="DELIVERED">签收</option></select></label>
          <label class="field"><span>事件号</span><input v-model.trim="source" required maxlength="64" :disabled="busy || pending"></label>
          <label class="field"><span>发生时间（空白用当前时间）</span><input v-model="occurred" type="datetime-local" step="1" :disabled="busy || pending"></label>
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="pending">提交轨迹</el-button>
        </form>
      </section>
      <section class="surface">
        <div class="section-head"><h2>运输轨迹</h2></div>
        <div class="table-wrap"><table>
          <thead><tr><th>业务时间</th><th>事件</th><th>事件号</th></tr></thead>
          <tbody><tr v-for="event in detail.events" :key="event.sourceEventNo">
            <td>{{ new Date(event.occurredAt).toLocaleString('zh-CN') }}</td><td>{{ deliveryLabels[event.type] }}</td><td class="mono">{{ event.sourceEventNo }}</td>
          </tr></tbody>
        </table></div>
        <p v-if="!detail.events.length" class="empty">暂无轨迹。</p>
      </section>
    </template>
  </div>
</template>

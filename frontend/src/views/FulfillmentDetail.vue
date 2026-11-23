<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { command, get, message, pendingCommand, profile } from '../api'
import { deliveryLabels, fulfillmentLabels, type FulfillmentDetail, type ShipmentDetail } from './order'

const route=useRoute()
const router=useRouter()
const path=`/fulfillments/${encodeURIComponent(String(route.params.fulfillmentNo))}`
const detail=ref<FulfillmentDetail>()
const busy=ref(false)
const error=ref('')
const pending=ref<string>()
const pickPath=(task:string)=>`${path}/tasks/${task}/pick`
const taskLabels:Record<string,string>={PENDING:'待拣货',PICKED:'已确认拣货',CANCELLED:'已取消'}

function findPending() {
  pending.value=[`${path}/dispatch`,`${path}/shipment`,...(detail.value?.tasks.map(t=>pickPath(t.taskNo))??[])]
    .find(p=>pendingCommand(p))
}
async function refresh() { detail.value=await get<FulfillmentDetail>(path); findPending() }
onMounted(async()=>{ try { await refresh() } catch(e) { error.value=message(e) } })

async function apply(actionPath:string,retry=false) {
  if (busy.value) return
  busy.value=true
  error.value=''
  try {
    const result=await command<ShipmentDetail>(actionPath,retry ? undefined : null)
    if (actionPath.endsWith('/shipment')) await router.push(`/shipments/${result.shipmentNo}`)
    else await refresh()
  } catch(e) { error.value=message(e) }
  finally { busy.value=false; findPending() }
}
</script>

<template>
  <div class="flow">
    <RouterLink to="/warehouse/outbound">← 出库作业</RouterLink>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="pending"><el-button :loading="busy" @click="apply(pending,true)">重试原请求</el-button></div>
    <template v-if="detail">
      <div class="section-head"><h1>{{ detail.fulfillmentNo }}</h1>
        <span class="status-chip">{{ detail.status==='DISPATCHED'?'已出库':fulfillmentLabels[detail.status] }}</span></div>
      <p class="lede">仓库 {{ detail.warehouseCode }} · 订单
        <RouterLink v-if="profile?.role==='OPS'" :to="`/orders/${detail.orderNo}`">{{ detail.orderNo }}</RouterLink>
        <span v-else class="mono">{{ detail.orderNo }}</span>
      </p>
      <section class="surface flow">
        <div class="section-head"><h2>拣货任务</h2><span class="muted">按源库位整条确认</span></div>
        <div class="table-wrap"><table>
          <thead><tr><th>SKU</th><th>源库位</th><th>应拣</th><th>任务</th><th>状态 / 操作</th></tr></thead>
          <tbody><tr v-for="task in detail.tasks" :key="task.taskNo">
            <td>{{ task.skuCode }}</td><td>{{ task.binCode }}</td><td>{{ task.quantity }}</td><td class="mono">{{ task.taskNo }}</td>
            <td><el-button v-if="task.allowedActions.includes('PICK')" :disabled="busy || !!pending" @click="apply(pickPath(task.taskNo))">确认拣货</el-button>
              <span v-else>{{ taskLabels[task.status] }}</span></td>
          </tr></tbody>
        </table></div>
      </section>
      <section class="surface flow">
        <div class="section-head"><h2>出库与运单</h2>
          <el-button v-if="detail.allowedActions.includes('DISPATCH')" type="primary" :loading="busy" :disabled="!!pending"
            @click="apply(`${path}/dispatch`)">确认出库</el-button>
          <el-button v-if="detail.allowedActions.includes('CREATE_SHIPMENT')" type="primary" :loading="busy" :disabled="!!pending"
            @click="apply(`${path}/shipment`)">建模拟运单</el-button>
        </div>
        <template v-if="detail.dispatch">
          <p>出库号 <RouterLink class="mono" :to="{path:'/inventory',query:{bizNo:detail.dispatch.dispatchNo}}">{{ detail.dispatch.dispatchNo }}</RouterLink>
            · {{ new Date(detail.dispatch.dispatchedAt).toLocaleString('zh-CN') }}</p>
          <p v-if="detail.shipment">
            运单 <RouterLink class="mono" :to="`/shipments/${detail.shipment.shipmentNo}`">{{ detail.shipment.shipmentNo }}</RouterLink>
            · {{ deliveryLabels[detail.shipment.status] }}
          </p>
        </template>
        <p v-else-if="detail.status!=='CANCELLED'" class="muted">全部确认拣货后才能出库；确认出库时扣减现存和预占。</p>
        <p v-else class="muted">履约单已取消，不会出库。</p>
      </section>
    </template>
  </div>
</template>

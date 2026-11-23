<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { command, get, message, pendingCommand, profile } from '../api'
import { channels, deliveryLabels, fulfillmentLabels, routeStatus, type Order } from './order'

const route = useRoute()
const path = `/orders/${encodeURIComponent(String(route.params.orderNo))}`
const order = ref<Order>()
const error = ref('')
const busy = ref(false)
const showCancel = ref(false)
const reason = ref('')
const routePending = ref(Boolean(pendingCommand(`${path}/route`)))
const cancelPending = ref(Boolean(pendingCommand(`${path}/cancel`)))
const reservationState: Record<string,string> = { ACTIVE: '有效', RELEASED: '已释放', CONSUMED: '已出库' }
const events: Record<string,string> = { ...routeStatus, RECEIVED: '已接单', ALLOCATED: '路由成功', SHORTAGE: '路由缺货',
  RESERVE: '预占', RELEASE: '释放', PICK: '确认拣货', DISPATCH: '确认出库', SHIPMENT_CREATED: '建模拟运单',
  IN_TRANSIT: '在途', DELIVERED: '签收' }

async function refresh() { order.value = await get<Order>(path) }
onMounted(async () => { try { await refresh() } catch (e) { error.value = message(e) } })

async function mutate(action: 'route' | 'cancel', retry = false) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    order.value = await command<Order>(`${path}/${action}`,retry ? undefined : action === 'route' ? null : { reason: reason.value })
    showCancel.value = false
    await refresh()
  } catch (e) { error.value = message(e) }
  finally {
    busy.value = false
    routePending.value = Boolean(pendingCommand(`${path}/route`))
    cancelPending.value = Boolean(pendingCommand(`${path}/cancel`))
  }
}
</script>

<template>
  <div class="flow">
    <RouterLink to="/orders">← 渠道订单</RouterLink>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="routePending || cancelPending" class="filters">
      <el-button v-if="routePending" :loading="busy" @click="mutate('route',true)">重试原路由请求</el-button>
      <el-button v-if="cancelPending" :loading="busy" @click="mutate('cancel',true)">重试原取消请求</el-button>
    </div>
    <template v-if="order">
      <div class="section-head"><h1>{{ order.orderNo }}</h1><span class="status-chip">{{ routeStatus[order.routeStatus] }}</span></div>
      <p class="lede">{{ channels[order.channel] }} / {{ order.channelOrderNo }} · {{ new Date(order.paidAt).toLocaleString('zh-CN') }} 已支付</p>
      <section class="surface flow">
        <div class="section-head">
          <h2>订单进度</h2>
          <div v-if="profile?.role === 'OPS'" class="filters">
            <el-button v-if="order.allowedActions.includes('ROUTE')" type="primary" :loading="busy" :disabled="routePending || cancelPending"
              @click="mutate('route')">{{ order.routeStatus === 'SHORTAGE' ? '补货后重试路由' : '执行路由' }}</el-button>
            <el-button v-if="order.allowedActions.includes('CANCEL')" :disabled="busy || routePending || cancelPending" @click="showCancel = !showCancel">取消整单</el-button>
          </div>
        </div>
        <p>仓库履约：{{ fulfillmentLabels[order.fulfillmentStatus] }} · 运输：{{ deliveryLabels[order.deliveryStatus] }}</p>
        <p v-if="order.cancelReason" class="muted">取消原因：{{ order.cancelReason }}</p>
        <form v-if="showCancel" class="filters" @submit.prevent="mutate('cancel')">
          <label class="field"><span>取消原因</span><input v-model.trim="reason" required maxlength="200" :disabled="busy || cancelPending"></label>
          <el-button native-type="submit" :loading="busy" :disabled="cancelPending || routePending">确认取消</el-button>
        </form>
      </section>
      <section v-if="order.shortages.length" class="surface flow">
        <h2>缺货明细</h2><p class="muted">整单未预占。补齐缺口后，对同一张订单重新路由。</p>
        <div class="table-wrap"><table><thead><tr><th>SKU</th><th>需求</th><th>可分配</th><th>缺口</th></tr></thead>
          <tbody><tr v-for="row in order.shortages" :key="row.skuCode">
            <td>{{ row.skuCode }}</td><td>{{ row.requestedQty }}</td><td>{{ row.availableQty }}</td><td>{{ row.missingQty }}</td>
          </tr></tbody>
        </table></div>
      </section>
      <section class="surface flow">
        <h2>订单需求</h2>
        <div class="table-wrap"><table><thead><tr><th>原单行</th><th>SKU</th><th>订购</th><th>当前预占</th><th>已拣</th><th>已出库</th></tr></thead>
          <tbody><tr v-for="row in order.lines" :key="row.lineNo">
            <td>{{ row.lineNo }}</td><td>{{ row.skuCode }}</td><td>{{ row.orderedQty }}</td><td>{{ row.reservedQty }}</td>
            <td>{{ row.pickedQty }}</td><td>{{ row.dispatchedQty }}</td>
          </tr></tbody>
        </table></div>
      </section>
      <section class="surface flow">
        <h2>分仓履约与预占</h2>
        <p v-if="!order.fulfillments.length" class="muted">暂无履约单。</p>
        <div v-for="f in order.fulfillments" :key="f.fulfillmentNo" class="flow">
          <div class="section-head"><span><strong>{{ f.warehouseCode }}</strong> ·
            <RouterLink v-if="profile?.role === 'OPS'" class="mono" :to="`/warehouse/outbound/${f.fulfillmentNo}`">{{ f.fulfillmentNo }}</RouterLink>
            <span v-else class="mono">{{ f.fulfillmentNo }}</span></span>
            <span class="status-chip">{{ f.status === 'DISPATCHED' ? '已出库' : fulfillmentLabels[f.status] }}</span></div>
          <p v-if="f.dispatch" class="muted">
            出库 <span class="mono">{{ f.dispatch.dispatchNo }}</span> ·
            <template v-if="f.shipment"><RouterLink :to="`/shipments/${f.shipment.shipmentNo}`">{{ f.shipment.shipmentNo }}</RouterLink>
              {{ deliveryLabels[f.shipment.status] }}</template><template v-else>待建运单</template>
          </p>
          <div class="table-wrap"><table><thead><tr><th>原单行</th><th>SKU</th><th>本仓应发</th><th>库位预占</th></tr></thead>
            <tbody><tr v-for="row in f.lines" :key="row.orderLineNo">
              <td>{{ row.orderLineNo }}</td><td>{{ row.skuCode }}</td><td>{{ row.quantity }}</td>
              <td><div v-for="r in row.reservations" :key="r.reservationNo">{{ r.binCode }} · {{ r.quantity }} 件 · {{ reservationState[r.status] }}
                <RouterLink v-if="profile?.role === 'OPS'" class="mono" :to="{path:'/inventory',query:{bizNo:r.reservationNo}}">{{ r.reservationNo }}</RouterLink>
                <span v-else class="mono">{{ r.reservationNo }}</span>
              </div></td>
            </tr></tbody>
          </table></div>
        </div>
      </section>
      <section class="surface flow">
        <h2>时间线</h2>
        <div class="table-wrap"><table><thead><tr><th>时间</th><th>动作</th><th>仓</th><th>业务单号</th></tr></thead>
          <tbody><tr v-for="(event,index) in order.timeline" :key="index">
            <td>{{ new Date(event.occurredAt).toLocaleString('zh-CN') }}</td><td>{{ events[event.type] ?? event.type }}</td>
            <td>{{ event.warehouseCode ?? '—' }}</td><td class="mono">{{ event.bizNo }}</td>
          </tr></tbody>
        </table></div>
      </section>
    </template>
  </div>
</template>

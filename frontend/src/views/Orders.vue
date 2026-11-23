<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { get, message, profile, type Page } from '../api'
import { channels, routeStatus, type OrderSummary } from './order'

const filters = reactive({ channel: '', channelOrderNo: '', orderNo: '', routeStatus: '' })
const applied = { ...filters }
const result = ref<Page<OrderSummary>>()
const loading = ref(false)
const error = ref('')

async function load(page = 1) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { result.value = await get<Page<OrderSummary>>('/orders', { ...applied, page }) }
  catch (e) { error.value = message(e) }
  finally { loading.value = false }
}

function search() { Object.assign(applied,filters); return load() }
onMounted(() => load())
</script>

<template>
  <div class="flow">
    <div class="section-head"><h1>渠道订单</h1><RouterLink v-if="profile?.role === 'OPS'" to="/orders/new">录入已支付订单</RouterLink></div>
    <p class="lede">接单只登记渠道需求；路由成功后才预占两仓的可分配库存。</p>
    <form class="surface filters" @submit.prevent="search">
      <label class="field"><span>渠道</span><select v-model="filters.channel"><option value="">全部</option>
        <option v-for="(label, code) in channels" :key="code" :value="code">{{ label }}</option></select></label>
      <label class="field"><span>渠道单号</span><input v-model.trim="filters.channelOrderNo" placeholder="SAMPLE-001"></label>
      <label class="field"><span>内部单号</span><input v-model.trim="filters.orderNo" placeholder="CO-…"></label>
      <label class="field"><span>路由状态</span><select v-model="filters.routeStatus"><option value="">全部</option>
        <option v-for="(label, code) in routeStatus" :key="code" :value="code">{{ label }}</option></select></label>
      <el-button native-type="submit" type="primary" :loading="loading">查询</el-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="surface">
      <div class="section-head"><h2>订单列表</h2><span v-if="result" class="muted">共 {{ result.total }} 张</span></div>
      <div class="table-wrap"><table>
        <thead><tr><th>内部单号</th><th>渠道</th><th>渠道单号</th><th>支付时间</th><th>路由状态</th></tr></thead>
        <tbody><tr v-for="order in result?.items" :key="order.orderNo">
          <td class="mono"><RouterLink :to="`/orders/${order.orderNo}`">{{ order.orderNo }}</RouterLink></td>
          <td>{{ channels[order.channel] }}</td><td>{{ order.channelOrderNo }}</td>
          <td>{{ new Date(order.paidAt).toLocaleString('zh-CN') }}</td>
          <td><span class="status-chip">{{ routeStatus[order.routeStatus] }}</span></td>
        </tr></tbody>
      </table></div>
      <p v-if="!result?.items.length" class="empty">{{ loading ? '正在查询…' : '没有符合条件的订单。' }}</p>
      <el-pagination v-if="result && result.total > result.size" class="pager" layout="prev, pager, next"
        :current-page="result.page" :page-size="result.size" :total="result.total" @current-change="load" />
    </section>
  </div>
</template>

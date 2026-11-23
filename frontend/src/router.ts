import { createRouter, createWebHistory } from 'vue-router'
import { ApiError, get, profile, type Profile } from './api'
import Login from './views/Login.vue'
import Purchases from './views/Purchases.vue'
import Purchase from './views/Purchase.vue'
import Receipt from './views/Receipt.vue'
import Inventory from './views/Inventory.vue'
import PurchaseCreate from './views/PurchaseCreate.vue'
import Orders from './views/Orders.vue'
import OrderCreate from './views/OrderCreate.vue'
import OrderDetail from './views/OrderDetail.vue'
import Fulfillments from './views/Fulfillments.vue'
import FulfillmentDetail from './views/FulfillmentDetail.vue'
import Shipments from './views/Shipments.vue'
import ShipmentDetail from './views/ShipmentDetail.vue'
import Operations from './views/Operations.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/purchase-orders' },
    { path: '/login', component: Login, meta: { public: true, title: '登录' } },
    { path: '/purchase-orders', component: Purchases, meta: { title: '采购单' } },
    { path: '/purchase-orders/new', component: PurchaseCreate, meta: { title: '新建采购单', ops: true } },
    { path: '/purchase-orders/:poNo', component: Purchase, meta: { title: '采购单详情' } },
    { path: '/receipts/:receiptNo', component: Receipt, meta: { title: '收货单详情' } },
    { path: '/inventory', component: Inventory, meta: { title: '库存台账' } },
    { path: '/warehouse/outbound', component: Fulfillments, meta: { title: '出库作业' } },
    { path: '/warehouse/outbound/:fulfillmentNo', component: FulfillmentDetail, meta: { title: '履约详情' } },
    { path: '/orders', component: Orders, meta: { title: '渠道订单', orders: true } },
    { path: '/orders/new', component: OrderCreate, meta: { title: '录入已支付订单', orders: true, ops: true } },
    { path: '/orders/:orderNo', component: OrderDetail, meta: { title: '订单详情', orders: true } },
    { path: '/shipments', component: Shipments, meta: { title: '模拟运单', orders: true } },
    { path: '/shipments/:shipmentNo', component: ShipmentDetail, meta: { title: '运单详情', orders: true } },
    { path: '/operations', component: Operations, meta: { title: '运营汇总', orders: true, ops: true } },
    { path: '/:pathMatch(.*)*', redirect: '/purchase-orders' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!profile.value) {
    try { profile.value = await get<Profile>('/auth/me') }
    catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) console.warn('无法读取本机会话')
      return { path: '/login', query: { next: to.fullPath } }
    }
  }
  const role = profile.value.role
  const home = role === 'WAREHOUSE' ? '/purchase-orders' : '/orders'
  if (to.meta.ops && role !== 'OPS' || to.meta.orders && role === 'WAREHOUSE' || !to.meta.orders && role === 'SUPPORT') return home
  return true
})

export const channels = { DIRECT: '自营商城', DOUYIN: '抖音小店' }
export const routeStatus = { RECEIVED: '待路由', SHORTAGE: '缺货', ALLOCATED: '已分配', CANCELLED: '已取消' }
export const fulfillmentLabels: Record<string,string> = {
  NOT_STARTED: '未开始', READY_TO_PICK: '待拣货', PICKING: '拣货中', PICKED: '已拣完', READY_TO_DISPATCH: '待出库',
  PARTIALLY_DISPATCHED: '部分已发', DISPATCHED: '全部已发', CANCELLED: '已取消', SHORTAGE: '缺货',
}
export const deliveryLabels: Record<string,string> = {
  NOT_STARTED: '未开始', AWAITING_SHIPMENT: '待建运单', IN_PROGRESS: '运输处理中',
  CREATED: '待运输', IN_TRANSIT: '在途', DELIVERED: '已签收',
}
export interface DispatchFact { dispatchNo: string; dispatchedAt: string }
export interface ShipmentFact { shipmentNo: string; carrier: string; trackingNo: string; status: string }
export interface ItemInput { skuCode: string; quantity: number }
export interface OrderSummary {
  orderNo: string; channel: keyof typeof channels; channelOrderNo: string; paidAt: string; routeStatus: keyof typeof routeStatus
}
export interface Order extends OrderSummary {
  shortages: { skuCode: string; requestedQty: number; availableQty: number; missingQty: number }[]
  lines: { lineNo: number; skuCode: string; orderedQty: number; reservedQty: number; pickedQty: number; dispatchedQty: number }[]
  fulfillments: { fulfillmentNo: string; warehouseCode: string; status: string; lines: {
    orderLineNo: number; skuCode: string; quantity: number
    reservations: { reservationNo: string; binCode: string; quantity: number; status: string }[]
  }[]; dispatch: DispatchFact | null; shipment: ShipmentFact | null }[]
  fulfillmentStatus: string; deliveryStatus: string; cancelReason: string | null; allowedActions: string[]
  timeline: { type: string; bizNo: string; warehouseCode: string | null; occurredAt: string }[]
}

export interface FulfillmentSummary { fulfillmentNo: string; orderNo: string; warehouseCode: string; status: string }
export interface FulfillmentDetail extends FulfillmentSummary {
  lines: { orderLineNo: number; skuCode: string; quantity: number }[]
  tasks: { taskNo: string; skuCode: string; binCode: string; quantity: number; status: string; allowedActions: string[] }[]
  dispatch: DispatchFact | null; shipment: ShipmentFact | null; allowedActions: string[]
}
export interface ShipmentSummary extends ShipmentFact { dispatchNo: string; fulfillmentNo: string; warehouseCode: string; deliveredAt: string | null }
export interface ShipmentDetail extends ShipmentSummary {
  events: { sourceEventNo: string; type: string; occurredAt: string }[]; allowedActions: string[]
}

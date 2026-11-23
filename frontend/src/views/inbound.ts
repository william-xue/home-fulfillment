export const poStatus: Record<string,string> = { OPEN: '待收货', PARTIAL: '部分收货', RECEIVED: '已收齐' }
export interface PurchaseSummary { poNo: string; warehouseCode: string; supplierName: string; status: string }
export interface Line { lineNo: number; skuCode: string; orderedQty: number; receivedQty: number; remainingQty: number }
export interface PurchaseDetail extends PurchaseSummary { lines: Line[]; receiptNos: string[]; allowedActions: string[] }
export interface PutAwayFact { putAwayNo: string; destinationBinCode: string; quantity: number }
export interface ReceiptDetail {
  receiptNo: string; poNo: string; lineNo: number; skuCode: string; warehouseCode: string;
  binCode: string; quantity: number; putAwayQty: number; remainingQty: number;
  putAways: PutAwayFact[]; allowedActions: string[];
}

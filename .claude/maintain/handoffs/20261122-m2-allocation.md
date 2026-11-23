# M2 接管卡｜为什么整单路由不会占一半

1. 运营录入的是已支付需求；接单后现存和预占都没动。只有路由成功才承诺库存，缺货可以补货后路由同一张单。
2. [`OrderCommands.route()` / `cancel()`](../../../backend/src/main/java/com/homefulfillment/fulfillment/OrderCommands.java) 串起 OMS、WMS 和库存；[`InventoryLedger.reserve()` / `release()`](../../../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java) 是真正改库位预占和流水的地方。
3. 先锁订单头，再按 SKU 主键升序拿锁，再读可拣位和规划全单。不能把 `reserve()` 移进逐行试算，不能只改库存余额而不写预占事实与流水。

| 修改 | 入口与验证 |
|---|---|
| 渠道重放判断 | `OrderCommands.accept()` 归一时间与商品集合，`Orders.accept()` 用渠道/外部号唯一键比对；A05 查不同渠道相同号码及原单引用。 |
| 仓、库位优先顺序 | `InventoryLedger.availablePickable()` 按仓优先级、库位优先级、主键排序；`route()` 按这个结果试算。改变策略需要先改规格。 |
| 缺货与技术失败 | `OrderCommands.route()` 只有不够时写 `SHORTAGE`；写库异常原样抛出，由 `CommandJournal` 整笔回滚；参见 A06。 |
| 取消释放 | `OrderCommands.cancel()` → `Fulfillments.requireAllUnpicked()` → `InventoryLedger.release()`；M3 增加拣货状态时继续共用订单锁。 |
| 增一项订单展示 | `OrderRead` 负责跨域只读查询；`OrderDetail.vue` 只展示状态和动作。对账用 [`verify-m2.sql`](../../../infra/mysql/verify-m2.sql)。 |

没有引入新服务或依赖。`OrderItems` 只复用两类录入的 SKU 检查；`CommandJournal.execute()` 取代写死 201 的逻辑，既保留 M1 契约，也支持路由、取消与渠道重放的 200。回归入口 `python3 tests/m1.py --m2`；两个渠道抢最后一件的 A07 应保持一成一缺。

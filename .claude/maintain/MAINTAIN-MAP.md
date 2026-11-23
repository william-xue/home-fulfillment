# 维护地图｜M0–M4

`main` · V1 基线。各能力的实际验收见 [Task](../../docs/TASK-v1.md)。

**主线：** Vue 按单号读采购单 → Vite 代理 `/api/v1` → Spring 会话及授权仓 → 读 MySQL，返回余欠和可操作项 → 执行收货/上架 → `CommandJournal` 开事务 → `InboundCommands` 写单据、`InventoryLedger` 写账 → 返回业务号，从页面或 SQL 回读同号。

**路由：** Vue 接单页面先写 `channel_order` → 详情「执行路由」→ `OrderCommands` 锁订单与 SKU、计算全单 → 缺货只记 OMS，够货才让 WMS 建履约、库存写预占和流水 → 查询详情回读两仓分配。

**出库与运输：** 仓管确认任务 → WMS 更新拣货（账不动）→ `OutboundCommands` 同订单锁下确认出库 → `InventoryLedger.consume()` 记账 → 运营由出库事实建模拟运单 → `Shipments` 登轨迹 → 订单查询按两仓事实汇总。

**投递：** 出库事务同时写 `outbox_event` → 投递器短事务认领 → RocketMQ 确认 → 消费者核对同号真实出库，Inbox 去重并用手工建单的同一用例 → 事务提交后才 ACK；运营区只缓存三个统计计数。

**初始化：** [`start.sh`](../../start.sh) → Compose MySQL → [`ensure-app-user.sh`](../../infra/mysql/ensure-app-user.sh) 建运行账号 → Flyway V1–V5 → 显式 `--seed` 才补主数据、账号和采购种子单（不写收货、上架或渠道单）。`--m4` 才启中间件；基础健康不挂 Redis/MQ。

| 现象 | 先找 |
|---|---|
| 页面不能连健康接口 | [`vite.config.ts`](../../frontend/vite.config.ts) 和 `.run/frontend.log`；对比后端 `/actuator/health`。 |
| Java 起不来 / 数据库连接失败 | [`application.yml`](../../backend/src/main/resources/application.yml)、`.run/backend.log`、Compose 的新 MySQL；不改用旧库。 |
| 登录、角色或仓权限 | [`SecurityConfig`](../../backend/src/main/java/com/homefulfillment/identity/SecurityConfig.java)、[`Identity.Actor`](../../backend/src/main/java/com/homefulfillment/identity/Identity.java)。前端隐藏按钮不是鉴权。 |
| 收货量、余欠、上架量不对 | [`InboundCommands`](../../backend/src/main/java/com/homefulfillment/inbound/InboundCommands.java) 与 [`InboundMapper`](../../backend/src/main/java/com/homefulfillment/inbound/InboundMapper.java)；读字段和 `allowedActions` 查 [`InboundRead`](../../backend/src/main/java/com/homefulfillment/inbound/InboundRead.java)。 |
| 双边流水或可分配量不对 | 写账查 [`InventoryLedger`](../../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java)，展示口径查 [`InventoryController`](../../backend/src/main/java/com/homefulfillment/inventory/InventoryController.java)，两处用 [SQL](../../infra/mysql/verify-m1.sql) 对证。 |
| 网络超时、重试、重复作业 | [`api.ts`](../../frontend/src/api.ts) 在本标签保存原请求键；[`CommandJournal`](../../backend/src/main/java/com/homefulfillment/web/CommandJournal.java) 和业务一起提交结果；别换新键当重试。 |
| 页面字段、表格、按钮 | [`frontend/src/views`](../../frontend/src/views) 内对应单据；公用排版在 [`workbench.css`](../../frontend/src/workbench.css)。 |
| 数据库升级、种子或回归 | [`db/migration`](../../backend/src/main/resources/db/migration)、[`DemoSeed`](../../backend/src/main/java/com/homefulfillment/identity/DemoSeed.java)、[`tests/m1.py`](../../tests/m1.py)；已应用的迁移不能原地改。 |
| 接单重放、缺货、拆仓、取消 | [`OrderCommands`](../../backend/src/main/java/com/homefulfillment/fulfillment/OrderCommands.java) 是协调入口；[`Orders`](../../backend/src/main/java/com/homefulfillment/oms/Orders.java) 存路由事实，[`InventoryLedger`](../../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java) 改预占。 |
| 订单详情数据或历史错了 | [`OrderRead`](../../backend/src/main/java/com/homefulfillment/fulfillment/OrderRead.java) → [`OrderDetail.vue`](../../frontend/src/views/OrderDetail.vue)，用 [verify-m2.sql](../../infra/mysql/verify-m2.sql) 追同号。 |
| 拣货、出库、取消竞争 | [`OutboundCommands`](../../backend/src/main/java/com/homefulfillment/fulfillment/OutboundCommands.java) 和 `OrderCommands.cancel()` 共用订单锁；出库扣账在 [`InventoryLedger.consume()`](../../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java)。 |
| 运单、签收、迟到轨迹 | [`Shipments`](../../backend/src/main/java/com/homefulfillment/tms/Shipments.java) 只写运输事实；[`verify-m3.sql`](../../infra/mysql/verify-m3.sql) 按同号查。 |
| 出库成功但没有运单 | [`OutboxStore`](../../backend/src/main/java/com/homefulfillment/events/OutboxStore.java) 查发布状态， [`DispatchConsumer`](../../backend/src/main/java/com/homefulfillment/events/DispatchConsumer.java) 与 Inbox 查消费；用 [verify-m4.sql](../../infra/mysql/verify-m4.sql) 一次看三步。 |
| 运营计数滞后或 Redis 停服 | [`OperationsSummary`](../../backend/src/main/java/com/homefulfillment/operations/OperationsSummary.java)，缓存最旧 30 秒，其他业务仍用主库。 |

详见 [M1 接管卡](handoffs/20261122-m1-inbound.md)、[M2 接管卡](handoffs/20261122-m2-allocation.md)、[M3 接管卡](handoffs/20261122-m3-outbound.md) 和 [M4 接管卡](handoffs/20261122-m4-reliability.md)。

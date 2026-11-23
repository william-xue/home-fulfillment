# 供应链业务工作台｜V1 设计规格

**版本：1.1 · M0–M4 设计基线。** 本文件决定业务行为、数据和接口；[实施计划](implementation-plan.md)决定开工顺序。[项目交接](../HANDOFF.md)负责新会话怎么找到真实停点。旧课卡、沙盘和旧项目是参考，不覆盖本规格。发现实际冲突，报告对应规则、最小复现和受影响的测试，不私自更改业务语义。

## 1. 产品：为什么做、做到哪

项目显示名 **供应链业务工作台**，案例是家居电商。目标目录 `/Users/xueyuan/Desktop/home-fulfillment`，独立前后端、数据库和运行环境；旧仓库及数据库不迁移、不改动。面向一家品牌的运营、仓管和客服；自营商城 `DIRECT` 与抖音小店 `DOUYIN` 共享上海仓 `SH`、广州仓 `GZ` 库存。渠道、支付、承运商仅使用本地模拟数据。

**学习目标：** 这套系统用来训练供应链业务建模与全栈独立交付，并把真实个人贡献转成能扛追问的面试案例。工程验收见 §9；个人摸底、练习和面试证据另见 [学习方案](learning.md) 与 [面试训练](interview.md)，不把代码跑通等同于学会。

**交付主线：** 运营开采购单 → 仓管部分收货、上架 → 运营录入已支付渠道单 → 路由并预占 → 仓管拣货、出库 → 运输建单、登记签收 → 客服用渠道号查全链。要能解释缺货、重复、取消、事务失败和消息补发。采购单和渠道订单都不是商城下单页面。

**V1 不做：** 真实渠道同步、实际收款退款、运费、财务、合单、退货、撤拣、盘点、调拨、批次效期、套装、多货主、组织租户、规则引擎；无微服务、ES、上线承诺。主数据和账号由本地种子提供，不做后台 CRUD。未定义功能不生成占位页面、假接口或空表。

延续的三条证据：Carfolio 的「页面 → 同号 SQL → Java 写入点」；`wms-core` 的单据、余额与流水；[原沙盘](../../一条线/供应链沙盘/00-业务设定与全景图.md)的品牌、共享库存、双仓与出库扣减。**有意不照搬**旧 WMS 的拣货扣库、部分预占、手动 OMS 同步；旧模型是对照，不是待移植的框架。

### 全局功能清单

这张表是 **V1 全部交付功能**，不是当前进度表。后续会话可以用能力编号判断某项是到期未做，还是原本不在 V1；业务细节见右列。

| 编号 | 功能 | 实施阶段 | 规格与验收 |
|---|---|---|---|
| F01 | 独立前后端启动、独立 MySQL、Flyway、种子、健康检查 | M0 | §8.3 · A01 |
| F02 | 登录、退出、三个角色、仓库数据权限、最小审计 | M1，后续动作沿用 | §3、§7、§8.1 · A03、A10 |
| F03 | 采购查询、部分收货、超收拒绝、收货作业历史 | M1（查询种子单）；M2（OPS 新建采购单） | §4–7 · A02–A04、A05 |
| F04 | 分次上架、按同收货单限制可上架量、双边流水 | M1 | §6.2、§7 · A02–A04 |
| F05 | 库存余额、可分配量、流水按业务号查 | M1 起 | §2–4、§6 · A02、A05、A08 |
| F06 | 已支付模拟渠道单接入、同键重放与冲突、订单列表/详情 | M2 | §5、§7 · A05、A10 |
| F07 | 上海优先、广州补齐、按仓拆履约；预占、缺货及补货重试 | M2 | §4–7 · A05–A07 |
| F08 | 整单取消并释放，已拣/已出库拒绝取消 | M2 开始可取消，M3 接入拣货闸门 | §5、§6 · A06、A09 |
| F09 | 仓管作业列表、按库位确认拣货、出库扣库、双仓发货摘要 | M3 | §3、§5–7 · A08、A09 |
| F10 | 模拟建运单、登记在途与签收、不回退；客服查订单全链 | M3 | §3、§5、§7 · A10 |
| F11 | Outbox 投递与重试、消费去重、自动建运单、故障观察 | M4 | §8.2 · A11 |
| F12 | OPS 汇总缓存及降级、干净环境重演主线、备份恢复 | M4 | §8.2–8.3 · A12 |

## 2. 统一叫法和数据归属

| 词 | 定义与谁负责 |
|---|---|
| 渠道订单 / 订单行 | OMS：已支付上游需求；由渠道和渠道单号唯一识别。一个 SKU 只出现一行，不重复下单键。 |
| 路由 | OMS：按规则为整张订单决定仓和数量；成功产生全部履约单，失败记录缺货，不留部分承诺。接单与路由是两次明确操作。 |
| 履约单 / 履约行 | WMS：一张订单在一个仓的执行凭证；同一订单 SKU 可以分到两个仓，每仓履约行必须回指原订单行。 |
| 库位余额 / 预占 | 库存：余额按 SKU × 库位，库位全局 ID 属于一个仓；预占指某履约行的某库位数量承诺。一笔预占只对应一条拣货任务。 |
| 拣货 / 出库 | WMS：拣货确认“已从源位拿货并待出库”，出库确认“货已交接、离仓”，只有后者扣库存账。 |
| 发货事实 / 运单 | WMS 拥有发货事实，TMS 拥有运单及运输轨迹；一张履约单仅能出库一次、生成一张运单。签收不回写库存。 |
| 现存 / 可分配 | 现存在库位账；只有可拣位的 `on_hand - reserved` 参与仓级可分配量，暂存位不参与。没有另一份仓级库存真值。 |

**模块与依赖：** `identity`（登录和仓权限）、`catalog`（SKU、仓、库位）、`inbound`（采购、收货、上架）、`inventory`、`oms`、`wms`（履约与出库）、`tms`、`fulfillment`（跨模块命令协调和订单详情查询）。Java 一个 Spring Boot 应用；`fulfillment` 调各模块的用例接口，域模块不反向依赖协调层。

- `inbound` 调用库存的 `receive / moveToPickable`；`fulfillment` 调用库存的 `reserve / release / consume` 和 OMS/WMS 用例；`tms` 通过 `wms` 的只读发货接口验证事实。库存写库逻辑只在 `inventory`；不让 Controller 直接改表。
- 共享一个 MySQL 事务，协调层开启事务、模块写操作加入；库存、订单、履约不靠 MQ 最终一致。跨域详情可以专门做只读 SQL JOIN，不授予其他模块写权限。落表前先定义用例接口，不为每张表制造一套跨模块 DTO。
- **订单履约摘要、运单进度按底层事实查询，不另存一份状态。** 前端只负责展示后端给出的状态和可用动作，不重算。

## 3. 功能与页面

页面共用左侧导航、面包屑、表格筛选和详情页；默认列表 → 点单号打开详情，不做大屏。没有权限或状态不允许时解释原因；写请求提交中禁重复点击，超时提示「结果未知，刷新确认」，重试需保留请求键。详情页持久 URL 同号可分享给有访问权的人。

| 阶段 / 页面 | 默认视图和动作 |
|---|---|
| M1 `/login` | 用户名、密码、登录；未登录引导返回登录页，退出后作废会话。 |
| M1 `/purchase-orders`、`/purchase-orders/:poNo` | 按采购号、仓、状态筛选；仓管从采购行选择暂存位并输入本次收货量，看到余欠及收货历史。M1 用种子单；M2 起 OPS 可建单。 |
| M1 `/receipts/:receiptNo` | 同号显示采购行、SKU、暂存位、收货量、待上架量；选择同仓可拣位、输入数量，一次或多次上架；看上架号与两条流水。 |
| M1 `/inventory` | 仓、SKU、库位过滤；余额显示现存/预占/可分配；流水按业务号查询，明确标注暂存库存不可分配。 |
| M2 `/orders`、`/orders/:orderNo` | 按渠道/渠道号/内部号/状态查；录入模拟已支付订单；详情显示需求行、缺货原因、路由/重试/取消按钮，以及履约、预占和时间线。M2 的取消允许处理尚未进入拣货的单。 |
| M3 `/warehouse/outbound`、`/warehouse/outbound/:fulfillmentNo` | 默认自己仓；显示源库位、应拣量、任务状态。确认单条任务全部拣完；全拣完才允许出库。出库后展示发货事实，不允许再改任务。 |
| M3 `/shipments`、`/shipments/:shipmentNo` | 按运单/履约号查询；OPS 对已出库履约单建模拟运单、登记在途/签收；客服只读。 |
| M4 `/operations` | OPS 看积压/缺货数和出库事件投递情况，可对失败事件重试。缓存只用于本页计数，显示统计时间；订单详情和事件操作总是读主库。 |

订单时间线显示已接单、路由/失败、预占/释放、取消、拣货、出库、运单及轨迹；按 `occurredAt`，同时间再按动作先后类别及来源主键排序。履约区分三件事：**订单是否承诺、仓是否出库、运输是否签收**，不合并为一个模糊“已完成”。运输页提供「待建单」入口：用现有履约列表过滤 `DISPATCHED`，查看详情；未关联运单时 OPS 可建，不新建运输待办表。**采购来源不写成订单血缘：** 收货 → 上架是精确关联；上架后同 SKU 同位库存混合。订单精确追到预占库位及时间点之前的入库流水，不能声称出库的每件货来自某张采购单。

## 4. 数据模型与持久化契约

MySQL 8.4、InnoDB、`utf8mb4`。内部主键 `BIGINT`、外键实体同类型；需返回内部 ID 时以字符串编码，前端不作 `number`。单号 `VARCHAR(64)`、SKU/仓/库位编码 `VARCHAR(64)`；编号码按规范化值使用二进制比较，避免大小写或重音被排序规则合并。时间列 `DATETIME(3)` UTC，HTTP 用 ISO 8601 UTC，页面本地时区显示。数量为整数，单次输入 `1..1,000,000`，账面量 `BIGINT`，不可溢出；最初余额 0。普通实体有关联的 `created_at` 和必要的 `updated_at`；审计操作人从认证主体取。首版业务记录不物理删除、不提供跨状态任意更新。

下表列出**业务必需字段和约束**，不要求未到阶段提前迁移。业务号除指定客户端号外由服务端生成，`PO / RC / PA / CO / FO / DS / TS` 为前缀，使用全局唯一后缀，不用 `MAX+1`。可用测试 fixture/号生成器固定演示号，不把演示号写入生产路径。

| 阶段 / 表 | 粒度、字段、唯一性 |
|---|---|
| M1 `app_user`, `user_warehouse` | user：`id, username UQ, password_hash, role, enabled`；关联表 PK `(user_id,warehouse_id)`，只限制 WAREHOUSE。每人单角色。 |
| M1 `sku`, `warehouse`, `bin` | sku：`id, code UQ, name`；warehouse：`id, code UQ, name, route_priority UQ`（SH=10、GZ=20）；bin：`id, warehouse_id, code, type, pick_priority`，UQ `(warehouse_id,code)`；V1 `RECEIVING/PICKABLE`，不允许修改已用库位类型。 |
| M1 `purchase_order`, `purchase_order_line` | PO：`id, po_no UQ, warehouse_id, supplier_name, status, created_by`；行：`id, po_id, line_no, sku_id, ordered_qty, received_qty`，UQ `(po_id,line_no)` 与 `(po_id,sku_id)`；`0 <= received_qty <= ordered_qty`。 |
| M1 `receipt` | 本次对**一条采购行**收货：`id, receipt_no UQ, po_line_id, receiving_bin_id, qty, put_away_qty, actor_id`；`0 <= put_away_qty <= qty`。仓/SKU 由采购行及 PO 确定。 |
| M1 `put_away` | 单次从一张 receipt 上架到一个库位：`id, put_away_no UQ, receipt_id, destination_bin_id, qty, actor_id`；源库位由 receipt 确定。 |
| M1 `stock_balance` | 每 SKU × 库位一行：`id, sku_id, bin_id, on_hand, reserved`，UQ `(sku_id,bin_id)`；CHECK `0 <= reserved AND reserved <= on_hand`。warehouse 通过 bin 关联，不冗余。 |
| M1 `stock_movement` | 一次动作在一个余额行的变化：`id, balance_id, movement_type, biz_no, delta_on_hand, delta_reserved, on_hand_after, reserved_after, actor_id, occurred_at`；UQ `(movement_type,biz_no,balance_id)`；索引 `(biz_no,id)`, `(balance_id,id)`。无变更不写。 |
| M1 `command_receipt` | HTTP 写业务幂等凭证：`actor_id, operation, idempotency_key, request_hash, http_status, result_json, created_at`，PK `(actor_id,operation,idempotency_key)`。只留已提交的命令；失败事务不留假成功。 |
| M1 `operation_log` | 已完成业务动作审计：`id, action, entity_type, entity_no, actor_key, request_id, occurred_at, meta_json`，索引 `(entity_type,entity_no,id)`；路由结果记录在这里；不保存密码/令牌。 |
| M2 `channel_order`, `channel_order_line` | 头：`id, order_no UQ, channel, channel_order_no, paid_at, request_hash, route_status, shortage_json, route_attempt, cancel_reason, cancelled_at, created_by`，UQ `(channel,channel_order_no)`；行：`id, order_id, line_no, sku_id, ordered_qty`，UQ `(order_id,line_no)` 与 `(order_id,sku_id)`。`shortage_json` 只留当前缺口，路由成功或取消时清空；历史在审计。 |
| M2 `fulfillment_order`, `fulfillment_line` | 头：`id, fulfillment_no UQ, order_id, warehouse_id, status`，UQ `(order_id,warehouse_id)`；行：`id, fulfillment_id, order_line_id, qty`，UQ `(fulfillment_id,order_line_id)`；SKU 来自原订单行。 |
| M2 `stock_reservation` | `id, reservation_no UQ, fulfillment_line_id, balance_id, qty, status`，UQ `(fulfillment_line_id,balance_id)`、索引 `(balance_id,status)`；一行全部释放/消耗，不拆减本行数量。 |
| M3 `pick_task` | `id, task_no UQ, reservation_id UQ, status, picked_by, picked_at`；应拣量和源位来自预占，不存第二份。待/拣完/已取消，不支持单任务部分拣。 |
| M3 `dispatch` | `id, dispatch_no UQ, fulfillment_id UQ, actor_id, dispatched_at`。发货数量从不可再修改的履约行读取。不保存运单状态。 |
| M3 `shipment`, `tracking_event` | 运单：`id, shipment_no UQ, dispatch_id UQ, carrier, tracking_no, status, delivered_at`，UQ `(carrier,tracking_no)`；轨迹：`id, shipment_id, source_event_no, type, occurred_at, recorded_at, actor_id`，UQ `(shipment_id,source_event_no)`。 |
| M4 `outbox_event`, `consumer_inbox` | outbox：`event_id PK, event_type, dedupe_key UQ, payload_json, status, attempt, next_attempt_at, claim_token, lease_until, last_error, occurred_at, published_at`，索引 `(status,next_attempt_at)`；inbox：PK `(consumer_name,event_id)`，`processed_at`。 |

**约束实现：** 为内部引用加 FK、无级联删除；跨行/跨仓约束在命令事务里检查，例如 receipt 的暂存位和 PO 同仓、履约行的订单行属于同一订单。文本状态用 `VARCHAR + CHECK`，不允任意状态字符串。列表索引至少覆盖渠道外部号、`(warehouse_id,status,id)` 的采购/履约列表和 `(route_status,id)` 订单列表；其余查询测量后再补。

```text
purchase_order → purchase_order_line → receipt → put_away
                                      │          │
                                      └── stock_movement ← stock_balance ← bin ← warehouse
                                                               ↑
channel_order → order_line → fulfillment_line → stock_reservation → pick_task
      └────────→ fulfillment_order ──→ dispatch ──→ shipment ──→ tracking_event
```

`stock_movement.biz_no` 按类型关联：收货 receiptNo；上架 putAwayNo；预占/取消 reservationNo；出库 dispatchNo。**出库按同 `balance_id` 合并数量写一条流水**，避免多条预占占用同一库位时违反流水唯一键。拣货不写库存流水，但写作业和审计。

## 5. 状态机和业务失败

| 单据 | 状态与迁移 |
|---|---|
| PO | `OPEN`（所有行未收）→ `PARTIAL`（收过且任一行未满）→ `RECEIVED`（所有行收满）；也可直接 `OPEN → RECEIVED`。行不存独立状态，按数量推导。 |
| Receipt | 无可手改状态；`qty - put_away_qty` 是待上架量，0 表示完成。 |
| OMS 路由 | `RECEIVED → ALLOCATED / SHORTAGE`，`SHORTAGE → ALLOCATED / SHORTAGE`；`RECEIVED / SHORTAGE / ALLOCATED → CANCELLED`，已预占取消要求全部未拣未发。`ALLOCATED` 后重复路由返回既有履约结果，不再次分配。不存在“已出库回待路由”。 |
| 履约单 | `READY_TO_PICK → PICKING → PICKED → DISPATCHED`；一次拣完唯一任务可直接 `READY_TO_PICK → PICKED`。仅 `READY_TO_PICK → CANCELLED`，没有回退；拣货按钮是确认实际拣货，不做无数量的“开始”状态。 |
| 预占 / 任务 | `ACTIVE → RELEASED / CONSUMED`；任务 `PENDING → PICKED / CANCELLED`。同一任务不部分记数。 |
| 运单 | `CREATED → IN_TRANSIT / DELIVERED`，`IN_TRANSIT → DELIVERED`。只接受本地事件 `IN_TRANSIT / DELIVERED`；在途可多次登记。同事件号同内容重放成功，晚于或等于最新接受时间才能登记新事件，签收后拒绝任何新的事件号，`delivered_at` 永不被覆盖。 |

**订单详情的两个摘要：** `fulfillmentStatus`（`NOT_STARTED / SHORTAGE / CANCELLED / READY_TO_PICK / PICKING / READY_TO_DISPATCH / PARTIALLY_DISPATCHED / DISPATCHED`）和 `deliveryStatus`（`NOT_STARTED / AWAITING_SHIPMENT / IN_PROGRESS / DELIVERED`）。

- 履约摘要按顺序判定：取消/缺货优先；未路由 `NOT_STARTED`；已出库量等于原单需求总量 → `DISPATCHED`；大于 0 → `PARTIALLY_DISPATCHED`；所有履约已拣 → `READY_TO_DISPATCH`；有任一已拣任务 → `PICKING`；其余 → `READY_TO_PICK`。
- 运输摘要：无已出库单 → `NOT_STARTED`；全部应发数量已出库且所有履约运单都签收 → `DELIVERED`；有出库但一张运单都没建 → `AWAITING_SHIPMENT`；其余运输 `IN_PROGRESS`。每仓单独展示“未出库 / 待建运单 / 在途”等，不因另一仓已有轨迹遮蔽。

## 6. 库存事务、路由与重试

### 6.1 通用规则

1. **库存以数据库为准。** `stock_balance.reserved = SUM(ACTIVE reservation.qty)`；按余额行从零累加 `delta_on_hand / delta_reserved` 必须与当前余额一致。暂存行 `reserved=0`。原订单行的全部履约行数量之和成功后必须等于其订购量，不按整单总和替代逐行核对。
2. **锁顺序：** HTTP 幂等键 → 一个业务根（PO 或订单；同一出库/拣货/取消先锁订单头）→ 所有涉及 SKU 行按主键升序逐行锁 → 余额与子单据。每个库存写入口都遵守。SKU 行锁是 V1 主动选择的粗粒度串行化，两个仓共享此锁，可覆盖“余额尚未建行”；用实际压测再决定换锁粒度。不要用 Redis 锁替代；查询不加这把锁。写命令统一 `READ COMMITTED`，库存计划只在拿齐 SKU 锁后读取。
3. 余额变更还需条件更新：预占 `on_hand-reserved >= qty`；释放 `reserved >= qty`；出库 `on_hand >= qty AND reserved >= qty`；上架源 `on_hand-reserved >= qty`。目标行唯一键支持原子累加。更新行数不符合预期则**整笔回滚**，不能捕获后当成功。
4. 库存变化、业务事实、操作审计及同一动作的 outbox（M4）在同一 MySQL 事务，不在事务内调用 MQ、Redis 或外部系统。常规技术故障返回可重试错误，不写 `SHORTAGE`。数据库死锁或锁等待超时可整笔在**新事务**内最多重试 2 次（总共 3 次），仍失败返回 `503 WRITE_BUSY`。

### 6.2 命令顺序与账变

| 命令 | 校验 → 事实 → 账变（同一事务） |
|---|---|
| 收货 | 锁 PO、SKU。采购行属于 PO 且库位是该仓 `RECEIVING`；条件累加 `received_qty` 禁止超收。插 receipt，暂存 `on_hand +q`、`RECEIVE(+q,0)`，重算 PO。 |
| 上架 | 由 receipt 找 PO；锁同 PO、SKU。目标 `PICKABLE` 且同仓；`put_away_qty + q <= qty`，并校验实际暂存余额足够。插 put_away，写 `PUT_AWAY_OUT(-q,0)` 和 `PUT_AWAY_IN(+q,0)`；仓总现存不变。 |
| 首次/重试路由 | 锁订单、相关全部 SKU。按原订单行排序，仓顺序 SH→GZ，库位顺序 `pick_priority, bin.id`；只读 `PICKABLE`。先计算**全单**分配计划，尚不改任何库存和履约。真缺货：同事务只写 `SHORTAGE`、缺口数组和审计。足量：按仓组履约、按行/位建预占、加 `reserved` 并写 `RESERVE(0,+q)`、订单置 `ALLOCATED`。M3 起同事务生成任务。 |
| 取消订单 | 锁订单，检查所有履约的状态和任务；若任一已拣或发货，拒绝且不能只释放另一个仓。对 ACTIVE 预占按原余额释放，写 `RELEASE(0,-q)`；履约和任务置取消、OMS 置取消。无预占的单仅改状态。 |
| 确认任务 | 锁订单；校验任务仓权限、履约非取消、任务 `PENDING` 且预占 `ACTIVE`。整条任务改 `PICKED`，按兄弟任务重算履约状态；`on_hand / reserved` 不动。 |
| 确认出库 | 锁订单、相关 SKU；该履约所有任务已完成且预占 ACTIVE。插 dispatch，按余额行合计减现存和预占、写 `DISPATCH(-q,-q)`、把预占改 `CONSUMED`、履约改 `DISPATCHED`。同一 `dispatch` 不重复产生。 |
| 建运单/登记轨迹 | 由真实 dispatch 建一张模拟运单。carrier 固定 `SIM`，trackingNo 服务端从 dispatchNo 确定性派生。履约存在但未出库返回状态冲突；已建运单返回原单。并发建单由 dispatch 唯一键兜底，冲突后回读同一事实。追加轨迹锁运单头，先查同事件号重放，再检验事件时间和状态，写事件并刷新状态。 |

**口径取舍：** V1 拣货后货在待出库区，但不记待出库独立库位账，仍归源位账并持续预占。库存页写「账面现存，含待出库」，不能当货架实时定位。若以后引入拣后移位/撤拣，先重新设计流水和释放路径。

**M2 → M3 边界：** M2 尚无拣货任务。M3 的迁移按既有 `ACTIVE` 预占补齐 `PENDING` 任务、`RELEASED` 补 `CANCELLED`；`reservation_id` 唯一，迁移不重算或重置库存。以后建预占与任务在同事务，拣货界面按正常功能打开前验证无未补齐记录。

## 7. HTTP 契约

统一 `/api/v1`，JSON，成功写回**权威业务字段**与业务号，错误返回 `{code,message,requestId,details}`；列表 `{items,total,page,size}`，默认 `page=1,size=20`，上限 100，固定主键倒序（库存按库位展示），不把错误包成 HTTP 200。路径参数用业务号或 SKU/仓编码。`GET` 不改变业务状态；路由实际缺货是**已提交的业务结果**，返回 200 和 `routeStatus=SHORTAGE`、`shortages[{skuCode,requestedQty,availableQty,missingQty}]`，不是 409。

| 阶段 | 方法和路径（省略 `/api/v1`） | 请求/主要响应 |
|---|---|---|
| M1 | `GET /auth/csrf`、`POST /auth/login`、`GET /auth/me`、`POST /auth/logout` | 登录 `{username,password}`；me `{username,role,warehouseCodes}`。退出 204。 |
| M1 | `GET /catalog/warehouses`、`GET /catalog/skus`、`GET /catalog/warehouses/{warehouseCode}/bins` | 主数据选择项 `{code,name}`，库位额外 `type`；仓管按授权过滤。 |
| M1/2 | `GET /purchase-orders`、`GET /purchase-orders/{poNo}`；M2 `POST /purchase-orders` | 查询 `warehouseCode,status,poNo`。创建 `{warehouseCode,supplierName,lines:[{skuCode,quantity}]}`。详情有 `poNo,warehouseCode,status,lines[{lineNo,skuCode,orderedQty,receivedQty,remainingQty}]` 和 `receiptNos`。 |
| M1 | `POST /purchase-orders/{poNo}/receipts`、`GET /receipts/{receiptNo}` | `{lineNo,binCode,quantity}` → `{receiptNo,poNo,lineNo,skuCode,warehouseCode,binCode,quantity,putAwayQty,remainingQty,putAways}`。 |
| M1 | `POST /receipts/{receiptNo}/put-aways` | `{destinationBinCode,quantity}` → `{putAwayNo,receiptNo,quantity,remainingQty}`。从收货详情回读。 |
| M1 | `GET /inventory/balances`、`GET /inventory/movements` | 通用过滤 `warehouseCode,skuCode,binCode`；流水另 `bizNo`。余额返回 `onHand,reserved,available`（暂存位 0）、binType；流水返回动作、前述增量/余额、单号与时间。 |
| M2 | `POST /orders`、`GET /orders`、`GET /orders/{orderNo}` | 创建 `{channel,channelOrderNo,paidAt,lines:[{skuCode,quantity}]}`；列表过滤 `channel,channelOrderNo,orderNo,routeStatus`。详情 `{orderNo,channel,channelOrderNo,paidAt,routeStatus,shortages,fulfillmentStatus,deliveryStatus,lines,fulfillments,timeline,allowedActions}`。首创 201，渠道同内容跨用户重放 200。 |
| M2 | `POST /orders/{orderNo}/route`、`POST /orders/{orderNo}/cancel` | 路由空 body；取消 `{reason}`（非空，最多 200 字）。返回订单详情。 |
| M3 | `GET /fulfillments`、`GET /fulfillments/{fulfillmentNo}` | 过滤 `warehouseCode,status,orderNo`；详情含 `{fulfillmentNo,orderNo,warehouseCode,status,lines,tasks,dispatch,shipment,allowedActions}`。任务显示 `taskNo,skuCode,binCode,quantity,status`。 |
| M3 | `POST /fulfillments/{fulfillmentNo}/tasks/{taskNo}/pick`、`POST /fulfillments/{fulfillmentNo}/dispatch` | 两命令空 body；前者回履约详情，后者 `{dispatchNo,fulfillmentNo,dispatchedAt}`。 |
| M3 | `POST /fulfillments/{fulfillmentNo}/shipment`、`GET /shipments`、`GET /shipments/{shipmentNo}` | 建单空 body；列表过滤 `warehouseCode,status,fulfillmentNo,shipmentNo`。详情 `{shipmentNo,dispatchNo,fulfillmentNo,warehouseCode,carrier,trackingNo,status,events}`。 |
| M3 | `POST /shipments/{shipmentNo}/events` | `{sourceEventNo,type,occurredAt}` → 运单详情。`occurredAt` 不早于出库时间；事件时间相等按提交顺序接受，仍不可回退。 |
| M4 | `GET /operations/summary`、`GET /operations/outbox`、`POST /operations/outbox/{eventId}/retry` | 摘要 `{shortageCount,unshippedFulfillmentCount,failedEventCount,asOf}`；事件至少显示类型、dispatchNo、状态、尝试次数和失败摘要；重试仅允许 FAILED，成功后读回真实状态。 |

补充字段形状：订单 `lines[{lineNo,skuCode,orderedQty,reservedQty,pickedQty,dispatchedQty}]` 中 reservedQty 是**当前 ACTIVE 预占**，出库后归零；`fulfillments[{fulfillmentNo,warehouseCode,status,lines,dispatch,shipment}]`，履约 `lines[{orderLineNo,skuCode,quantity,reservations}]`，预占 `reservations[{reservationNo,binCode,quantity,status}]`，`timeline[{type,bizNo,warehouseCode,occurredAt}]`。可选事实未发生返回 `null`，集合未发生返回 `[]`；M2 没有拣货、出库/运单功能时仍遵守此形状，未发生数量为 0。

采购、收货、订单、履约、任务、运单详情返回各自的 `allowedActions`；只返回当前角色、资源状态和已交付阶段都允许的动作。前端据此决定按钮，拒绝原因以服务端为准。动作码为 `RECEIVE, PUT_AWAY, ROUTE, CANCEL, PICK, DISPATCH, CREATE_SHIPMENT, ADD_TRACKING_EVENT`。OPS 汇总中 `shortageCount` 数缺货订单，`unshippedFulfillmentCount` 数状态未出库且未取消的履约单，`failedEventCount` 数发布失败 outbox（不含消费死信）。

HTTP 错误口径：`400 INVALID_INPUT`（字段格式、数量、重复 SKU），`401 UNAUTHENTICATED`，`403 FORBIDDEN`（角色、仓或 CSRF），`404 NOT_FOUND`，`409 IDEMPOTENCY_CONFLICT / CHANNEL_ORDER_CONFLICT / OVER_RECEIPT / PUT_AWAY_EXCEEDS_RECEIPT / INVALID_STATE / TRACKING_EVENT_CONFLICT / TRACKING_EVENT_OUT_OF_ORDER`，`503 WRITE_BUSY`。列表过滤无权资源，单笔拒绝不暴露资源详情。未列出的技术故障 500，不暴露栈和 SQL。首次建 PO、收货、上架、接渠道单、确认出库、建运单是 201；轨迹登记与其他命令变更 200；业务重放回读 200，原 HTTP 请求键重放保留原状态码。

**幂等协议：** 除登录/退出外所有业务 POST 必须带 `Idempotency-Key`（客户端每个新动作生成 UUID），作用域 actor + operation；指纹覆盖规范化路径标识和 JSON 业务值，不包含传输/客户端时间戳（业务 `paidAt/occurredAt` 除外）。团队锁定 JSON 稳定排序；无法合法解析先 400。相同键/同指纹重放已提交的原 HTTP 结果；不同指纹 409。先鉴权，再处理重放。幂等凭证首次插入并完成命令后同事务提交；并发唯一键冲突处理在新事务读取已提交结果，不吞回滚。路由返回缺货后用户主动再路由必须发**新键**；网络重试使用原键。

另有业务幂等保护，防换键重复承诺：渠道同 `(channel,channelOrderNo)` 比对排序后的业务内容（接单时间不参与，paidAt 归一到 UTC 毫秒）；已有履约路由/已经确认的同一任务/已有出库事实/已有运单回同一事实；已取消的同原因回当前单，不同原因 409；轨迹按 `(shipmentId,sourceEventNo)` 比较 type/occurredAt。收货和上架允许同一单据多次相同数量，**必须用原请求键区分重试和下一次作业**，不能靠比较数量猜重复。

## 8. 权限、事件和运行

### 8.1 权限与可观测性

- OPS（运营）：全仓读，建 PO/接单、路由、取消、建模拟运单和写轨迹、重试事件；不代仓管确认收货/上架/拣货/出库。WAREHOUSE（仓管）：仅授权仓的采购/收货/库存/履约读，能收货、上架、拣货、出库；可在履约详情看关联订单号，不访问跨仓 OMS 详情或运输接口。SUPPORT（客服）：只读订单全链、运单，不写入。三角色都能使用登录、会话相关接口。权限在服务端以资源所属仓判定，不能信任客户端传的仓或 actor。
- Spring Security 同源 session cookie，前端 dev 代理转发 `/api`，不开放任意 CORS；密码 BCrypt，cookie `HttpOnly`、`SameSite=Lax`（HTTPS 部署才开 `Secure`，本地 loopback HTTP 明确例外），登录轮换会话，服务端退出销毁。CSRF cookie/header 只承载 CSRF token，登录/退出同样验证，登录后刷新 CSRF token。不把 session/密码放 localStorage 或日志。
- 写请求生成或接收经校验的 `X-Request-Id`，日志/响应可互查。关键操作写 `operation_log`，审计来源 `user:<id>` 或 `system:shipment-consumer`；记录出库号/事件号、前后状态和数量，不记凭据。Actuator 对普通访问仅开放健康总状态；详细指标只对本机或受权访问。

### 8.2 M4：只做一条可验证的消息链

`dispatch` 事务内写 `FulfillmentDispatched.v1` outbox（`dedupe_key=FulfillmentDispatched.v1:<dispatchNo>`）。消息包含 `{eventId,eventType,occurredAt,dispatchNo,fulfillmentNo,warehouseCode}`，稳定 ID、不传个人数据。M4 迁移后无论 Broker 是否可用、投递器是否启用，新出库都写 outbox；关闭投递器只会保留待发，不丢事件。消息不是出库的真值；消费者重新读取并校验 dispatch，调用和 OPS 手工创建同一个用例。

投递：事件初态 `PENDING`，到 `next_attempt_at` 才可认领。短事务认领到 `PUBLISHING`（`FOR UPDATE SKIP LOCKED`，每次认领生成 token，租约 2 分钟），提交后发 RocketMQ；确认或失败都按 `event_id + claim_token` 更新，过期工作者不能覆盖新认领。失败回 `PENDING`（按 2s、5s、15s、60s 退避），第 5 次失败进入 `FAILED`；认领超时按一次失败处理，避免永久占用。失败可由 OPS 重试，使用原 eventId，保留审计，重置本轮计数。Broker 暂停/恢复不影响 PO、库存、出库；正式 M4 验收必须真跑 Broker，不用日志当成功。M3 已有出库不自动补写历史事件，已有运单不变、未建单仍可手工建；升级后新出库才进入消息链。

消费：在一个事务里写 inbox 去重并建运单，事务提交后才 ACK；消费异常不确认，由 Broker 重投，耗尽后在死信队列留证据。**outbox 的 `PUBLISHED` 只等于 broker 确认**，OPS 从订单详情或 inbox 看运单是否真正生成；消费重试耗尽时可按单号人工调用相同建单用例补偿，不创建第二条出库事实。发布成功但写 PUBLISHED 前崩溃允许重发，不能承诺恰好一次。

Redis：仅缓存 OPS 汇总，TTL 30 秒、失败快速回读 MySQL；不缓存库存判断、权限、跟踪详情和命令。默认启动 M0–M3 不需要 Redis/MQ，M4 Compose profile 按需启动，端口冲突先报告。

### 8.3 工程和发布要求

- `frontend/` Vue 3 + TypeScript + Vite + Element Plus（建议 npm，一套锁文件）；`backend/` Java 17 + Spring Boot + MyBatis + Flyway（Maven Wrapper）；根目录 `compose.yaml`、`.env.example`、`./start.sh`、`./stop.sh`、`README.md`、`docs/`。应用端口默认 `8090`，前端 `5180`，新 MySQL `43307` / 库 `home_fulfillment`；检查端口并只绑定 loopback。前次 Docker daemon 不可连接，M0 先验证，不能借旧库。
- 同一仓内维护迁移与只读验收 SQL。生产结构和本地种子独立，种子只在显式本地演示初始化时执行、重复运行不覆盖。迁移账号与应用账号分开，运行时不用 root；用户演示密码来自本地 env，不提交。
- 默认不删卷、不启动全局服务、不清库、不推送。迁移前做备份；回滚应用需确认兼容现有 schema，迁移失败用隔离环境复现，禁止手改旧环境表结构；M4 恢复验证用另一隔离库。
- 每阶段后端 `./mvnw verify`、前端 `npm ci && npm run typecheck && npm run build`，关键业务用真实 MySQL 的独立事务测试，浏览器走真实后端。版本锁定、端口与证书等可逆工程细节由实施者决定并记录；公网安全、性能容量不因本地验收而宣称通过。

## 9. 验收矩阵：DeepSeek 不得用 Mock 或“接口通了”替代

主样例 `HOME-BOX-01`：上海采购种子单 `PO-HOME-001` 订 10，库位 `RECV-01 → A-01-01`，实际收 8、上架 8；M2 由 OPS 在广州新建采购单 4，仓管用广州的 `RECV-01 → A-01-01` 实际收/上架 4。`DIRECT/SAMPLE-001` 已支付订单订 10，分上海 8 广州 2。下面余额按 `现存/预占` 表示，上海暂存 M1 初始 0，两仓可拣都初始 0（可以无余额行）。种子包含两仓、四库位、主样例 SKU 和四名演员（OPS、上海仓管、广州仓管、SUPPORT）；不提前种收货、锁货、运单或出库。业务号由实际运行或 fixture 取得。**缺货、取消、并发等破坏主样例的测试进独立库，不重置主样例。**

| ID | 场景 | 必须观察到 |
|---|---|---|
| A01 M0 | 空库启动、停启 | 迁移成功、健康通过、新库连通；重启不重复或覆盖演示数据，旧库无写入。 |
| A02 M1 | 真浏览器收 8，再上架 8 | PO 部分/欠 2；上海暂存 `0/0→8/0→0/0`，可拣 `0/0→0/0→8/0`，上架流水 −8 与 +8；同 receiptNo、putAwayNo 可查。 |
| A03 M1 | 重复、超收、越仓 | 同键同内容返回原事实；同键异内容 409；再收 3 超收；两个并发上架合计不能超过同 receipt 剩余；拒绝其他仓的仓管。失败前后核对余额和流水，不只断言 HTTP。 |
| A04 M1 | 写库中途失败 | 测试让库存记账在已写单据之后失败，确认单据、余额、流水、成功幂等凭证一起回滚；重试成功一次。 |
| A05 M2 | `DIRECT/SAMPLE-001` 订 10 | 两履约 `SH=8,GZ=2`，余额 `SH 8/8,GZ 4/2`；总现存 12、可用 2；每个履约行回指原单行。跨渠道可用相同外部号但不共用订单；同渠道同内容不重复建单。 |
| A06 M2 | 多行一行缺货；补货重试；未拣取消 | 首次只写 SHORTAGE，哪怕第一行能分也无预占/履约；补货后新请求键重试成功。另选测试单取消：缺货取消不改库存，已预占未拣取消仅释放两仓预占。制造技术异常则事务回滚、不伪造缺货。 |
| A07 M2 | 两渠道同时抢最后一件 | 用独立测试夹具和两个并发事务；一个分配成功，另一个 SHORTAGE；不存在两个成功预占同一件货，也无负库存。 |
| A08 M3 | M2 老单升级后双仓拣货/出库 | M2 同笔单升级后预占/余额不变、任务补齐且不重复；拣货前后余额不动；先 SH 出库 `SH 0/0,GZ 4/2`，整单部分已发；再 GZ 出库 `SH 0/0,GZ 2/0`，整单全部已发。重复出库不扣第二次；拣货未完不能出库。 |
| A09 M3 | 整单取消与确认拣货竞态 | 未拣时释放但不减现存；任一仓拣货/出库后整单取消失败，另一仓不被局部释放。并发只可能取消或拣货一方生效。 |
| A10 M3 | 运单、轨迹、同号回溯 | 未出库禁止建单；已出库重复建返回同一运单；两履约均签收才整单运输完成，迟到事件不能回退。客服按渠道号看见每仓进度，且不能操作写接口。 |
| A11 M4 | MQ 停服/恢复、重复投递、老运单保留 | M3 老记录仍可读，不自动补发布；用一张新的已预占单（可用主样例剩余广州 2 件，另录 `DOUYIN/SAMPLE-002` 订 1 件）实测。停 Broker 后出库仍成功、事件可查；恢复后自动建运单（若已 FAILED 用原事件号人工重试），重复投递不建第二张，人工建单/自动消费并发同样只建一张。消费失败与发布失败证据分开。 |
| A12 M4 | Redis 不可用、备份恢复 | 工作台交易和详情可用、汇总回源；用隔离库恢复，在恢复库用原单号查到快照里的订单/余额/运单。 |

**SQL 交验必须覆盖：** PO 查询串 `po_no → purchase_order_line → receipt → put_away → stock_movement`；订单查询串 `(channel,channel_order_no) → channel_order_line → fulfillment_line / fulfillment_order → stock_reservation / pick_task → dispatch → shipment / tracking_event`。用实际 Java 方法定位至少 4 个写入点：收货、上架、预占、出库。不得在 SQL 中直接造流水来通过浏览器验收。

首轮 DataGrip 查询模板（**按设计，尚无新库可执行**；迁移后把实际业务号填入）：

```sql
SELECT p.po_no,p.status,l.line_no,l.ordered_qty,l.received_qty,
       l.ordered_qty-l.received_qty AS remaining_qty,
       r.receipt_no,r.qty,r.put_away_qty,a.put_away_no,a.qty AS moved_qty
FROM purchase_order p
JOIN purchase_order_line l ON l.po_id=p.id
LEFT JOIN receipt r ON r.po_line_id=l.id
LEFT JOIN put_away a ON a.receipt_id=r.id
WHERE p.po_no='PO-HOME-001'
ORDER BY l.line_no,r.id,a.id;

SELECT m.biz_no,m.movement_type,w.code AS warehouse_code,b.code AS bin_code,
       m.delta_on_hand,m.delta_reserved,m.on_hand_after,m.reserved_after
FROM stock_movement m
JOIN stock_balance s ON s.id=m.balance_id
JOIN bin b ON b.id=s.bin_id
JOIN warehouse w ON w.id=b.warehouse_id
WHERE m.biz_no IN ('填实际收货号','填实际上架号')
ORDER BY m.id;
```

全链 DataGrip 查询模板（M3 起，`LEFT JOIN` 保留未发生的阶段，**查证用，不汇总**；一个订单行多预占/轨迹会展开多行）：

```sql
SELECT o.order_no,o.channel,o.channel_order_no,o.route_status,
       l.line_no,k.code AS sku_code,l.ordered_qty,w.code AS warehouse_code,
       f.fulfillment_no,f.status AS fulfillment_status,fl.qty AS fulfillment_qty,
       b.code AS bin_code,r.reservation_no,r.qty AS reserved_fact_qty,
       r.status AS reservation_status,t.task_no,t.status AS pick_status,
       d.dispatch_no,s.shipment_no,s.status AS shipment_status,
       e.source_event_no,e.type AS event_type,e.occurred_at
FROM channel_order o
JOIN channel_order_line l ON l.order_id=o.id
JOIN sku k ON k.id=l.sku_id
LEFT JOIN fulfillment_line fl ON fl.order_line_id=l.id
LEFT JOIN fulfillment_order f ON f.id=fl.fulfillment_id AND f.order_id=o.id
LEFT JOIN warehouse w ON w.id=f.warehouse_id
LEFT JOIN stock_reservation r ON r.fulfillment_line_id=fl.id
LEFT JOIN stock_balance sb ON sb.id=r.balance_id
LEFT JOIN bin b ON b.id=sb.bin_id
LEFT JOIN pick_task t ON t.reservation_id=r.id
LEFT JOIN dispatch d ON d.fulfillment_id=f.id
LEFT JOIN shipment s ON s.dispatch_id=d.id
LEFT JOIN tracking_event e ON e.shipment_id=s.id
WHERE o.channel='DIRECT' AND o.channel_order_no='SAMPLE-001'
ORDER BY l.line_no,w.route_priority,r.id,e.occurred_at,e.id;
```

验收时另按余额行校验两条恒等式：① `on_hand / reserved` 分别等于该行流水两种变化之和；② `reserved` 等于该行 ACTIVE 预占的量。两组汇总各自先 `GROUP BY balance_id`，再与余额表关联，避免流水 × 预占的多对多重复计数。到 M4 同时记录 `dispatchNo → eventId → inbox → shipmentNo`，有 `PUBLISHED` 无运单不算消费通过。

## 10. 实施与变更门禁

1. **首轮只做 M0–M1。** 到 A01–A04，有真实浏览器证据、同号 SQL、Java 路径、测试结果后停止，等用户验收。M2 再做 A05–A07，M3 做 A08–A10，M4 做 A11–A12。每段在同库演示数据上延伸，用独立测试库反复执行异常测试。
2. 本文件是新仓的唯一维护版，旧「一条线」留原始基线。续作先读[项目交接](../HANDOFF.md)和 [Task](TASK-v1.md)。新仓的 `CONTEXT.md` 如需建立，从 §2 摘词，不重新定义术语。
3. **不得自行改：** 扣库时点、整单路由策略、取消条件、状态所有权、数据粒度、幂等范围、已约定的外部接口、MQ 不能成为主链依赖。若需改，先给用户：原规则 → 新规则 → 业务/数据迁移影响 → 哪些验收会变，获确认后改同一份规格并写版本记录。
4. 可以自行决定：库表索引补充、不改变契约的内部命名/包结构、UI 样式和兼容版本。规格未定义的采购撤销、运单异常、开单后编辑等不默认实现。

**版本记录：** 1.0 首次定稿；1.1 补全 F01–F12、跟踪查询、阶段升级验收及跨会话协议，未扩大 V1 功能边界。实际实施停点只看 [HANDOFF](../HANDOFF.md) 或新仓的对应文件，不从规格版本猜进度。

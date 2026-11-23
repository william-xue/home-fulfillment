# M4 接管卡｜出库成功和自动建运单是两件事

1. 出库事实、扣账与 Outbox 同事务；Broker 挂了也能完成出库。投递确认和消费成功分别看 `outbox_event`、`consumer_inbox` 和 `shipment`。
2. [`OutboundCommands.dispatch()`](../../../backend/src/main/java/com/homefulfillment/fulfillment/OutboundCommands.java) → [`OutboxStore`](../../../backend/src/main/java/com/homefulfillment/events/OutboxStore.java) → [`MqWorkers`](../../../backend/src/main/java/com/homefulfillment/events/MqWorkers.java) → [`DispatchConsumer`](../../../backend/src/main/java/com/homefulfillment/events/DispatchConsumer.java) → `Shipments.ensureForDispatch()`。
3. 发布后进程若没来得及更新 MySQL 会再次投递；不能删掉 Inbox 或 `shipment.dispatch_id` 唯一约束。也不能把 MQ 发送放回出库事务，或者把 Redis 缓存当库存依据。

| 现象 | 下一步 |
|---|---|
| `FAILED`，没有 Inbox | 查发布摘要与 Broker，恢复后用原事件号重试；`claim_token` 防旧工作者覆盖新认领。 |
| `PUBLISHED`，没有运单 | 查消费记录和日志中的 `eventKey/reconsumeTimes`；重投耗尽看 RocketMQ 消费组死信。必要时手工建单，同一个出库不会建两张。 |
| Redis 停了或运营数字暂未变 | [`OperationsSummary`](../../../backend/src/main/java/com/homefulfillment/operations/OperationsSummary.java) 单点回源，统计时间最多缓存 30 秒；订单、发货、轨迹都不查 Redis。 |
| 核对升级或恢复后的原单 | [同号查询](../../../infra/mysql/verify-m4.sql)；看 `tests/m4.py` 的隔离恢复，不往原库导入。 |

新依赖只为 RocketMQ 客户端和 Redis 读缓存；缺少中间件时只停发布/缓存，不替换数据库或引入另一套库存逻辑。

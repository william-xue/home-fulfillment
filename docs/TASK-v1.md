# TASK｜V1 交付与验收

- 规范：[V1 1.1](v1-spec.md)，总功能 F01–F12、验收 A01–A12。
- 实施：[M0–M4 计划](implementation-plan.md)；恢复从 [HANDOFF](../HANDOFF.md) 进入。
- 目标：在独立工作台用同号走完采购入库到双仓发货签收；同时留下能独立实现、能面对面试追问的真实能力证据。工程记录与个人学习记录分开。

| 阶段 | 状态 | 当前证据 |
|---|---|---|
| M0 / A01 | 待用户验收 | 正式 Docker Compose 路径已实跑：空业务 schema 迁移 1 成功，`./stop.sh` 后 `./start.sh --seed` 回读迁移仍为 1、种子仍为 1 个 SKU / 2 个仓 / 4 个库位。真实 Chromium 显示「后端与数据库已就绪」。 |
| M1 / A02–A04 | 用户已走单，确认继续 | 模型 A02–A04 已通过；用户操作后主库回读订 10、收 1、上架 1、余欠 9。用户指示进入下一环节；不据此评定学习掌握。 |
| M2 / A05–A07 | 模型通过，用户授权继续 | 真实浏览器与隔离 Compose MySQL A02–A07 通过。用户明确要求开始 M3；主工作台未查到用户已录入的渠道单，不记作独立走单或掌握。 |
| M3 / A08–A10 | 模型通过，用户授权继续 | V3→V4、浏览器、真实 MySQL 通过；用户明确授权开始 M4。主工作台未观察到本人出库走单，不计为个人掌握。 |
| M4 / A11–A12 | 待用户验收 | 正式 Compose、浏览器、隔离恢复通过；主工作台 V5 已运行，原采购、库存与订单回读不变。 |

本机首次核实：Java 17.0.14、Maven 3.9.9、Node 24.14.0、npm 11.9.0、Compose 5.6.1。`./mvnw -B verify` 通过（M0 无 Java 单元用例）；前端 `npm run typecheck` / `npm run build`、Shell 语法与 Compose 配置校验通过。隔离 MySQL 与验证进程已停止，未用旧库。Git 为新仓，无提交，无远程写入。

2026-11-22 正式证据：`http://127.0.0.1:5180/`，`/api/health` 与 8090 的 `/actuator/health` 都返回 `{"status":"UP"}`；`docker compose ps` 显示 `mysql:8.4.5` healthy、`127.0.0.1:43307`。用 `hf_app` 对 `home_fulfillment` 执行 [`verify-m0.sql`](../infra/mysql/verify-m0.sql)，停启前后结果完全一致。初次拉镜像曾超时；容器首开时挂载脚本报 126，改为 `start.sh` 在数据库健康后通过 stdin 幂等建立业务账号，保留原卷后复测通过。未重置或连接旧库。

Flyway 11.7.2 在 MySQL 8.4.5 上打印版本兼容提醒；本轮原生隔离实例中实际迁移 V1 和重启校验都成功。后续新增迁移仍须用本项目真实 MySQL 验证，不为消掉提示关闭 Flyway。

## M1｜入库链路证据

- **A02**：最终复跑在隔离 Compose MySQL 8.4.5 用 Chromium 登录 → `PO-HOME-001` 订 10、收 8 → 收货号 `RC-66caf2f3-3157-495a-8b85-1c8c75e58808` → 上架号 `PA-89895a0f-c738-45ab-85d6-09393ef4d635`。采购 `PARTIAL`、余欠 2；页面暂存 `8 / 0 / 0`（现存 / 预占 / 可分配），上架后暂存归零、可拣位现存 8，双边流水 `−8/+8`。模拟收货已提交但响应丢失，刷新后用原请求键重试，仍只收一笔；375 px 下台账不撑宽页面。用 `hf_app` 执行 [verify-m1.sql](../infra/mysql/verify-m1.sql) 核对同号流水、库位和操作者。上述业务号属于已清理的测试实例，不是主工作台数据。
- **A03**：并发同键同内容返回原结果，异内容 409；再收 3 返回 `OVER_RECEIPT` 且账不变；并发尝试分别上架 5，只允许一笔成功，同键重试不多上架。未登录、跨仓、角色、撤仓权限后重放、CSRF、退出、非法数量和缺少请求键检查均通过。
- **A04**：隔离库在写流水时用触发器注入失败，回查采购、收货、库存、流水与幂等凭证均未残留；解除故障后用原键成功。
- **命令**：`(cd backend && ./mvnw -B verify)`（无独立 Java 单测）；`(cd frontend && npm run typecheck && npm run build)`；`python3 tests/m1.py --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"`。浏览器首跑因文字选择器匹配多节点误报，改为定位按钮后复跑通过。
- **写入**：[`InboundCommands.receive()` / `putAway()`](../backend/src/main/java/com/homefulfillment/inbound/InboundCommands.java) 是单据入口；[`InventoryLedger`](../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java) 只负责库存账，[`CommandJournal`](../backend/src/main/java/com/homefulfillment/web/CommandJournal.java) 保持业务与幂等同事务。
- **主工作台**：迁移前备份 `.run/pre-m1-WnyxwQwe`（权限 600，未提交；仅验证导出完整，未做恢复演练）。原演示卷升级 V2、连续两次 `--seed` 后仍为四账号、一采购单、零收货/上架；`PO-HOME-001` 订购 10 / 已收 0，留给用户亲手走单。页面截图 `.run/m1-login.png`、`.run/m1-purchase.png`（已检查）；前后端 8090/5180、MySQL 43307 持续运行。
- **收尾复验**：幂等指纹按规格改为业务路径和字段的排序 JSON。升级至 Vue 3.5.46、Vite 7.3.8 后 `npm audit --audit-level=high` 返回 0 条；最终浏览器与 MySQL 回归通过，结果在 `.run/m1-final.log`；主工作台已重启到本轮版本，`/api/health` 为 `UP`。只验本地，不代表生产安全或容量通过。
- **演示登录**：`./start.sh --seed` 在 `127.0.0.1` 开发服务上预填 `warehouse-sh` 和本机演示密码。真实 Chromium 验证无需输入即可点登录到采购单；跨源及非预期主机被拒绝。生产构建已检查不含预填入口或密码，正常登录接口未改。

## M2｜已支付接单、双仓分配

- 工程范围为 F03 采购建单、F06–F08；不造拣货任务，不扣现存，不建运单。先运行只含 M1 的旧 jar，M2 首个采购写入验收如期失败（`.run/m2-red.log`）。
- **A05**：最终复跑用 Chromium 在隔离库从页面为广州建单、收货和上架 4；从页面录入 `DIRECT/SAMPLE-001`（内部号 `CO-3b74375e-ba85-4368-9317-0e32caed7094`）并路由。SQL 回读原单行关联、上海 `8/8`、广州 `4/2`（现存/预占），总现存 12、可分配 2；跨渠道同号不串，同渠道归一化时间后同内容回原单，异内容冲突。该内部号在隔离库，主工作台未预种渠道订单。
- **A06**：多行缺货不产生部分履约/预占；补货后原键回原结果，新键重路由成功。缺货取消不改账，已预占取消只释放；故意让第二仓流水写失败，第一仓写入也回滚，订单仍是待路由；恢复后原键可成功。
- **A07**：两个渠道并发抢最后一件，一张 `ALLOCATED`，一张 `SHORTAGE`。真实 MySQL 查账等式成立（现存/预占等于流水增量，预占等于有效预占明细）。
- 入口：[`OrderCommands.route()` / `cancel()`](../backend/src/main/java/com/homefulfillment/fulfillment/OrderCommands.java)、[`InventoryLedger.reserve()` / `release()`](../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java)；同号 [verify-m2.sql](../infra/mysql/verify-m2.sql)。主库已有的 `PO-HOME-001` 实际操作未被测试改动；迁移前备份 `.run/pre-m2-ag38g0k7`（权限 600，导出校验，未做恢复）。
- **命令与运行**：后端 `./mvnw -B verify`、前端 `npm run typecheck && npm run build`，`python3 tests/m1.py --m2 --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"` 均通过；A02–A07 输出在 `.run/m2-final.log`。主工作台 V1–V3 迁移成功，迁移前后的 `verify-m1.sql` 输出一致，`/api/health` 为 `UP`。运营预填和录入页已实测，未提交测试订单到主库。

## M3｜拣货、出库、模拟运单

- 范围为 F08 的拣货闸门、F09–F10。不同仓操作先锁同一订单；库存仍只在出库动作扣。交付不包含真实承运商、消息队列或缓存。
- 旧 M2 jar 的目标测试先在履约读接口报 404（`.run/m3-red.log`）；升级后的 A08–A10 接口/SQL 首轮通过（`.run/m3-api.log`）。迁移前主库已导出 `.run/pre-m3-cYWmYDq4`（权限 600，校验导出完成、未做恢复）。
- **A08**：先用保存的真实 M2 jar 产生预占与历史取消；同隔离卷升 V4 后，账和预占原样，`ACTIVE → PENDING`、`RELEASED → CANCELLED`，再次启动后任务号完全一致。Chromium 操作两仓拣货、出库，拣货时库存与流水不动；上海先出后主订单为「部分已发」，广州出后为「全部已发」。订单 `CO-554f24f1-2f4f-46af-8543-bcbd306c65f7`，出库 `DS-715f6dd8-74de-4103-b62f-c179ec4ec195`、`DS-d43d094c-d9d4-4590-a876-8a8bff8eb6d0`，最终上海 `0/0`、广州 `2/0`（现存/预占）。
- **A09**：同一已预占双仓单并发「拣货/取消」，浏览器回归的接口竞态结果为 `409/200`；已拣的另一张双仓单取消被拒且不产生局部释放。未拣取消后任务 `CANCELLED`、仅释放预占。
- **A10**：未出库拒建；重复建单回原运单。上述主订单对应 `TS-044337d3-a2b2-416b-b602-99312800cfd6`、`TS-9afc24a6-633b-466e-8479-eea21bcec86e`。有一仓签收仍非整单签收；两仓签收后客服浏览器按订单回看全链与运单，没有登记按钮。迟到和新终态事件被拒，原事件重放可回读，运输操作不修改库存。业务号都来自已清理隔离库。
- 写入证据：[`OutboundCommands`](../backend/src/main/java/com/homefulfillment/fulfillment/OutboundCommands.java)、[`InventoryLedger.consume()`](../backend/src/main/java/com/homefulfillment/inventory/InventoryLedger.java)、[`Shipments.addEvent()`](../backend/src/main/java/com/homefulfillment/tms/Shipments.java)；同号查询 [verify-m3.sql](../infra/mysql/verify-m3.sql)。
- 命令：`(cd backend && ./mvnw -B verify)`、`(cd frontend && npm run typecheck && npm run build)`、`python3 tests/m1.py --m3 --upgrade-from .run/m2-before-v4.jar --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"`；浏览器日志 `.run/m3-browser.log`。新库 `python3 tests/m1.py --m3` 也已通过（`.run/m3-fresh.log`），额外覆盖同仓多任务未拣完拒绝出库、两个并发建单请求只建一张。
- 主工作台保持运行，Flyway 1–4 成功，健康 `UP`，原有采购/余额/订单 SQL 升级前后相同；未造渠道订单、出库或运输记录。浏览器确认上海仓管、广州仓管、运营、客服四个预填入口都可用。

## M4｜可靠投递与恢复

- 范围 F11–F12：出库同事务记 Outbox，短事务租约认领，真实 RocketMQ 消费；后端 Inbox 去重、运单按出库唯一。运营 Redis 只缓存三个计数。未把 Broker 或 Redis 加入基础就绪门槛。
- `apache/rocketmq:5.3.0` 为 amd64；本机 Apple Silicon 用 Docker 模拟，Broker 已真实启动。Redis 7.4.9 镜像拉取曾 TLS/层下载超时，2026-11-23 已核实本机有 arm64 官方镜像，转回正式 Compose 验收。
- 迁移前备份 `.run/pre-m4-7bUiq2MX`（权限 600；仅核验导出，另做隔离恢复）。新接口与页面已落盘，Maven 构建、前端类型检查和构建通过。
- 初次隔离回归（真实 Broker、隔离原生 Redis）超时；已观测 V4→V5 老单不补发、停 Broker 时出库仍成功且写入事件，停在发送第 3 次后待重试。遗留的隔离卷已按项目名清理；未修改主库或其他本机 Redis。重跑时只提前测试库到期时间，不改应用退避。
- **A11（已实跑）**：`--upgrade-from-m3` 从同隔离卷升级旧出库，已有运单不变、未建者仍可手工建、旧出库没有自动入队。停真 RocketMQ 后，`DOUYIN/SAMPLE-002` 的内部号 `CO-7b371a9a-e70f-48d7-8836-05b5d45db5e4` 出库 `DS-69b44d74-b1a8-4ffb-8ad7-1782ff6b0e7f` 仍提交；事件 `7331b95c-06f5-4b69-bad9-3fcc2e674227` 五次真实发送失败。恢复 Broker 后 Chromium 在运营页点重试，原事件号发布、Inbox 只有一条、自动运单 `TS-b8a88079-e8d5-4828-bdf9-6f526c2ae7cc`。真实重复投递有消费者去重日志。另用隔离触发器制造消费写入失败：发布已确认而 Inbox/运单未落库；解除故障后手工建单与消费重试汇合到一张运单。未专门等到死信入队。
- **A12（已实跑）**：Redis 容器 TTL 在 1–30 秒内、缓存命中统计时间不变。真实停 Redis 后再创建并路由订单，详情可读、汇总约 0.32 秒回 MySQL，三计数一致。使用 `bash backup.sh` 把隔离源库快照恢复到第二个 Compose MySQL 容器，核对库存、outbox、Inbox，并通过恢复后端以客服沿原号查到同一运单。只验证本地快照，不是生产容灾或性能指标。
- **重跑**：`python3 tests/m1.py --m4 --upgrade-from-m3 .run/m3-before-v5.jar --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"`（`.run/m4-compose.log`）。V5 空库且不启动中间件也通过 A02–A10（`.run/m4-v5-fresh.log`）。[同号 SQL](../infra/mysql/verify-m4.sql)；入口 [`OutboxStore`](../backend/src/main/java/com/homefulfillment/events/OutboxStore.java)、[`DispatchConsumer`](../backend/src/main/java/com/homefulfillment/events/DispatchConsumer.java)、[`OperationsSummary`](../backend/src/main/java/com/homefulfillment/operations/OperationsSummary.java)。
- **主工作台**：2026-11-23 `bash backup.sh` 生成 `.run/backups/home-fulfillment-20261123-222650.sql`（权限 `0600`）；保留原卷升 V5，`verify-m1.sql` / `verify-m3.sql` 前后结果一致。`--seed --m4` 启动后 MySQL、RocketMQ Broker/Nameserver 和 Redis 全健康；Broker 注册 `127.0.0.1:41911`，Redis `PONG`，前后端健康 `UP`。真实浏览器登录 [运营页](http://127.0.0.1:5180/login?next=/operations) 能看汇总和列表；主库没有新造出库或事件。
- **最终包复验**：认领/消费事务明确采用 `READ COMMITTED` 后，`python3 tests/m1.py --m4 --upgrade-from-m3 .run/m3-before-v5.jar` 再次通过 A11–A12（`.run/m4-final-api.log`）；不同命令、事件和恢复都使用单独的隔离库，未替用户验收。

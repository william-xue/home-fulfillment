# 供应链业务工作台

家居电商案例，两渠道、两仓共享库存。它是供应链全栈实战项目，也是面试项目：用同一笔业务练习从需求、单据与状态到页面、Java、SQL、异常和验证。

- **开始/续上**：[HANDOFF.md](HANDOFF.md)
- **全部功能与规则**：[V1 设计规格](docs/v1-spec.md) · [M0–M4 实施计划](docs/implementation-plan.md)
- **学习与面试**：[摸底与练习路线](docs/learning.md) · [面试训练](docs/interview.md)

## 本机运行

需要 Java 17、Node 22.12+（或 Node 20 的 20.19+）、npm、Docker Compose。项目默认使用前端 `5180`、后端 `8090`、MySQL `43307`，全部绑定本机。只启动本项目 MySQL 容器；不使用旧 `wms_core` 或 Carfolio 的数据库。

```bash
docker info
./start.sh --seed
curl -fsS http://127.0.0.1:5180/api/health
```

打开 [工作台](http://127.0.0.1:5180/)。`./start.sh` 首次生成仅当前用户可读的 `.env`，打包后端、安装前端依赖、启动 MySQL，等待 Flyway 和后端就绪，再启动 Vite。`--seed` 显式补主数据、四个演示账号和种子采购单，不预写收货或上架，也不覆盖既有业务数据。`/api/health` 检查后端和数据库。

停止：`./stop.sh`（保留卷）；只停前后端：`./stop.sh --app-only`。若 Docker daemon 不可连接，先启动 Docker Desktop 再重试，不回退到本机已有库。端口冲突可在 `.env` 改本项目端口，别结束未知进程。日志在 `.run/backend.log`、`.run/frontend.log`；已有卷不要重新生成密码，改 `.env` 不会修改 MySQL 账号。

## A01 验证和代码入口

1. 启动后用 DataGrip 连 `127.0.0.1:43307`、数据库 `home_fulfillment`、用户名 `hf_app`，密码来自 `.env` 的 `HF_DB_APP_PASSWORD`。执行 [verify-m0.sql](infra/mysql/verify-m0.sql)：应看到本项目库名、成功迁移 `1` 至当前版本，以及主数据种子 `1 / 2 / 4`。
2. `./stop.sh` 后重新 `./start.sh --seed`，再查一次数量和迁移记录没有重复。查看 [Task](docs/TASK-v1.md) 区分已跑与未跑。
3. `/api/health` → Vite `frontend/vite.config.ts` → Spring Actuator → MySQL。迁移在 `backend/src/main/resources/db/migration/`；业务增量用后续版本，不能修改已应用的迁移。

## A02–A04｜用同一笔单验收 M1

1. 用默认本机地址访问时，`./start.sh --seed` 会在登录页预填 `warehouse-sh` 和演示密码，直接点「登录」即可。手动登录时密码取项目 `.env` 的 `HF_DEMO_PASSWORD`；另有 `ops`（全仓只读入库）、`warehouse-gz`（广州仓）、`support`（M1 无业务页）。预填仅限本机开发服务器，不包含在构建产物里。
2. 打开 [`PO-HOME-001`](http://127.0.0.1:5180/purchase-orders/PO-HOME-001)，订购 10，登记 8 到 `RECV-01`。采购单应为「部分收货」，余欠 2；收货流水 `+8`，暂存可分配为 0。
3. 从收货单上架 8 到 `A-01-01`，点「查双边流水」。同上架号对应源位 `−8`、目标位 `+8`；全仓现存仍为 8，可分配变成 8。DataGrip 跑 [verify-m1.sql](infra/mysql/verify-m1.sql)，用同号查采购、收货、余额、上架和审计。台账里的余额是库位的当前账，不是某张采购单专属的余额。
4. 后端写入入口是 `InboundCommands.receive()` / `putAway()`；数量条件在 `InboundMapper`，记账在 `InventoryLedger` / `StockMapper`。参见 [维护地图](.claude/maintain/MAINTAIN-MAP.md)。

复跑测试（需要 Python 3；创建随机名的隔离 Compose 库，结束后清理该库，不改主工作台）：

```bash
(cd backend && ./mvnw -B verify)
(cd frontend && npm ci && npm run typecheck && npm run build)
python3 tests/m1.py
# 已安装 gstack 时，用真实浏览器代替 A02 的 HTTP 操作：
python3 tests/m1.py --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"
```

## A05–A07｜渠道接单、双仓分配

1. 用 `warehouse-sh` 让上海可拣位累计有 8 件 `HOME-BOX-01`。运营 `ops` 在[采购单页](http://127.0.0.1:5180/purchase-orders)为广州建 4 件的单，再换 `warehouse-gz` 收货、上架 4 件。三个演示账号使用同一个本机演示密码。
2. 打开 [运营演示登录](http://127.0.0.1:5180/login?next=/orders/new)，已预填 `ops`。录入订单：选自营商城、填 `SAMPLE-001` 和 `HOME-BOX-01` 数量 10。提交只接单；点「执行路由」后才有上海 8 / 广州 2 两张履约单，总现存仍是 12。
3. 从订单详情记下 `CO-…` / `FO-…` / `RS-…`，用 [verify-m2.sql](infra/mysql/verify-m2.sql) 按渠道号查原单行、预占及流水；订单详情也可点预占号进入库存台账。
4. 失败验收用隔离库，不要拿后续要拣货的主样例做取消。`python3 tests/m1.py --m2 --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"` 复跑 A02–A07；省略 `--browser` 只走真实 HTTP + MySQL。写入入口见 [维护地图](.claude/maintain/MAINTAIN-MAP.md)。

## A08–A10｜仓管拣货、出库到签收

1. 先按 M2 路由一张有货的订单。在 [上海仓管出库作业](http://127.0.0.1:5180/login?next=/warehouse/outbound) 找相同 `CO-…` 的履约单；广州仓用 [广州仓管入口](http://127.0.0.1:5180/login?demo=warehouse-gz&next=/warehouse/outbound)。
2. 对每条任务按库位确认拣货，再点「确认出库」。拣货后余额不变，出库才同时减现存和预占。只发一个仓时，整单是「部分已发」；两仓都发才是「全部已发」。
3. 用 [运营运单入口](http://127.0.0.1:5180/login?next=/shipments) 打开「查看已出库待建单」，进履约详情建模拟运单并登记在途、签收。再用 [客服订单入口](http://127.0.0.1:5180/login?demo=support&next=/orders) 按渠道号查两仓状态与时间线。各入口自动预填演示账号与密码；不会自动登录或写作业。
4. [verify-m3.sql](infra/mysql/verify-m3.sql) 按渠道号关联 `CO → FO → PK → DS → TS` 并查出库流水。运输仅本机模拟；未接真实承运商或消息队列。

完整回归用 `python3 tests/m1.py --m3 --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"`。
若有升级前的 M2 jar，追加 `--upgrade-from .run/m2-before-v4.jar`，测试先用原 M2 jar 产生预占和历史取消，再对同一隔离库升级，比较余额/预占并验任务补齐。

## A11–A12｜自动建单、回源与恢复

```bash
./stop.sh --app-only                  # 如果当前已运行
./start.sh --seed --m4
docker compose --profile m4 ps
```

- 默认 `./start.sh --seed` 只需要 MySQL：新出库仍记录事件，但默认不开投递和缓存。`--m4` 额外启动 RocketMQ 名字服务 `49876`、Broker `41911`、Redis `46379`，只绑定本机。RocketMQ 5.3.0 镜像为 x86_64；Apple Silicon 上需要 Docker 的 amd64 模拟。
- [运营汇总入口](http://127.0.0.1:5180/login?next=/operations)已预填 `ops`。点出库事件的履约号查运单；`PUBLISHED` 只说明 Broker 已确认。`FAILED` 是**发布失败**，可以点「重新投递」；消费出错记录在后端日志的 `eventKey/reconsumeTimes`，重投耗尽由 RocketMQ 进入消费组死信 `%DLQ%hf-shipment-consumer-v1`。不要把消费问题计为发布失败；必要时从已出库履约手工建单，不会重复建。
- Redis 只缓存顶部三个计数，最长 30 秒；订单、出库、运单与事件列表都从 MySQL 读写。实际事件、Inbox、运单的对照用 [verify-m4.sql](infra/mysql/verify-m4.sql)。M3 已有出库不自动补事件。
- `bash backup.sh` 在 `.run/backups/` 安全导出 MySQL，不覆盖已有文件；指定输出路径时其父目录需已存在。恢复只在**新项目名、新端口、新卷**走，先保持投递关闭以免回放。验收脚本会导出隔离源库并在第二套隔离库恢复，比较余额、事件/Inbox，并用同号从恢复库 HTTP 回读。Redis 和 Broker 的数据不包含在此 MySQL 备份内；本地演练不是生产级容灾方案。

```bash
python3 tests/m1.py --m4 --upgrade-from-m3 .run/m3-before-v5.jar \
  --browser "$HOME/.mirasim/skills/gstack/browse/dist/browse"
```

`--upgrade-from-m3` 是可选的，用于真正从旧 jar 写入历史单再升级；没有旧 jar 可省略，仍会实测空库整链。离线无法拉 Redis 镜像时可显式加 `--native-redis`，使用本机可执行文件启动独立端口实例，不碰其他 Redis；RocketMQ 始终用真实容器。验收仅在隔离库提前重试时间，仍由真实工作器执行五次失败发送，不改变运行中的退避策略。

# HANDOFF｜供应链业务工作台

> 新会话先读本文件。事实和阶段进度在 [TASK-v1](docs/TASK-v1.md)，F01–F12 的设计在 [V1 规格](docs/v1-spec.md)。

## 当前停点

V1 的 M0–M4 已实现。M4 / A11–A12 已通过正式 Compose、浏览器和隔离恢复，**待用户验收**；主工作台 V5 和本项目中间件都运行，未动原业务数据。Git 基线以 `git log` 和远程 `main` 为准。

## 下一步

打开[运营汇总](http://127.0.0.1:5180/login?next=/operations)，再用现有上海 1 件走录单→路由→拣货→出库。回到运营页核对事件与自动运单，用 `infra/mysql/verify-m4.sql` 查同号。停服和恢复按 [README](README.md) 的隔离验收跑，不停主工作台做故障实验。未获新授权不加功能。

## 同步保留两条训练线

- 工程验收只记 [Task](docs/TASK-v1.md)。
- 学习摸底、独立实现和停点记在 [学习方案](docs/learning.md)；用 [面试训练](docs/interview.md) 把已核实、能讲清的个人工作整理成面试材料。两条训练线不可用 AI 跑通结果代替。

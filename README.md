# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

唯一管理员（本平台只有 admin 一个角色）：

- 用户名：`admin`
- 密码：`admin123`

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 异常整改闭环：多措施（临时/原因分析/长期）、前置依赖、提交-验证分离、
  原因分析审批、风险接受、按异常聚合的依赖/逾期/验证人视图，以及工单取消/重开联动（`/api/inspection/abnormalities`、
  详见[“异常整改闭环”](#异常整改闭环)）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 异常整改闭环

每条异常可建立多项整改措施（类别 temporary/cause/long_term），措施含负责人、期限、
前置措施依赖与每轮完成证据；措施状态只能 `open → submitted → verified`，验证失败退回
`open` 并保留轮次（历史为 append-only 事件），提交人与验证人必须不同。

闭环（`closedLoop=true`）由系统按以下**全部**条件统一裁决，任何单一条件都不能提前闭环：

1. 最近一次复检通过（复检历史只追加，工单取消/重开不删除复检）；
2. 关联工单状态为 done（cancelled / 重新打开时异常同步重新打开，但保留复检与事件）；
3. 严重异常（high/urgent）的原因分析已由非提交人批准；
4. 所有未取消措施均 verified（部分完成不闭环；cancelled 不阻塞）。

长期措施无法按期完成时只能登记**风险接受**（原因 + 到期日，`riskExpired` 提醒），
风险接受**永不**构成闭环。

主要 API：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/inspection/abnormalities/rectification` | 质量主管聚合视图（`status`、`overdueOnly`、`equipmentId` 过滤；含措施依赖、逾期标记、验证人、风险到期日、未闭环原因） |
| GET | `/api/inspection/tasks/{taskId}/rectification` | 巡检员从原任务追溯当前整改进展 |
| GET | `/api/inspection/abnormalities/{id}/rectification` | 单条异常的整改详情 |
| GET | `/api/inspection/abnormalities/{id}/events` | 异常级事件流（复检/审批/风险接受/工单同步/闭环，append-only） |
| GET | `/api/inspection/abnormalities/actions/{actionId}/events` | 措施级事件流（创建/提交/验证/退回/取消，含轮次） |
| POST | `/api/inspection/abnormalities/{id}/actions` | 建立整改措施（类别、负责人、期限、前置措施） |
| POST | `/api/inspection/abnormalities/actions/{actionId}/submit` | 负责人提交完成证据（须本人） |
| POST | `/api/inspection/abnormalities/actions/{actionId}/verify` | 他人验证（`pass=false` 退回并保留轮次，重复通过回调幂等） |
| POST | `/api/inspection/abnormalities/actions/{actionId}/cancel` | 取消措施（被依赖时拒绝） |
| POST | `/api/inspection/abnormalities/{id}/cause-analysis` | 提交原因分析 |
| POST | `/api/inspection/abnormalities/{id}/cause-approval` | 原因分析审批（须非提交人，驳回需原因） |
| PATCH | `/api/work-orders/{id}/status` | 工单状态变更（新增 cancelled），同事务按规则同步异常闭环 |
| POST | `/api/inspection/abnormalities/{id}/risk-acceptance` | 登记风险接受（不闭环，含到期日） |

事件中的操作人优先取请求体 `actor`，缺省时回退到登录用户的显示名/用户名。

并发与幂等：所有措施/异常写操作先对异常行加悲观写锁（加锁前不水合实体），
同一异常上的并发验证、重复回调被串行化；工单状态未变化的重复 PATCH 不产生同步事件。

## 数据库迁移

版本化 SQL 位于 `src/main/resources/db/migration/V*.sql`，启动时由
`SchemaMigrationRunner` 按版本号顺序幂等执行（记录于 `schema_migrations` 表，
`ADD COLUMN` 经 JDBC 元数据判重）。可用 `app.schema-migration.enabled=false` 关闭；
`app.seed.enabled=false` 可禁用种子数据（集成测试使用）。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。

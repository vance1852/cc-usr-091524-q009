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
- 异常整改措施全流程：多措施（临时/长期）、负责人、期限、前置依赖、完成证据、
  提交-验证分离与轮次保留、追加事件（`/api/inspection/abnormalities/{id}/...`）
- 严重异常原因分析审批闸门、长期措施未完成时的风险接受（带到期日，不伪装闭环）
- 质量主管聚合看板（逾期措施、风险接受到期日、待审批严重异常）与巡检员任务进展追踪
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。

## 异常整改闭环规则（V2）

`InspectionAbnormality.closedLoop=true` 只有在以下条件**全部**满足时由系统统一判定：

1. 最近一次复检通过，且复检时间晚于关联工单最近一次“重新打开/取消后重启”时间（旧复检不删除，但不再有效）；
2. 关联工单已完成（`done`）且当前未处于取消状态；
3. 异常下**至少一项**整改措施，且所有措施状态均为 `verified`（部分措施完成不闭环）；
4. 严重级别（`high`/`urgent`）异常，原因分析已**获批**。

风险接受（`risk_acceptances`）只代表“带风险运行”，带到期日，任何情况下都不构成闭环；
全部整改完成并闭环时自动解除，记录保留。措施事件（`rectification_events`）只追加不修改。

### 措施生命周期

```
pending ──提交(带证据)──> submitted ──验证通过──> verified
                              │
                              └──验证失败──> returned ──重新提交(轮次+1)──> submitted
```

- 提交人（措施负责人）与验证人不能为同一人；前置措施未全部 `verified` 时不能提交；
- 重复回调（同一提交人+证据 / 同一验证人成功回调 / 工单状态重复回调）幂等处理；
- 所有状态流转在异常行级悲观锁内串行化，并发验证不会提前/重复置位 closedLoop。

### 工单联动规则

| 工单变化 | 对异常的影响 |
| --- | --- |
| open/in_progress → done | 重新评估闭环 |
| → cancelled | 闭环解除（若原本已闭环），异常置 `wo_cancelled`，复检记录保留 |
| done → open/in_progress（重新打开） | 设置 woReopenedAt，旧复检失效，须重新复检通过 |
| cancelled → open/in_progress → done | 取消后重启处置，同样须重新复检 |

### 新增接口（均需 Bearer Token）

- `GET  /api/inspection/abnormalities/{id}/rectification` — 按异常聚合：措施（含依赖序号、逾期标记、验证人）、事件、活跃风险接受及到期日、闭环卡点
- `POST /api/inspection/abnormalities/{id}/measures` — 新建措施（`category=temporary|long_term`、`owner`、`dueDate`、`predecessorIds`）
- `POST .../measures/{measureId}/submit` — 负责人提交完成证据
- `POST .../measures/{measureId}/verify` — 他人验证（`passed=false` 退回并保留轮次）
- `POST /api/inspection/abnormalities/{id}/cause-analysis` 及 `/approval` — 原因分析提交/审批
- `POST /api/inspection/abnormalities/{id}/risk-acceptance` — 判定是否可登记风险接受（须有未完成长期措施、到期日晚于今天）
- `GET  /api/inspection/tasks/{taskId}/rectification-progress` — 巡检员从原任务追踪当前进展
- `GET  /api/inspection/supervision/overview` — 质量主管看板：逾期措施、风险接受到期日、等待原因分析审批的严重异常

数据库迁移：`src/main/resources/db/migration/V2__rectification_workflow.sql`，
由 `SchemaMigrationRunner` 在启动时按版本号应用（仅 MySQL 执行；H2 测试库由 Hibernate 建表）。

## 测试

```bash
mvn test
```

15 个集成测试（H2 内存库）覆盖完整闭环主流程、严重异常审批闸门、风险接受、依赖与逾期聚合、
并发验证/重复回调/部分措施完成，以及端到端 HTTP 流程（含 401 鉴权与 422/409 错误码）。

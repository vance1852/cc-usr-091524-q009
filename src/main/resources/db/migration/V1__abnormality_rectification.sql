-- =====================================================================
-- V1__abnormality_rectification.sql
-- 异常整改闭环扩展：整改措施、措施事件流、异常事件流，以及异常表上的
-- 原因分析审批与风险接受字段。
--
-- 适用 MySQL 8.0；语句全部幂等（CREATE TABLE IF NOT EXISTS，
-- ADD COLUMN 由应用启动迁移器按 information_schema / JDBC 元数据判重）。
-- 纯 DBA 手工执行时：MySQL 8.0 的 ALTER TABLE ... ADD COLUMN 不支持
-- IF NOT EXISTS，请先确认列不存在再执行相应 ALTER 段。
-- =====================================================================

-- @ddl create-table=abnormality_corrective_actions
CREATE TABLE IF NOT EXISTS abnormality_corrective_actions (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    abnormality_id      BIGINT NOT NULL,
    category            VARCHAR(16) NOT NULL,
    title               VARCHAR(256) NOT NULL,
    content             VARCHAR(1024) DEFAULT '',
    prerequisite_ids    VARCHAR(256) DEFAULT '',
    owner_name          VARCHAR(64) NOT NULL,
    due_date            DATETIME NULL,
    status              VARCHAR(16) NOT NULL,
    current_round       INT DEFAULT 0,
    submitted_at        DATETIME NULL,
    submitted_by        VARCHAR(64) DEFAULT '',
    completion_evidence VARCHAR(1024) DEFAULT '',
    verified_at         DATETIME NULL,
    verified_by         VARCHAR(64) DEFAULT '',
    verify_comment      VARCHAR(512) DEFAULT '',
    cancelled_at        DATETIME NULL,
    cancelled_by        VARCHAR(64) DEFAULT '',
    cancel_reason       VARCHAR(512) DEFAULT '',
    created_at          DATETIME
);

-- @ddl create-table=corrective_action_events
CREATE TABLE IF NOT EXISTS corrective_action_events (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    action_id      BIGINT NOT NULL,
    abnormality_id BIGINT NOT NULL,
    type           VARCHAR(24) NOT NULL,
    round_no       INT DEFAULT 0,
    from_status    VARCHAR(16) DEFAULT '',
    to_status      VARCHAR(16) DEFAULT '',
    actor          VARCHAR(64) DEFAULT '',
    evidence       VARCHAR(1024) DEFAULT '',
    comment        VARCHAR(512) DEFAULT '',
    occurred_at    DATETIME
);

-- @ddl create-table=abnormality_events
CREATE TABLE IF NOT EXISTS abnormality_events (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    abnormality_id    BIGINT NOT NULL,
    type              VARCHAR(32) NOT NULL,
    actor             VARCHAR(64) DEFAULT '',
    work_order_id     BIGINT NULL,
    work_order_status VARCHAR(16) DEFAULT '',
    risk_expiry_date  DATETIME NULL,
    closed_loop       BOOLEAN DEFAULT 0,
    detail            VARCHAR(1024) DEFAULT '',
    occurred_at       DATETIME
);

-- @ddl add-column=inspection_abnormalities.closed_loop_at
ALTER TABLE inspection_abnormalities ADD COLUMN closed_loop_at DATETIME NULL;
-- @ddl add-column=inspection_abnormalities.cause_analysis
ALTER TABLE inspection_abnormalities ADD COLUMN cause_analysis VARCHAR(2048) DEFAULT '';
-- @ddl add-column=inspection_abnormalities.cause_submitted_by
ALTER TABLE inspection_abnormalities ADD COLUMN cause_submitted_by VARCHAR(64) DEFAULT '';
-- @ddl add-column=inspection_abnormalities.cause_submitted_at
ALTER TABLE inspection_abnormalities ADD COLUMN cause_submitted_at DATETIME NULL;
-- @ddl add-column=inspection_abnormalities.cause_approved
ALTER TABLE inspection_abnormalities ADD COLUMN cause_approved BOOLEAN NULL;
-- @ddl add-column=inspection_abnormalities.cause_approved_by
ALTER TABLE inspection_abnormalities ADD COLUMN cause_approved_by VARCHAR(64) DEFAULT '';
-- @ddl add-column=inspection_abnormalities.cause_approved_at
ALTER TABLE inspection_abnormalities ADD COLUMN cause_approved_at DATETIME NULL;
-- @ddl add-column=inspection_abnormalities.cause_reject_reason
ALTER TABLE inspection_abnormalities ADD COLUMN cause_reject_reason VARCHAR(512) DEFAULT '';
-- @ddl add-column=inspection_abnormalities.risk_accepted
ALTER TABLE inspection_abnormalities ADD COLUMN risk_accepted BOOLEAN DEFAULT 0;
-- @ddl add-column=inspection_abnormalities.risk_accepted_by
ALTER TABLE inspection_abnormalities ADD COLUMN risk_accepted_by VARCHAR(64) DEFAULT '';
-- @ddl add-column=inspection_abnormalities.risk_accepted_at
ALTER TABLE inspection_abnormalities ADD COLUMN risk_accepted_at DATETIME NULL;
-- @ddl add-column=inspection_abnormalities.risk_expiry_date
ALTER TABLE inspection_abnormalities ADD COLUMN risk_expiry_date DATETIME NULL;
-- @ddl add-column=inspection_abnormalities.risk_reason
ALTER TABLE inspection_abnormalities ADD COLUMN risk_reason VARCHAR(1024) DEFAULT '';

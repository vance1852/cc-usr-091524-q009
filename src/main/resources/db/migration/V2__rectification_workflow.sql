-- V2: 异常整改措施 / 追加事件 / 风险接受 / 原因分析审批 / 工单联动
-- MySQL 8.0，幂等：可重复执行。
-- 表结构与 JPA 实体保持一致；应用启动时由 SchemaMigrationRunner 记录到 schema_migrations。

CREATE TABLE IF NOT EXISTS rectification_measures (
    id BIGINT NOT NULL AUTO_INCREMENT,
    abnormality_id BIGINT NOT NULL,
    seq INT NOT NULL,
    category VARCHAR(16) NOT NULL,
    title VARCHAR(128) NOT NULL,
    description VARCHAR(1024),
    owner VARCHAR(64),
    due_date DATE,
    predecessor_ids VARCHAR(255),
    status VARCHAR(16) NOT NULL,
    verify_round INT NOT NULL,
    evidence VARCHAR(2048),
    submitted_by VARCHAR(64),
    submitted_at DATETIME(6),
    verified_by VARCHAR(64),
    verified_at DATETIME(6),
    verify_comment VARCHAR(512),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_rect_measure_ab (abnormality_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rectification_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    abnormality_id BIGINT NOT NULL,
    measure_id BIGINT,
    type VARCHAR(32) NOT NULL,
    actor VARCHAR(64),
    comment VARCHAR(512),
    verify_round INT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_re_event_ab (abnormality_id),
    INDEX idx_re_event_measure (measure_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS risk_acceptances (
    id BIGINT NOT NULL AUTO_INCREMENT,
    abnormality_id BIGINT NOT NULL,
    outstanding_measure_ids VARCHAR(255),
    reason VARCHAR(512) NOT NULL,
    accepted_by VARCHAR(64) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    expire_date DATE NOT NULL,
    released BIT(1) NOT NULL,
    released_by VARCHAR(64),
    released_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_risk_ab (abnormality_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- inspection_abnormalities 新增列：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，逐列按 INFORMATION_SCHEMA 守卫。
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_analysis VARCHAR(2048)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_analysis');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_submitted_by VARCHAR(64)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_submitted_by');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_submitted_at DATETIME(6)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_submitted_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_approved BIT(1)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_approved');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_approved_by VARCHAR(64)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_approved_by');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_approved_at DATETIME(6)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_approved_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN cause_comment VARCHAR(512)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'cause_comment');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN wo_synced_status VARCHAR(16)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'wo_synced_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN wo_cancelled_at DATETIME(6)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'wo_cancelled_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE inspection_abnormalities ADD COLUMN wo_reopened_at DATETIME(6)', 'SELECT 1')
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'inspection_abnormalities' AND COLUMN_NAME = 'wo_reopened_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

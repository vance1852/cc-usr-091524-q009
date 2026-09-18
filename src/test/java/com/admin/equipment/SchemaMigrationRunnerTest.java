package com.admin.equipment;

import com.admin.equipment.db.SchemaMigrationRunner;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 持久化迁移测试：模拟"旧库升级"——
 * inspection_abnormalities 只含迁移前的旧列，三张新表均不存在，
 * 直接驱动 SchemaMigrationRunner 执行 classpath 中的版本化脚本，
 * 并验证重复执行幂等（ALTER ADD COLUMN 判重 + schema_migrations 版本记录）。
 */
class SchemaMigrationRunnerTest {

    private static final String URL =
            "jdbc:h2:mem:migration_upgrade;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private static final List<String> NEW_COLUMNS = List.of(
            "closed_loop_at", "cause_analysis", "cause_submitted_by", "cause_submitted_at",
            "cause_approved", "cause_approved_by", "cause_approved_at", "cause_reject_reason",
            "risk_accepted", "risk_accepted_by", "risk_accepted_at",
            "risk_expiry_date", "risk_reason");

    private static final List<String> NEW_TABLES = List.of(
            "abnormality_corrective_actions", "corrective_action_events", "abnormality_events");

    @Test
    void migratesOldSchemaAndIsIdempotent() throws Exception {
        DataSource ds = dataSource();
        try (Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            // 迁移前的旧表（仅旧列）
            st.execute("CREATE TABLE inspection_abnormalities ("
                    + "id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, title VARCHAR(256) NOT NULL, "
                    + "status VARCHAR(16), severity VARCHAR(16), closed_loop BOOLEAN, "
                    + "work_order_id BIGINT, work_order_created BOOLEAN)");
            for (String t : NEW_TABLES) {
                assertThat(tableExists(c, t)).isFalse();
            }
            for (String col : NEW_COLUMNS) {
                assertThat(columnExists(c, "inspection_abnormalities", col)).isFalse();
            }
        }

        SchemaMigrationRunner runner = new SchemaMigrationRunner(ds);
        runner.run(null);

        try (Connection c = ds.getConnection()) {
            for (String t : NEW_TABLES) {
                assertThat(tableExists(c, t)).as("迁移应创建表 %s", t).isTrue();
            }
            for (String col : NEW_COLUMNS) {
                assertThat(columnExists(c, "inspection_abnormalities", col))
                        .as("迁移应新增列 %s", col).isTrue();
            }
            // 新版本可在旧数据上写入新列
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into inspection_abnormalities"
                            + "(id, task_id, title, status, severity, closed_loop, cause_approved, risk_accepted) "
                            + "values (1, 1, '泵振动异常', 'resolved', 'high', true, true, false)")) {
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            long versions;
            try (PreparedStatement ps = c.prepareStatement(
                    "select count(*) from schema_migrations where version = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                versions = rs.getLong(1);
            }
            assertThat(versions).isEqualTo(1);
        }

        // 再次执行（模拟重启）：全部幂等跳过，不报错、不重复登记版本
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("select count(*) from schema_migrations");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    private DataSource dataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        return ds;
    }

    private boolean tableExists(Connection c, String table) throws Exception {
        DatabaseMetaData meta = c.getMetaData();
        for (String t : new String[]{table, table.toUpperCase()}) {
            try (ResultSet rs = meta.getTables(null, null, t, new String[]{"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    private boolean columnExists(Connection c, String table, String column) throws Exception {
        DatabaseMetaData meta = c.getMetaData();
        for (String t : new String[]{table, table.toUpperCase()}) {
            for (String col : new String[]{column, column.toUpperCase()}) {
                try (ResultSet rs = meta.getColumns(null, null, t, col)) {
                    if (rs.next()) return true;
                }
            }
        }
        return false;
    }

}

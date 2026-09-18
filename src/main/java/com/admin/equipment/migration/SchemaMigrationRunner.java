package com.admin.equipment.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 极简版本化 SQL 迁移（不引入 Flyway/Liquibase）。
 *
 * <p>启动时扫描 classpath:db/migration/V*.sql，按版本号顺序执行尚未记录在 schema_migrations
 * 中的脚本。当前脚本面向 MySQL 8（INFORMATION_SCHEMA 守卫 + PREPARE 幂等加列）；
 * 其他数据库（如集成测试使用的 H2）由 Hibernate ddl-auto 建表，跳过这些脚本。
 */
@Component
@Order(0)
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    private final DataSource dataSource;

    public SchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            boolean mysql = product != null && product.toLowerCase().contains("mysql");
            ensureMigrationTable(conn);

            for (Resource res : discoverScripts()) {
                String name = res.getFilename();
                if (name == null) continue;
                Long version = parseVersion(name);
                if (version == null) continue;
                if (!mysql) {
                    log.info("非 MySQL 环境（{}）跳过迁移脚本 {}", product, name);
                    continue;
                }
                if (isApplied(conn, version)) continue;
                log.info("执行数据库迁移 {}", name);
                // 脚本全部为幂等 DDL；出错不记录版本，重启后可重跑。
                ScriptUtils.executeSqlScript(conn, res);
                markApplied(conn, version, name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("数据库迁移失败：" + e.getMessage(), e);
        }
    }

    private Resource[] discoverScripts() throws Exception {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
    }

    private Long parseVersion(String filename) {
        // V2__rectification_workflow.sql -> 2
        String head = filename.replaceFirst("^V", "").split("__")[0];
        try {
            return Long.parseLong(head);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void ensureMigrationTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                    + "version BIGINT NOT NULL PRIMARY KEY, "
                    + "script VARCHAR(255), "
                    + "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private boolean isApplied(Connection conn, long version) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(1) FROM schema_migrations WHERE version = " + version)) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private void markApplied(Connection conn, long version, String name) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO schema_migrations(version, script) VALUES ("
                    + version + ", '" + name.replace("'", "''") + "')");
        }
    }
}

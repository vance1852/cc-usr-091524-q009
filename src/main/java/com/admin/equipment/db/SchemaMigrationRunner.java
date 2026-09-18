package com.admin.equipment.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量版本化 SQL 迁移器（无 Flyway/Liquibase 依赖）。
 *
 * 扫描 classpath:db/migration/V*.sql，按版本号顺序在事务中执行；
 * 已应用版本记录于 schema_migrations 表，重启不重复执行。
 * ALTER TABLE ADD COLUMN 通过 JDBC 元数据判重，单个缺失列才执行，
 * 因此旧库（含 Hibernate ddl-auto=update 已补列的库）也可安全重放。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final Pattern ALTER_ADD_COLUMN =
            Pattern.compile("(?is)alter\\s+table\\s+([a-zA-Z0-9_]+)\\s+add\\s+column\\s+([a-zA-Z0-9_]+).*");
    private static final Pattern VERSION =
            Pattern.compile("V(\\d+)__.*\\.sql");

    private final DataSource dataSource;

    @Value("${app.schema-migration.enabled:true}")
    private boolean enabled = true;

    public SchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            log.info("schema 迁移已禁用（app.schema-migration.enabled=false）");
            return;
        }
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:db/migration/V*.sql");
        List<Resource> ordered = new ArrayList<>(List.of(resources));
        ordered.sort((a, b) -> {
            long va = versionOf(a);
            long vb = versionOf(b);
            return Long.compare(va, vb);
        });

        try (Connection conn = dataSource.getConnection()) {
            ensureMigrationsTable(conn);
            for (Resource res : ordered) {
                long version = versionOf(res);
                if (isApplied(conn, version)) continue;
                log.info("执行数据库迁移：{}", res.getFilename());
                applyMigration(conn, res, version);
            }
        }
    }

    private void applyMigration(Connection conn, Resource res, long version) throws Exception {
        String content;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) continue;
                sb.append(line).append('\n');
            }
            content = sb.toString();
        }

        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String raw : splitStatements(content)) {
                String sql = raw.trim();
                if (sql.isEmpty()) continue;
                Matcher m = ALTER_ADD_COLUMN.matcher(sql);
                if (m.matches()) {
                    String table = m.group(1);
                    String column = m.group(2);
                    if (columnExists(conn, table, column)) {
                        log.info("跳过已存在列：{}.{}", table, column);
                        continue;
                    }
                }
                log.debug("迁移SQL：{}", sql);
                st.execute(sql);
            }
            markApplied(conn, version);
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw new IllegalStateException("数据库迁移失败：" + res.getFilename() + "：" + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private List<String> splitStatements(String content) {
        // 迁移脚本中没有存储过程/触发器，按分号拆分即可
        List<String> result = new ArrayList<>();
        for (String s : content.split(";")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        String[] catalogs = new String[]{null, conn.getCatalog()};
        String[] tableNames = new String[]{table, table.toUpperCase(), table.toLowerCase()};
        String[] columnNames = new String[]{column, column.toUpperCase(), column.toLowerCase()};
        for (String catalog : catalogs) {
            for (String t : tableNames) {
                for (String c : columnNames) {
                    try (ResultSet rs = meta.getColumns(catalog, null, t, c)) {
                        if (rs.next()) return true;
                    }
                }
            }
        }
        return false;
    }

    private void ensureMigrationsTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                    + "version BIGINT PRIMARY KEY, "
                    + "filename VARCHAR(255), "
                    + "applied_at TIMESTAMP)");
        }
    }

    private boolean isApplied(Connection conn, long version) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "select 1 from schema_migrations where version = ?")) {
            ps.setLong(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void markApplied(Connection conn, long version) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into schema_migrations(version, filename, applied_at) values (?, ?, ?)")) {
            ps.setLong(1, version);
            ps.setString(2, "V" + version + ".sql");
            ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }

    private long versionOf(Resource r) {
        String name = r.getFilename() == null ? "" : r.getFilename();
        Matcher m = VERSION.matcher(name);
        if (m.matches()) return Long.parseLong(m.group(1));
        return Long.MAX_VALUE;
    }
}

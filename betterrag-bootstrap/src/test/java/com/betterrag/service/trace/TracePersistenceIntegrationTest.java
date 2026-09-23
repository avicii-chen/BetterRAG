package com.betterrag.service.trace;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 集成测试:rag_trace 落库与读回。
 * <p>
 * D4(2026-09-24 修订):开发期本地直连——使用本机 PG 专用评测库 betterrag_eval;
 * PG 不可达时 Assumptions.abort 自动跳过,不阻塞默认构建。
 */
@Tag("integration")
class TracePersistenceIntegrationTest {

    private static final String EVAL_DB = "betterrag_eval";
    private static final Pattern URL_PATTERN = Pattern.compile("jdbc:postgresql://([^/]+)/.*");

    private static String evalUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void prepareDatabase() {
        username = env("PGVECTOR_USERNAME", "postgres");
        password = env("PGVECTOR_PASSWORD", "postgres");
        String envUrl = env("PGVECTOR_URL", "jdbc:postgresql://localhost:5432/betterrag");
        Matcher matcher = URL_PATTERN.matcher(envUrl);
        String hostPort = matcher.matches() ? matcher.group(1) : "localhost:5432";
        String adminUrl = "jdbc:postgresql://" + hostPort + "/postgres";
        evalUrl = "jdbc:postgresql://" + hostPort + "/" + EVAL_DB;

        try (Connection ignored = DriverManager.getConnection(adminUrl, username, password)) {
            // PG 可达,继续
        } catch (Exception ex) {
            Assumptions.abort("本地 PG(" + hostPort + ")不可达,跳过集成测试:" + ex.getMessage());
        }
        createDatabaseIfMissing(adminUrl);
        applySchema();
    }

    @Test
    void trace落库并可读回() {
        DataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                evalUrl, username, password);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("DELETE FROM rag_trace");

        TraceRecorder recorder = new TraceRecorder(new JdbcTraceWriter(jdbcTemplate), true, Runnable::run);
        TraceContext ctx = recorder.start("it-session", "集成测试问题");
        ctx.setRewrittenQuery("集成测试:改写后问题");
        recorder.spanAround(ctx, "hybrid-retrieve", "集成测试问题",
                () -> List.of(1, 2), docs -> "docs=2");
        recorder.finish(ctx, "集成测试答案");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT session_id, question, rewritten_query, nodes::text AS nodes, "
                        + "answer_text, total_ms FROM rag_trace WHERE trace_id = ?",
                ctx.getTraceId());
        assertEquals("it-session", row.get("session_id"));
        assertEquals("集成测试问题", row.get("question"));
        assertEquals("集成测试:改写后问题", row.get("rewritten_query"));
        assertEquals("集成测试答案", row.get("answer_text"));
        assertNotNull(row.get("total_ms"));
        String nodes = String.valueOf(row.get("nodes"));
        assertTrue(nodes.contains("hybrid-retrieve"), "nodes 应包含节点名,实际:" + nodes);
        assertTrue(nodes.contains("SUCCESS"), "nodes 应包含状态,实际:" + nodes);
    }

    /** eval 库不存在时经 admin 连接创建;已存在(42P04)则忽略 */
    private static void createDatabaseIfMissing(String adminUrl) {
        try (Connection ignored = DriverManager.getConnection(evalUrl, username, password)) {
            return;
        } catch (SQLException expected) {
            // 库不存在,继续创建
        }
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + EVAL_DB);
        } catch (SQLException ex) {
            if (!"42P04".equals(ex.getSQLState())) {
                throw new IllegalStateException("创建评测库失败: " + EVAL_DB, ex);
            }
        }
    }

    private static void applySchema() {
        try (Connection connection = DriverManager.getConnection(evalUrl, username, password)) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("schema-trace.sql"), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("应用 schema-trace.sql 失败", ex);
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}

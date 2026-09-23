package com.betterrag.service.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * rag_trace 表写入实现(spec 01 §4.1/4.2)。
 * <p>
 * 失败兜底由 TraceRecorder 完成,本类只管写入。
 */
public class JdbcTraceWriter implements TraceWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String INSERT_SQL = """
            INSERT INTO rag_trace
                (trace_id, session_id, question, rewritten_query, nodes,
                 answer_text, error, total_ms, snapshot_hash, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, now())
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTraceWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(TraceRecord record) {
        String nodesJson;
        try {
            nodesJson = OBJECT_MAPPER.writeValueAsString(record.nodes());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("trace nodes 序列化失败", ex);
        }
        jdbcTemplate.update(INSERT_SQL,
                record.traceId(),
                record.sessionId(),
                record.question(),
                record.rewrittenQuery(),
                nodesJson,
                record.answerText(),
                record.error(),
                record.totalMs(),
                record.snapshotHash());
    }
}

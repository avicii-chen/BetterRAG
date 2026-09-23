-- BetterRAG trace 基础表(spec 01 §4.1)
-- D3:spring.sql.init + IF NOT EXISTS 幂等执行

-- 配置快照(S4 接入写入逻辑,trace 只存 snapshot_hash 避免冗余)
CREATE TABLE IF NOT EXISTS rag_config_snapshot (
    id            BIGSERIAL PRIMARY KEY,
    snapshot_hash CHAR(64)    NOT NULL UNIQUE,
    content       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 问答全链路轨迹
CREATE TABLE IF NOT EXISTS rag_trace (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        UUID        NOT NULL,
    session_id      VARCHAR(64),
    question        TEXT,
    rewritten_query TEXT,
    nodes           JSONB,
    answer_text     TEXT,
    error           TEXT,
    total_ms        BIGINT,
    snapshot_hash   CHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_trace_session ON rag_trace(session_id, created_at DESC);

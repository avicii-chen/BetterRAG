package com.betterrag.service.trace;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一次问答的 trace 上下文:start() 后绑定 ThreadLocal,由链路各组件写入 span。
 * <p>
 * NOOP 实例用于 trace 关闭时零开销直通(所有写操作 no-op)。
 */
public class TraceContext {

    private static final int DIGEST_MAX_CHARS = 200;

    /** 关闭开关时的共享空实现 */
    static final TraceContext NOOP = new TraceContext(true);

    private final boolean noop;
    private final UUID traceId;
    private final String sessionId;
    private final String question;
    private final long startedAt;
    private final List<NodeSpan> spans = new CopyOnWriteArrayList<>();
    private volatile String rewrittenQuery;

    private TraceContext(boolean noop) {
        this.noop = noop;
        this.traceId = null;
        this.sessionId = null;
        this.question = null;
        this.startedAt = 0L;
    }

    TraceContext(UUID traceId, String sessionId, String question) {
        this(traceId, sessionId, question, System.currentTimeMillis());
    }

    TraceContext(UUID traceId, String sessionId, String question, long startedAt) {
        this.noop = false;
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.question = question;
        this.startedAt = startedAt;
    }

    boolean isNoop() {
        return noop;
    }

    /**
     * 记录一个节点 span。public 供 S3 pipeline 桥接回放 DAG NodeTrace 使用。
     */
    public void addSpan(String node, String status, long costMs,
                        String inputDigest, String outputDigest, String error) {
        if (noop) {
            return;
        }
        spans.add(new NodeSpan(node, status, costMs,
                digest(inputDigest), digest(outputDigest), error));
    }

    /** 摘要:压缩空白并截断,防止 trace 表膨胀(spec 01:只存 digest 不存全文) */
    static String digest(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= DIGEST_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, DIGEST_MAX_CHARS) + "…";
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        if (!noop && rewrittenQuery != null) {
            this.rewrittenQuery = rewrittenQuery;
        }
    }

    public UUID getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public List<NodeSpan> getSpans() {
        return List.copyOf(spans);
    }
}

package com.betterrag.service.pipeline;

/**
 * 单节点执行记录(终态)。S3 编排切换时桥接到 TraceRecorder 落库 rag_trace。
 */
public record NodeTrace(String node, NodeStatus status, long costMs,
                        String inputDigest, String outputDigest, String error) {

    private static final int DIGEST_MAX_CHARS = 200;

    static NodeTrace success(String node, long costMs, String inputDigest, String outputDigest) {
        return new NodeTrace(node, NodeStatus.SUCCESS, costMs, inputDigest, outputDigest, null);
    }

    static NodeTrace failed(String node, long costMs, String inputDigest, String error) {
        return new NodeTrace(node, NodeStatus.FAILED, costMs, inputDigest, null, error);
    }

    static NodeTrace skipped(String node, String reason) {
        return new NodeTrace(node, NodeStatus.SKIPPED, 0L, null, null, reason);
    }

    /**
     * 通用摘要:集合只记类型与 size,其余记字符串;统一压缩空白并截断到 200 字符。
     */
    static String digest(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof java.util.Collection<?> collection) {
            text = value.getClass().getSimpleName() + "(size=" + collection.size() + ")";
        } else {
            text = String.valueOf(value);
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() <= DIGEST_MAX_CHARS) {
            return text;
        }
        return text.substring(0, DIGEST_MAX_CHARS) + "…";
    }
}

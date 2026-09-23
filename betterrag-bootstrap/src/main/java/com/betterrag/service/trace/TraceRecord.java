package com.betterrag.service.trace;

import java.util.List;
import java.util.UUID;

/**
 * rag_trace 表一行的写入载荷。
 *
 * @param traceId        本次问答唯一标识
 * @param sessionId      会话标识
 * @param question       原始问题
 * @param rewrittenQuery 改写后问题(rewrite 节点产出)
 * @param nodes          各节点 span 列表(存 JSONB)
 * @param answerText     最终答案全文
 * @param error          失败时的错误摘要
 * @param totalMs        端到端总耗时
 * @param snapshotHash   配置快照哈希(S4 起接入,当前为 null)
 */
public record TraceRecord(
        UUID traceId,
        String sessionId,
        String question,
        String rewrittenQuery,
        List<NodeSpan> nodes,
        String answerText,
        String error,
        Long totalMs,
        String snapshotHash) {
}

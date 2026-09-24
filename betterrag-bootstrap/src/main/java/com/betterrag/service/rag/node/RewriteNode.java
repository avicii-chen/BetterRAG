package com.betterrag.service.rag.node;

import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.RagNode;
import com.betterrag.service.rag.RewriteQueryTransformer;

import org.springframework.ai.rag.Query;

/**
 * 问题改写节点:复用 advisor 模式的 RewriteQueryTransformer(内部含失败回退原问题)。
 * <p>
 * 同时定义流水线黑板 key 常量,供 RAGService 与各节点共用。
 */
public class RewriteNode implements RagNode {

    public static final String QUESTION_KEY = "question";
    public static final String REWRITTEN_KEY = "rewrittenQuery";
    public static final String SESSION_ID_KEY = "sessionId";
    public static final String TOKEN_CONSUMER_KEY = "tokenConsumer";
    public static final String KB_KEY = "kb";

    private final RewriteQueryTransformer transformer;

    public RewriteNode(RewriteQueryTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public String id() {
        return "rewrite";
    }

    @Override
    public Object execute(NodeContext context) {
        String question = context.get(QUESTION_KEY);
        Query rewritten = transformer.transform(new Query(question));
        context.put(REWRITTEN_KEY, rewritten.text());
        return rewritten.text();
    }
}

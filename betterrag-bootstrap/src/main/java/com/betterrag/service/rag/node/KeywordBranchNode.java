package com.betterrag.service.rag.node;

import com.betterrag.service.pipeline.ExceptionPolicy;
import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.RagNode;
import com.betterrag.service.rag.KeywordDocumentRetriever;

/**
 * 关键词检索分支:降级路,SKIP_AND_CONTINUE——ES 不可用时退化为纯向量检索,
 * 与 advisor 模式 HybridDocumentRetriever 的单路降级语义保持一致。
 */
public class KeywordBranchNode implements RagNode {

    private final KeywordDocumentRetriever retriever;

    public KeywordBranchNode(KeywordDocumentRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String id() {
        return "keyword";
    }

    @Override
    public ExceptionPolicy exceptionPolicy() {
        return ExceptionPolicy.SKIP_AND_CONTINUE;
    }

    @Override
    public Object execute(NodeContext context) {
        String rewritten = context.get(RewriteNode.REWRITTEN_KEY);
        String kb = context.get(RewriteNode.KB_KEY);
        return retriever.retrieve(FilterQueries.build(rewritten, kb));
    }
}

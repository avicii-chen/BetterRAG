package com.betterrag.service.rag.node;

import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.RagNode;

import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

/**
 * 向量检索分支:主路,FAIL_FAST(向量库不可用时流水线终止,与语义重要性一致)。
 */
public class VectorBranchNode implements RagNode {

    private final VectorStoreDocumentRetriever retriever;

    public VectorBranchNode(VectorStoreDocumentRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String id() {
        return "vector";
    }

    @Override
    public Object execute(NodeContext context) {
        String rewritten = context.get(RewriteNode.REWRITTEN_KEY);
        String kb = context.get(RewriteNode.KB_KEY);
        return retriever.retrieve(FilterQueries.build(rewritten, kb));
    }
}

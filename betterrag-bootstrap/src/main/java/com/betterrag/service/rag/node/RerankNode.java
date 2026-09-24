package com.betterrag.service.rag.node;

import java.util.List;

import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.RagNode;
import com.betterrag.service.rag.RerankDocumentPostProcessor;

import org.springframework.ai.document.Document;

/**
 * Rerank 节点:复用 advisor 模式的 RerankDocumentPostProcessor(内部含降级回退向量分)。
 */
public class RerankNode implements RagNode {

    private final RerankDocumentPostProcessor processor;

    public RerankNode(RerankDocumentPostProcessor processor) {
        this.processor = processor;
    }

    @Override
    public String id() {
        return "rerank";
    }

    @Override
    public Object execute(NodeContext context) {
        Object candidate = context.getLastOutput();
        if (!(candidate instanceof List<?> candidates) || candidates.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Document> candidateDocs = (List<Document>) candidates;
        String rewritten = context.get(RewriteNode.REWRITTEN_KEY);
        String kb = context.get(RewriteNode.KB_KEY);
        return processor.process(FilterQueries.build(rewritten, kb), candidateDocs);
    }
}

package com.betterrag.service.rag.node;

import java.util.List;

import com.betterrag.config.RAGProperties;
import com.betterrag.service.pipeline.NodeContext;
import com.betterrag.service.pipeline.RagNode;
import com.betterrag.service.rag.RrfFusion;

import org.springframework.ai.document.Document;

/**
 * RRF 融合节点:从黑板读取双路检索结果融合;SKIP 分支缺位视为空列表。
 */
public class RrfFuseNode implements RagNode {

    private final RAGProperties properties;

    public RrfFuseNode(RAGProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "fuse";
    }

    @Override
    public Object execute(NodeContext context) {
        List<Document> vectorDocs = orEmpty(context.get("vector"));
        List<Document> keywordDocs = orEmpty(context.get("keyword"));
        return RrfFusion.fuse(vectorDocs, keywordDocs,
                properties.getRrfK(), properties.getRetrieveTopK());
    }

    @SuppressWarnings("unchecked")
    private static List<Document> orEmpty(Object docs) {
        return docs instanceof List<?> list ? (List<Document>) list : List.of();
    }
}

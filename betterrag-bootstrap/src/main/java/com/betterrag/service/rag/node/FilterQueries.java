package com.betterrag.service.rag.node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

/**
 * 组装带 kb 过滤的检索 Query:kb 写入 query context 的过滤表达式,
 * 向量与关键词两条分支共用同一约定(KeywordDocumentRetriever 从中提取 kb)。
 */
final class FilterQueries {

    private FilterQueries() {
    }

    static Query build(String text, String kb) {
        Map<String, Object> context = new HashMap<>();
        if (kb != null && !kb.isBlank()) {
            context.put(VectorStoreDocumentRetriever.FILTER_EXPRESSION,
                    "kb == '" + kb.replace("'", "\\'") + "'");
        }
        return new Query(text, List.of(), context);
    }
}

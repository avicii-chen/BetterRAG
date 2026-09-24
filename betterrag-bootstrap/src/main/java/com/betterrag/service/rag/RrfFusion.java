package com.betterrag.service.rag;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;

/**
 * RRF 融合工具(S3 抽取):advisor 模式(HybridDocumentRetriever)与
 * pipeline 模式(node/RrfFuseNode)共用同一份实现,避免双写漂移。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * @param k     RRF 平滑常数(惯例 60)
     * @param topK  融合后保留数量
     */
    public static List<Document> fuse(List<Document> primaryDocs,
                                      List<Document> secondaryDocs,
                                      int k, int topK) {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Document> docMap = new LinkedHashMap<>();
        accumulate(primaryDocs, k, scoreMap, docMap);
        accumulate(secondaryDocs, k, scoreMap, docMap);
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> docMap.get(entry.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static void accumulate(List<Document> docs, int k,
                                   Map<String, Double> scoreMap, Map<String, Document> docMap) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            scoreMap.merge(doc.getId(), 1.0 / (k + i + 1), Double::sum);
            docMap.putIfAbsent(doc.getId(), doc);
        }
    }
}

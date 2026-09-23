package com.betterrag.service.pipeline;

/**
 * 节点异常策略(D1)。
 * <ul>
 *   <li>FAIL_FAST(默认):向上传播并终止流水线;</li>
 *   <li>SKIP_AND_CONTINUE:记录 SKIPPED 后继续,用于降级节点
 *       (语义对齐 HybridDocumentRetriever 的单路降级)。</li>
 * </ul>
 */
public enum ExceptionPolicy {
    FAIL_FAST, SKIP_AND_CONTINUE
}

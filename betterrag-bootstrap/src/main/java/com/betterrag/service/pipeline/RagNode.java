package com.betterrag.service.pipeline;

/**
 * DAG 流水线节点(D1:自研轻量执行器,S2)。
 * <p>
 * 返回值写入 NodeContext.lastOutput,并作为下一节点输入摘要的来源;
 * 并行分支的输出建议同时写黑板(ctx.put)供后续融合节点读取。
 */
public interface RagNode {

    String id();

    /**
     * @return 节点输出,无业务返回值时可为 null
     */
    Object execute(NodeContext context) throws Exception;

    default ExceptionPolicy exceptionPolicy() {
        return ExceptionPolicy.FAIL_FAST;
    }
}

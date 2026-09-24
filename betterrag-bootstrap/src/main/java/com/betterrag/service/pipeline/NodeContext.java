package com.betterrag.service.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点间共享的黑板上下文(D1:ConcurrentHashMap,并行分支可安全写入)。
 * <p>
 * lastOutput 由 DagPipeline 主线程串行维护,并行分支不应写它,
 * 分支输出请用 put(key, value) 写黑板(key 惯例 = 分支节点 id)。
 */
public final class NodeContext {

    private final Map<String, Object> blackboard = new ConcurrentHashMap<>();
    private volatile Object lastOutput;

    public void put(String key, Object value) {
        blackboard.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) blackboard.get(key);
    }

    public Object getLastOutput() {
        return lastOutput;
    }

    /**
     * 由 DagPipeline 主线程串行维护;测试与桥接场景可显式设置。
     */
    public void setLastOutput(Object output) {
        this.lastOutput = output;
    }
}

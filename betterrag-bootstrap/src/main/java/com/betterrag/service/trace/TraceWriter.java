package com.betterrag.service.trace;

/**
 * trace 持久化出口。实现类专注写入;失败兜底由 TraceRecorder 统一完成。
 */
public interface TraceWriter {

    void write(TraceRecord record);
}

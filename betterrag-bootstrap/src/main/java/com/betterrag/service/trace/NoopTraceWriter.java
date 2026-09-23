package com.betterrag.service.trace;

/**
 * 空实现:trace 关闭或依赖(JdbcTemplate)缺失时使用,保证零 SQL。
 */
public class NoopTraceWriter implements TraceWriter {

    @Override
    public void write(TraceRecord record) {
        // no-op by design
    }
}

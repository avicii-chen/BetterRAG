package com.betterrag.config;

import com.betterrag.service.trace.JdbcTraceWriter;
import com.betterrag.service.trace.NoopTraceWriter;
import com.betterrag.service.trace.TraceRecorder;
import com.betterrag.service.trace.TraceWriter;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * trace 装配(spec 01 §4.2):
 * 开关关闭或 JdbcTemplate 缺失时回落 NoopTraceWriter(零 SQL);
 * 写库走独立单线程异步池,队列满丢弃最旧记录——观测数据可丢,业务绝不阻塞。
 */
@Configuration
public class TraceConfiguration {

    @Bean(name = "traceWriterExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor traceWriterExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1000),
                runnable -> {
                    Thread thread = new Thread(runnable, "rag-trace-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    public TraceWriter traceWriter(RAGProperties ragProperties,
                                   ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        if (!ragProperties.getTrace().isEnabled()) {
            return new NoopTraceWriter();
        }
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return new NoopTraceWriter();
        }
        return new JdbcTraceWriter(jdbcTemplate);
    }

    @Bean
    public TraceRecorder traceRecorder(TraceWriter traceWriter,
                                       RAGProperties ragProperties,
                                       @Qualifier("traceWriterExecutor") ThreadPoolExecutor executor) {
        return new TraceRecorder(traceWriter, ragProperties.getTrace().isEnabled(), executor);
    }
}

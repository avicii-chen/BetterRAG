package com.betterrag.service.trace;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 轨迹记录器(spec 01 §4.2)。
 * <p>
 * start() 绑定 ThreadLocal → 链路组件经 spanAround() 旁观计时 → finish() 异步落库。
 * 铁律:观测代码的任何异常只 log.warn,绝不向上抛,不影响问答业务。
 * <p>
 * ThreadLocal 说明:advisor 链在订阅线程上同步执行,span 均记录于调用线程;
 * 若个别回调落在其它线程,仅丢失对应 span(观测降级),业务不受影响。
 */
@Slf4j
public class TraceRecorder {

    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private final TraceWriter writer;
    private final boolean enabled;
    private final Executor executor;

    public TraceRecorder(TraceWriter writer, boolean enabled, Executor executor) {
        this.writer = writer;
        this.enabled = enabled;
        this.executor = executor;
    }

    /**
     * 开启一次问答 trace 并绑定当前线程;开关关闭时返回 NOOP 上下文。
     */
    public TraceContext start(String sessionId, String question) {
        return start(sessionId, question, System.currentTimeMillis());
    }

    /**
     * 指定起始时间的重载(S3 pipeline 桥接):DAG 先执行、后建 trace 并回放节点记录,
     * totalMs 仍以问答起点计。
     */
    public TraceContext start(String sessionId, String question, long startedAtMillis) {
        if (!enabled) {
            return TraceContext.NOOP;
        }
        TraceContext ctx = new TraceContext(UUID.randomUUID(), sessionId, question, startedAtMillis);
        CURRENT.set(ctx);
        return ctx;
    }

    /**
     * 当前线程绑定的 trace 上下文;无上下文(未开启/跨线程)返回 null,调用方直通。
     */
    public TraceContext current() {
        TraceContext ctx = CURRENT.get();
        return (ctx != null && !ctx.isNoop()) ? ctx : null;
    }

    public void finish(TraceContext ctx, String answer) {
        complete(ctx, answer, null);
    }

    public void finishWithError(TraceContext ctx, String error) {
        complete(ctx, null, error);
    }

    private void complete(TraceContext ctx, String answer, String error) {
        if (ctx == null || ctx.isNoop()) {
            return;
        }
        try {
            if (CURRENT.get() == ctx) {
                CURRENT.remove();
            }
            long totalMs = System.currentTimeMillis() - ctx.getStartedAt();
            TraceRecord record = new TraceRecord(
                    ctx.getTraceId(), ctx.getSessionId(), ctx.getQuestion(),
                    ctx.getRewrittenQuery(), ctx.getSpans(), answer, error, totalMs, null);
            executor.execute(() -> {
                try {
                    writer.write(record);
                } catch (Exception ex) {
                    log.warn("[Trace] trace 写入失败(已忽略,不影响业务): {}", ex.getMessage());
                }
            });
        } catch (Exception ex) {
            log.warn("[Trace] finish 阶段异常(已忽略): {}", ex.getMessage());
        }
    }

    /** 便捷重载:取当前线程上下文,无则直通 */
    public <T> T spanAround(String node, Supplier<T> work) {
        return spanAround(current(), node, null, work, null);
    }

    public <T> T spanAround(String node, String inputDigest, Supplier<T> work,
                            Function<T, String> outputDigest) {
        return spanAround(current(), node, inputDigest, work, outputDigest);
    }

    /** 显式传入 ctx(RAGService 主链路持有 ctx 的场景) */
    public <T> T spanAround(TraceContext ctx, String node, Supplier<T> work) {
        return spanAround(ctx, node, null, work, null);
    }

    public <T> T spanAround(TraceContext ctx, String node, Supplier<T> work,
                            Function<T, String> outputDigest) {
        return spanAround(ctx, node, null, work, outputDigest);
    }

    /** 显式 ctx + 摘要的完整版本(集成测试与主链路使用) */
    public <T> T spanAround(TraceContext ctx, String node, String inputDigest,
                            Supplier<T> work, Function<T, String> outputDigest) {
        if (ctx == null || ctx.isNoop()) {
            return work.get();
        }
        long startTime = System.currentTimeMillis();
        try {
            T result = work.get();
            ctx.addSpan(node, NodeSpan.SUCCESS, System.currentTimeMillis() - startTime,
                    inputDigest,
                    outputDigest == null ? null : outputDigest.apply(result),
                    null);
            return result;
        } catch (RuntimeException ex) {
            ctx.addSpan(node, NodeSpan.FAILED, System.currentTimeMillis() - startTime,
                    inputDigest, null, ex.getMessage());
            throw ex;
        }
    }
}

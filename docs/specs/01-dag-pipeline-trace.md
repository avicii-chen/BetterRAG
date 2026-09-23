# Spec 01 — DAG 化流水线与 Trace 落库

> 关联决策:D1 D2 D3 | 关联 Story:S1 S2 S3 | 依赖:无(本方向是其他方向的基座)

## 1. 背景与目标

当前检索链路(Rewrite→Hybrid→Rerank→Augment)整体封装在 `RetrievalAugmentationAdvisor` 内,`RAGService` 只能看到输入输出,阶段信息仅存于 log。目标:①每次问答的全链路轨迹落 PG(可观测、可回溯);②检索侧编排迁移到自研轻量 DAG 执行器,支持节点级状态记录与后续按节点重试/回放;③SSE 对外行为完全不变。

## 2. 范围 / 非目标

范围:trace 两张表与记录器、DAG 引擎(纯逻辑)、编排双模切换、组件埋点。
非目标:分布式追踪(OTel)、trace 可视化页面、节点级自动重试(本期只记录,不重试)、分布式部署。

## 3. 现状触点

| 位置 | 现状 | 改动 |
|------|------|------|
| `RAGConfiguration#retrievalAugmentationAdvisor` | 组装 advisor 链 | 保留;新增 pipeline 模式 Bean |
| `RAGService#streamAnswer` | 从 stream chunk 的 advisor context 抠 sources;手写计时日志 | 接 TraceRecorder;sources 改从编排结果取 |
| `RewriteQueryTransformer` / `HybridDocumentRetriever` / `RerankDocumentPostProcessor` | 三阶段逻辑所在(均自研类) | advisor 模式下在类内埋点 |
| `application.yaml` | 无编排/trace 配置 | 新增 `app.rag.orchestration`、`app.rag.trace.enabled` |

## 4. 技术方案

### 4.1 表结构(`schema-trace.sql`,sqlinit,D3)

```sql
CREATE TABLE IF NOT EXISTS rag_config_snapshot (
  id           BIGSERIAL PRIMARY KEY,
  snapshot_hash CHAR(64) NOT NULL UNIQUE,      -- SHA-256(prompts + rag 配置 + 编排模式)
  content      JSONB   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS rag_trace (
  id            BIGSERIAL PRIMARY KEY,
  trace_id      UUID NOT NULL,
  session_id    VARCHAR(64),
  question      TEXT,
  rewritten_query TEXT,
  nodes         JSONB,          -- [{node,status,inputDigest,outputDigest,costMs,error}]
  answer_text   TEXT,
  error         TEXT,
  total_ms      BIGINT,
  snapshot_hash CHAR(64),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_trace_session ON rag_trace(session_id, created_at DESC);
```

### 4.2 TraceRecorder(观测不拖垮业务,铁律)

API:`start(sessionId, question) → TraceContext`;`ctx.span(node, status, digest...)`;`finish(answer, totalMs)`。
实现要点:①写库走独立线程池/`@Async`,失败仅 `log.warn`,**任何情况下不向上抛**;②输入输出只存摘要(前 N 字符 + 数量),不存全文,防 trace 表膨胀;③`trace.enabled=false` 时全部 no-op(空实现),保证可一键关闭。

### 4.3 DAG 引擎(纯逻辑,~300 行)

```java
interface RagNode { String id(); NodeOutput execute(NodeContext ctx) throws Exception; }
class DagPipeline { DagPipeline add(RagNode node); PipelineResult run(NodeContext ctx); }
class ParallelNode implements RagNode { /* 包装多节点并发执行,join 后合并输出 */ }
```

- `NodeStatus`:RUNNING/SUCCESS/FAILED/SKIPPED;`NodeTrace`:node/status/costMs/inputDigest/outputDigest/error。
- 异常策略两档:`FAIL_FAST`(默认,向上传播)与 `SKIP_AND_CONTINUE`(降级节点用,如关键词检索路——对齐 HybridDocumentRetriever 现有降级语义)。
- `NodeContext`:`Map<String,Object>` 黑板模式 + 上一次节点输出;并发安全(ConcurrentHashMap)。
- 不做通用图(无环校验、条件分支)——线性 + 一层并行即当前流水线形态,够用即止。

### 4.4 编排双模切换与关键设计

`app.rag.orchestration: advisor(默认) | pipeline`。

pipeline 模式节点图:`RewriteNode → HybridRetrieveNode(内部 ParallelNode[VectorNode ∥ KeywordNode] → FuseNode) → RerankNode → GenerateNode`。

**关键设计——增强与记忆的顺序**:不能把"检索文档拼进 user message"的增强文本直接发给 ChatClient,否则 `MessageChatMemoryAdvisor` 会把增强后的长文本存进会话历史(污染记忆)。正确做法:检索三节点跑完后把 docs 放入 advisor-param,由一个薄的 `ContextInjectAdvisor`(置于 memory advisor **之后**)在发送给 LLM 前注入上下文——与现状 advisor 链里 memory 存原始问题的语义严格一致。此点必须有测试断言(见 TDD-7)。

advisor 模式:保持现状,在三个自研组件内部调用 TraceRecorder.span 埋点。**两种模式产出相同 schema 的 trace**,保证数据可比、模式可切换。

## 5. TDD 开发顺序(red → green)

1. 【unit】TraceRecorder SQL 与 JSONB 映射:写入后按 trace_id 读回字段完整(升级为 integration 直连本地 PG,D4)
2. 【unit】TraceRecorder 失败隔离:注入 DataSource 异常,断言不抛出、业务返回正常
3. 【unit】DagPipeline 顺序执行:3 个 fake node,断言执行序、NodeTrace 状态与 costMs 记录
4. 【unit】ParallelNode:两路并发 join 结果合并;单路抛异常 + SKIP_AND_CONTINUE → 另一路结果保留、状态 SKIPPED
5. 【unit】FAIL_FAST:第二个节点抛异常 → PipelineResult.failed,后续节点不执行
6. 【integration】advisor 模式埋点:mock ChatModel + 本地 PG/ES(betterrag_eval 域),跑一次问答,断言 trace 含 rewrite/hybrid/rerank/generate 四段 span
7. 【unit】记忆纯净性:pipeline 模式跑完后,ChatMemory 中该 session 存的用户消息 == 原始问题(不含增强文本)
8. 【integration】SSE 等价性:同一问题下 advisor 与 pipeline 两模式的 SSE 事件序列(meta/token/sources/done)一致

## 6. 验收标准

- 任一模式问答后,`rag_trace` 一条完整记录:≥4 个节点 span、总耗时、答案全文、snapshot_hash;
- `app.rag.orchestration` 切换无需改代码,两模式 SSE 行为一致(测试 8 守护);
- trace 写库故障不影响问答(测试 2 守护);
- `trace.enabled=false` 时零额外开销(无 SQL)。

## 7. 风险与回退

| 风险 | 缓解 |
|------|------|
| pipeline 与 advisor 行为差异(augment 模板、memory 顺序) | 测试 7/8 + S5/S6 评测回归;差异即回退开关 |
| trace 异步写与主流程资源竞争 | 独立小线程池;digest 摘要限长 |
| JSONB schema 后续演化 | nodes 存数组,新增字段向后兼容;大变更走 D3 的 Flyway 升级条件 |

# Spec 02 — 真实链路评测与配置快照

> 关联决策:D2 D4 D5 | 关联 Story:S4 S5 S6 | 依赖:S1(复用 snapshot 表与 TraceRecorder 连接)

## 1. 背景与目标

`src/test/.../evaluator/RAGEvaluator.java` 已有完整评测方法论(三维度 judge prompt、hit/MRR、Bad Case 归因),但检索与生成结果是**硬编码模拟**的,数据集写死在 Java 里。目标:①数据集文件化;②评测接真实链路;③每次运行强制关联配置快照,支持两次 run 对比;④产出入库的基线报告。这是后续一切"优化是否有效"的裁决机制。

## 2. 范围 / 非目标

范围:dataset.yaml 加载、ConfigSnapshotService、RetrievalEvaluator(hit@k/MRR/Recall@k)、GenerationEvaluator(LLM judge)、eval_run 持久化与 run 对比。
非目标:用户反馈回流(下一阶段)、CI 自动门禁(先手动跑通,门禁是后续增量)、RAGAS。

## 3. 现状触点

| 位置 | 现状 | 改动 |
|------|------|------|
| `RAGEvaluator`(test) | 模拟数据 + OkHttp 直调百炼 | 三维度 prompt 与指标算法**迁移复用**;运行入口重写;原 demo 保留 |
| `HybridDocumentRetriever` | 被测对象(检索) | 不改,新增调用方 |
| `RAGService#streamAnswer` | 被测对象(端到端) | 不改,评测用 Consumer 收集 token |
| `KnowledgeIngestionService#ingest` | 入库入口 | 被种子加载器复用(顺带成为入库的集成测试) |

## 4. 技术方案

### 4.1 数据集文件化

`src/main/resources/eval/dataset.yaml`(路径可配 `app.rag.eval.dataset`):

```yaml
cases:
  - id: q001
    kb: default
    query: 年假最多可以累计多少天?
    expected_answer: ...
    relevant_chunks:            # 定位方式:source + chunk_index(见 4.2)
      - source: employee-handbook.md
        chunk_index: 3
    intent: knowledge
```

### 4.2 关键机制:稳定 chunk 定位

现状 Document id 是随机 UUID,无法作为 golden 标注。方案:**评测种子固定**——`EvalSeedRunner` 在评测前清空目标 kb 的旧数据(按 source+kb 删 PG/ES)再经真实 `ingest` 灌入 `eval/fixtures/` 下固定文档;数据集用 `(source, chunk_index)` 二元组标注,RetrievalEvaluator 把检索返回的 Document 映射为二元组后比对。不引入"确定性 UUID"去侵入生产 ingestion 逻辑。

### 4.3 ConfigSnapshotService

`hash = SHA-256(全部 prompts/*.st 内容 + RAGProperties 序列化 + orchestration 模式)` → 写 `rag_config_snapshot`(hash 去重)。评测 run 创建时**必须**携带 snapshot_hash;无快照拒绝执行——保证任何指标都有"当时配置"可查。

### 4.4 RetrievalEvaluator【integration】

直接调用 `HybridDocumentRetriever.retrieve(new Query(改写后文本))`(改写单独经 RewriteQueryTransformer,保证与线上路径一致)。指标:hit@k、MRR、Recall@k、空结果率。结果与 per-case 明细落 `eval_run` 表:`(run_id, kind=retrieval, snapshot_hash, started_at, metrics JSONB, cases JSONB)`。

### 4.5 GenerationEvaluator【llm】

调用 `RAGService.streamAnswer`(或 pipeline 等价入口)收集答案与 sources;judge 沿用三维度(忠实度/相关性/正确率)prompt,temperature 0.1,**score 与 reason 原文落库**(可审计);复用 RAGEvaluator 的三段归因逻辑(检索阶段/生成阶段/知识库)对 correctness<4 的 case 打归因标签。`@Tag("llm")` 仅手动执行,不进 CI。

### 4.6 RunCompare

`EvalRunCompare.compare(runA, runB)`:按指标逐项输出差值与方向,控制台表格 + 写回报告文件(`eval/reports/`,git 入库基线 `baseline.json`)。这是"评测门禁"的最小可用形态。

## 5. TDD 开发顺序

1. 【unit】DatasetLoader:yaml 解析、字段校验(缺 query/relevant_chunks 报错带 case id)、空文件处理
2. 【unit】ConfigSnapshot:同配置 hash 稳定;任一 prompt 改一字 → hash 变;RAGProperties 任一字段变 → hash 变
3. 【unit】指标计算:hit@k/MRR/Recall@k 参数化用例(从 RAGEvaluator 已有算例迁移,含"兜底样本无标注跳过检索指标"分支)
4. 【unit】(source, chunk_index) 比对映射:多命中/无命中/重复 doc
5. 【integration】EvalSeedRunner 幂等:连续跑两次,库内 chunk 集合一致(清空重灌生效)
6. 【integration】RetrievalEvaluator 端到端:本地 betterrag_eval 域(PG+ES 直连,D4),种子 3 份文档 × ≥8 case,产出报告落 eval_run
7. 【unit】RunCompare 差值计算与格式
8. 【llm】GenerationEvaluator smoke:单 case 跑通 judge 解析与落库

## 6. 验收标准

- `./mvnw test -Pintegration` 一条命令产出含 snapshot_hash 的检索报告(S1 起,集成测试经 `-Pintegration` profile 触发);
- 改 `retrieve-top-k: 8→16` 重跑,RunCompare 能输出两次 run 各指标差值;
- 生成评测手动触发跑通,faithfulness/relevancy/correctness + reason 均落库;
- 数据集增删 case 只改 yaml,不动 Java。

## 7. 风险与回退

| 风险 | 缓解 |
|------|------|
| LLM judge 波动 | temperature 0.1;reason 落库可审计;检索指标纯规则不波动,门禁优先用检索指标 |
| 种子文档被手工改动 | fixtures 入 git,SeedRunner 每次清空重灌(幂等) |
| 本地直连环境与 CI 漂移 | D4:专用 betterrag_eval 域隔离开发数据;Docker 就绪(S16 前)后切 Testcontainers 并重跑基线,旧基线标记 `env=local` 作废 |
| 评测调用消耗 API 费用 | llm tag 隔离手动触发;检索评测零 LLM 成本可高频跑 |

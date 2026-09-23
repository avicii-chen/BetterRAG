# Spec 03 — Chunking 策略升级与上下文过滤

> 关联决策:D6 D7 D8 | 关联 Story:S7 S8 S9 S10 | 依赖:S3(过滤链注册进 pipeline;advisor 模式亦兼容)

## 1. 背景与目标

入库一律走固定 `TokenTextSplitter(800)`,语义边界(标题/表格/代码块)不被感知;Rerank 分数在 `RerankItem(index, score)` 中已解析但**未写入 Document metadata**,下游无法利用;进 prompt 的上下文无阈值过滤、无去重、无 token 预算。目标:①结构感知切分(md);②parent-child 小块检索大块生成;③rerank 分数落 metadata + 三级上下文过滤。所有行为受开关控制,**默认值由 spec 02 的评测对比决定**。

## 2. 范围 / 非目标

范围:MarkdownStructureSplitter(纯函数)、ingestion 策略路由、parent 存储/扩展、上下文过滤链、RAGProperties 配置组。
非目标:PDF 版面解析(表格识别等,后续)、embedding 模型微调、多表示索引(summary 索引)。

## 3. 现状触点

| 位置 | 现状 | 改动 |
|------|------|------|
| `KnowledgeIngestionService#splitToDocuments` | 唯一切分入口;metadata: source/filename/kb/file_type/chunk_index | 切分策略路由 + parent 双写 |
| `RAGConfiguration#tokenTextSplitter` | 固定策略 Bean | 保留,作为兜底与二次切分器 |
| `RerankDocumentPostProcessor#pickByRerankResults` | 拿 index 选 doc,**丢弃 score**;降级路径用 vector score 排序 | score 写入 metadata |
| `RAGProperties` | chunk 组配置 | 新增 chunking/filter/parent-child 配置组 |
| ES 索引(`ElasticsearchDocumentRepository`) | 关键词路检索 child | 不改结构;parent 扩展在 PG 侧 |

## 4. 技术方案

### 4.1 MarkdownStructureSplitter(纯函数,D6)

输入 md 文本 → `List<Section{headingPath, text}>`,`headingPath` 形如 `"员工手册 > 考勤 > 请假"`。规则:

- ATX 标题(`#`~`####`)分节;无任何标题的文档整体为一节(退化为 token 切分);
- fenced code block(``` 与 ~~~)与 Markdown 表格(连续 `|` 行)标记为原子块,**不得跨块切断**;
- 节长超 `chunk-size` → 节内用 TokenTextSplitter 二次切,所有子块继承 headingPath;
- 输出 Document 带 `heading_path` metadata(chunk_index 语义保持全局递增)。

### 4.2 Ingestion 策略路由

`app.rag.chunking.strategy: token(默认) | structure`。`file_type ∈ {md, markdown}` 且 strategy=structure → 结构切分;其余(pdf/doc/docx)维持 Tika + TokenTextSplitter。**默认值是否切换为 structure,以 S5 评测 hit@k/MRR 对比为准**——这是 D6"评测门禁决定灰度"的落点。

### 4.3 Parent-Child(D7)

表:`rag_parent_chunk(parent_id BIGSERIAL PK, source, kb, seq, text, created_at)`。
入库(开关 `app.rag.parent-child.enabled`,默认 false):结构切分模式下,**同一 headingPath 的相邻 child 聚合为一个 parent**(聚合后超 1500 token 则按序切多个 parent);child metadata 写 `parent_id`;parent 文本不进向量库不进 ES。
检索扩展:Rerank 之后 `ParentExpansionProcessor`(DocumentPostProcessor):按 parent_id 批量 `IN` 回查 → 同 parent 多 child 命中去重(保留最高分 child 的 score)→ child 文本替换为 parent 文本,`source/chunk_index/score` 元数据保留 → 受 token 预算约束。开关关闭时 Processor 直通。

### 4.4 Rerank 分数落 metadata

`pickByRerankResults` 中:`metadata.rerank_score = RerankItem.score`。降级路径(向量分排序)的文档:`rerank_score = -1`、`fallback_score = doc.getScore()`。下游过滤与 trace 均可消费。

### 4.5 上下文过滤链(注册顺序:Rerank → 4.3 扩展 → 三过滤)

| 过滤器 | 规则 | 配置(默认) |
|--------|------|-------------|
| `ScoreThresholdFilter` | `rerank_score < 阈值` 剔除;**全部低于阈值时保留 top1 并打 `low_confidence=true`**(供 answer prompt 触发兜底话术,衔接现有 allowEmptyContext) | `filter.rerank-score-threshold: 0.1`(初值保守,评测调优) |
| `NearDuplicateFilter` | 字符 3-gram Jaccard ≥ 0.85 判重,保留高分者;n≤8,O(n²) 可接受 | `filter.near-dup-jaccard: 0.85` |
| `TokenBudgetFilter` | jtokkit(D8)计数,超预算从低分尾部裁剪 | `filter.context-token-budget: 6000` |

## 5. TDD 开发顺序

1. 【unit】结构切分·典型 md:两级标题 → sections 数量与 headingPath 正确
2. 【unit】结构切分·保护:代码块/表格不被切断(构造跨行表格与 20 行代码块)
3. 【unit】结构切分·超长节二次切:子块数与 headingPath 继承
4. 【unit】结构切分·退化:无标题文档 / 空输入 / 只有标题无正文
5. 【unit】ingestion 路由:file_type 与 strategy 组合四种 case 的切分器选择(mock splitter 断言调用)
6. 【integration】结构切分入库→ES/PG 可检索,heading_path 可查
7. 【unit】parent 聚合规则:同 path 聚合 / 超 1500 自切 / 无 structure 元数据时跳过
8. 【unit】ParentExpansion:批量回查、同 parent 去重保高分、开关直通(mock repo)
9. 【unit】rerank_score 写入:mock RerankService 返回 (index,score),断言 metadata;降级路径断言 -1 + fallback_score
10. 【unit】ScoreThresholdFilter:阈值边界 / 全低分保留 top1 + low_confidence / -1 分处理
11. 【unit】NearDuplicateFilter:高相似剔除保高分、不相似直通(参数化边界 0.84/0.86)
12. 【unit】TokenBudgetFilter:预算内直通 / 超预算从尾部裁 / jtokkit 中文计数冒烟

## 6. 验收标准

- `strategy=structure` 上传 md 后,PG 中 chunk 带 heading_path;代码块/表格完整性可抽查;
- `parent-child.enabled=true` 时,Rerank 后进入 prompt 的是 parent 文本,sources 聚合展示不变;
- 过滤链三开关独立生效,组合无顺序副作用;`low_confidence` 能驱动答案走兜底话术;
- spec 02 评测:structure + 过滤链开启后,hit@k 与忠实度**不劣于**基线(劣化则默认值保持 token,仅保留能力开关)。

## 7. 风险与回退

| 风险 | 缓解 |
|------|------|
| 结构切分对非规范 md 效果差 | 退化规则(无标题→token);策略开关默认 token,评测转正 |
| parent 回查增加延迟 | 批量 IN + 开关;trace(S1)可量化实际耗时 |
| 阈值初值不当误杀上下文 | 全低分保留 top1 兜底;阈值经评测调优并记入 snapshot |
| ES 与 PG 数据不一致(双写) | ingestion 同事务语义 + SeedRunner 幂等测试覆盖 |

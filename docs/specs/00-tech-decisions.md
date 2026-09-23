# BetterRAG 技术决策汇总(ADR)

> 日期:2026-09-21 | 状态:已评审待执行 | 配套:spec 01~05,plan.json
> 决策原则:依赖最小化、TDD 友好(纯逻辑优先)、每步可回退、评测兜底(任何行为变更先有基线)。

| ID | 决策点 | 结论 | 关联 Story |
|----|--------|------|-----------|
| D1 | 检索编排模式 | 自研轻量 DAG 执行器 + `advisor/pipeline` 双模开关 | S2 S3 |
| D2 | Trace 存储 | PG 单表 JSONB + 独立配置快照表 | S1 S4 |
| D3 | DDL 管理 | `spring.sql.init` + `CREATE TABLE IF NOT EXISTS`,暂不引入 Flyway | S1 |
| D4 | 评测运行环境 | 开发期本地直连(betterrag_eval 专用域)+ `@Tag` 三层;Docker 就绪后 CI 切 Testcontainers | S5 S6 |
| D5 | Judge 评分 | LLM-as-judge(复用 RAGEvaluator 三维度)+ 规则断言混合 | S6 |
| D6 | Chunking | 自研 Markdown 层级切分纯函数,TokenTextSplitter 兜底,策略开关灰度 | S7 |
| D7 | Parent-Child | 独立 parent 表 + child metadata.parent_id,检索后扩展,默认关 | S8 S9 |
| D8 | Token 计数 | jtokkit(仅用于预算控制,不追求精确计费) | S10 |
| D9 | Agent 层 | Spring AI 原生组合(ChatClient/advisor/BeanOutputConverter)+ 手写 ReAct | S11~S13 |
| D10 | ChatMemory | spring-ai 官方 JDBC repository starter,复用 PG | S16 |
| D11 | RAG-as-MCP | mcp-server 经 REST 调 bootstrap 新检索端点 | S14 S15 |
| D12 | 租户过滤 | FilterExpressionBuilder 参数化,废弃手拼转义 | S16 |

---

## D1 编排模式:自研轻量 DAG vs 保留 Advisor 链 vs 完整工作流引擎

| 方案 | 优点 | 缺点 |
|------|------|------|
| A. 自研轻量 DAG(RagNode + DagPipeline) | 节点级 trace/重试/回放;正面命中"可观测可回溯";代码量可控(~300 行) | 需要自己拼 augment 步骤,与 Spring AI advisor 语义对齐有成本 |
| B. 保留 RetrievalAugmentationAdvisor,仅在自定义组件内埋点 | 零行为风险,改动最小 | "DAG"只是逻辑概念;无法做节点级重试/回放;JD 关键词弱命中 |
| C. 引入 Flowable/Temporal | 工业级 | 重依赖,个人项目运维/叙事均不匹配 |

**决定:A 为主、B 兜底,双模共存**。理由:Rewrite/Hybrid/Rerank 三阶段本就是自研类,从 advisor 配置搬到显式编排是机械迁移;保留 `app.rag.orchestration: advisor|pipeline` 开关,评测(S5/S6)守护切换,失败一键回退 advisor 模式。不选 C:为 RAG 单条流水线引入通用引擎是过度设计。

**TDD 影响**:DagPipeline 是纯 Java 逻辑,fake node 即可全覆盖;编排等价性用"SSE 事件序列 + memory 存储内容"断言(见 spec 01 关键设计)。

## D2 Trace 存储:PG JSONB 单表 vs 遥测平台 vs 多表规范化

| 方案 | 优点 | 缺点 |
|------|------|------|
| PG 单表 `rag_trace` + JSONB nodes | 零新组件;SQL/jsonb 函数即可分析;与业务库同事务域 | 高并发写入需考虑(个人项目量级无虞) |
| OTel + Langfuse/Phoenix | 生态标准,面板现成 | 新组件部署维护;Trace schema 受平台约束;面试演示依赖外部服务 |
| trace + node 两张表规范化 | 关系查询友好 | 表多、写入两次,当前分析需求用不到 |

**决定:PG 单表**。理由:PG 已在栈内;trace 的消费方是"评测回流 + Bad Case 归因"(后续阶段),JSONB 的灵活性够;先有数据再谈平台。Langfuse 列为观测升级的备选,不阻塞。
另设独立 `rag_config_snapshot` 表(hash 去重):trace 只存 snapshot_hash,避免每条 trace 冗余全量配置——这是"改了什么导致指标变化"可回答的前提。

## D3 DDL 管理:sqlinit vs Flyway

**决定:暂用 `spring.sql.init`(mode=always + IF NOT EXISTS)**。理由:当前新增表仅 4 张(见各 spec),项目尚无迁移工具,pgvector 自身也是 `initialize-schema=true` 模式;引入 Flyway 是净增概念。**触发升级条件**:表结构出现首次破坏性变更或多人协作时,整体切 Flyway(此为 S16 可选任务)。

## D4 评测运行环境:本地直连为主 + Testcontainers 为升级项

> 2026-09-24 修订:开发机确认无 Docker 且推迟到 S16 前再安装,决策由"Testcontainers 为主"调整如下。

| 方案 | 优点 | 缺点 |
|------|------|------|
| 本地直连(专用 betterrag_eval 库)【开发期采用】 | 零新增依赖,红绿循环最快;复用日常开发的 PG/ES 服务 | 与 CI/生产存在环境漂移;须严格隔离防污染开发数据 |
| Testcontainers(pgvector + ES 真容器)【Docker 就绪后】 | 环境完全确定,基线数字只反映代码变化;CI 零额外配置 | 需 Docker Desktop;容器启动 10~30s |
| H2/内嵌 ES 模拟 | 快 | pgvector 扩展与 ES ik 分词无法模拟,检索评测失去意义,排除 |

**决定:开发期(S1~S15)本地直连,`@Tag` 三层不变**:`unit`(纯逻辑)、`integration`(直连本地 PG/ES)、`llm`(仅手动)。隔离机制:integration 测试连接专用库 `betterrag_eval` + eval 专用 kb 前缀,SeedRunner 只清这个域,绝不触碰开发数据。**升级路径**:S16 前安装 Docker Desktop 后,CI 切 Testcontainers,本地一行 profile 配置换回;届时重跑一次基线入库并将旧基线标记 `env=local` 作废——跨环境的指标数字不做对比。

## D5 Judge 评分:LLM-as-judge vs RAGAS vs 纯规则

| 方案 | 优点 | 缺点 |
|------|------|------|
| LLM-as-judge(忠实度/相关性/正确率三维度) | test 目录 RAGEvaluator 的 prompt 与解析已验证可用;中文场景成熟 | 分数有波动 |
| RAGAS(Python) | 指标学术标准 | 无官方 Java 版,引入 Python sidecar,拒绝混栈 |
| 纯规则断言 | 确定性 | 覆盖不了语义忠实度 |

**决定:LLM-as-judge + 规则断言混合**。检索指标(hit@k/MRR/Recall@k)纯规则确定性计算;仅生成质量交给 judge(temperature 0.1,保留 reason 原文落库可审计);judge 模型可配置,走现有 SiliconFlow OpenAI 兼容口。

## D6 Chunking:自研结构切分 vs 保留 Token 切分 vs 引入外部库

| 方案 | 优点 | 缺点 |
|------|------|------|
| 自研 Markdown 层级切分(纯函数) | TDD 完美(输入输出都是字符串/对象);heading_path 元数据为 parent-child 和可解释性铺路;不依赖版本不确定的官方新 API | 需自维护(~200 行) |
| 全保留 TokenTextSplitter | 零改动 | 语义边界切断,JD"Chunking 策略优化"空缺 |
| langchain4j DocumentSplitter | 现成 | 引入第二套 AI 栈,与 Spring AI 并存,拒绝 |

**决定:自研 + 策略开关 `app.rag.chunking.strategy: token|structure`(默认 token)**。md/markdown 走结构切分,其他类型维持现状;是否把默认值切成 structure,由 S5 检索评测对比决定——这是"评测门禁决定灰度"的第一个实际用例。

## D7 Parent-Child(small-to-big):独立表 vs metadata 冗余 vs 邻近块合并

| 方案 | 优点 | 缺点 |
|------|------|------|
| 独立 `rag_parent_chunk` 表 + child.metadata.parent_id,检索后回查扩展 | 干净;parent 可独立更新;ES 不用存大文本 | 检索多一次批量回查 |
| parent 全文冗余进 child metadata | 查询零回查 | 向量库和 ES 双份冗余,更新一致性问题 |
| 命中后按 chunk_index 拉邻近块 | 无 schema 变更 | 邻近≠同一语义单元,heading 边界才对 |

**决定:独立表方案,默认关闭(`app.rag.parent-child.enabled`)**。parent 生成规则 = 同一 heading_path 下相邻 child 聚合,超 1500 token 自成 parent。开关默认 false 的理由:这是入库 schema 变更,先用评测证明收益(S6)再转正。

## D8 Token 计数:jtokkit vs 字符近似

**决定:jtokkit(`com.knuddels:jtokkit`,单依赖)**,仅用于 TokenBudgetFilter 的预算控制。理由:字符近似对中英混排误差大;jtokkit 是 tiktoken 的成熟 Java 移植。不追求与 DeepSeek 计费完全一致——预算控制允许 5% 误差。

## D9 Agent 层:Spring AI 原生组合 vs Agent 框架 vs 自研通用框架

| 方案 | 优点 | 缺点 |
|------|------|------|
| Spring AI 原生组合:ChatClient + BeanOutputConverter(意图结构化输出)+ 手写 ReAct 循环 + advisor | 无新依赖;面试可讲循环/终止条件/工具治理的实现细节;与现有代码同构 | 循环、终止、消息回填全手写(~200 行) |
| LangGraph4j 等框架 | 状态图现成 | 社区库,API 与 ChatClient 体系割裂;个人项目引框架反而讲不透 |
| 自研"通用 Agent 框架" | 叙事大 | 过度设计,做垂直不做通用 |

**决定:原生组合**。ReAct 循环手写反而是资产:maxSteps、工具错误作为 observation 继续、每步 SSE 事件,这些细节是面试深挖点。多 Agent 采用 supervisor 路由拓扑(Router→RagAgent/ToolAgent/ChatAgent),**不做并发编排**——需求里没有,诚实边界。

## D10 ChatMemory:官方 JDBC starter vs 自研 vs Redis

**决定:`spring-ai-starter-model-chat-memory-repository-jdbc`**。理由:Spring AI 1.x 官方组件,提供 `JdbcChatMemoryRepository` 与 PG schema 脚本;自研等价物毫无增益;Redis 是新组件,量级不需要。MessageWindowChatMemory 窗口策略保持不变,只换仓储。

## D11 RAG-as-MCP:REST 调用 vs 共享模块 vs 直连存储

| 方案 | 优点 | 缺点 |
|------|------|------|
| mcp-server 经 REST 调 bootstrap 新端点 `/api/rag/search` | 模块边界清晰;mcp-server 不感知存储 schema;复用 bootstrap 全部检索逻辑 | 多一跳网络 |
| 抽 betterrag-core 共享模块 | 无网络跳 | 两进程变一同进程语义,重构面大 |
| mcp-server 直连 PG/ES | 独立 | 检索逻辑双写,schema 耦合,违 DRY |

**决定:REST 调用**。bootstrap 检索端点本身也是 S5 评测与后续前端"引用预览"的复用资产,一举多得。

## D12 租户过滤:参数化表达式 vs 手拼转义

**决定:废弃 `RAGService.escapeForFilter` 手拼 `"kb == '...'"`,改 `FilterExpressionBuilder.eq()` 传对象**。理由:手工转义属于"每次都要记得做对"的类别风险,框架参数化一次性消除;同时为 user 维度扩展(`and(e1, e2)`)铺路。

# Spec 05 — RAG-as-MCP-Tool 与生产化加固

> 关联决策:D10 D11 D12 D3(Flyway 备选) | 关联 Story:S14 S15 S16 | 依赖:S14→S15;S16 建议收尾做

## 1. 背景与目标

mcp-server 模块目前只有 weather/exchange 演示工具,与知识库业务无关;bootstrap 侧:memory 单机内存态、无健康检查、无一键部署、kb 过滤手拼转义字符串。目标:①把 RAG 检索本身暴露为 MCP 工具(任何 MCP 客户端可查知识库);②memory 持久化;③compose 一键全栈;④健康检查;⑤过滤参数化。

## 2. 范围 / 非目标

范围:bootstrap 检索 REST 端点、RagSearchTool、ChatMemory JDBC、actuator+HealthIndicator、docker-compose+Dockerfile、FilterExpressionBuilder、README。
非目标:K8s/网关/JWT 认证体系、多实例水平扩展、PII 脱敏(列入下一阶段)、Flyway(触发条件见 D3)。

## 3. 现状触点

| 位置 | 现状 | 改动 |
|------|------|------|
| `betterrag-mcp-server` `WeatherTool/ExchangeRateTool` | `@McpTool` 注解模式 + record Response 风格 | 新增 RagSearchTool 沿用同风格 |
| `RAGController` | 仅 chat/stream 与 upload | 新增 POST `/api/rag/search` |
| `RAGConfiguration#chatMemory` | `InMemoryChatMemoryRepository` | 换官方 JDBC repository |
| `RAGService#escapeForFilter` | 手拼 `"kb == '...'"` + 单引号转义 | 删除,改 FilterExpressionBuilder |
| 根目录 | 无部署物 | docker-compose.yml、Dockerfile、scripts/smoke.sh |
| `application.yaml` | mcp client 指向 127.0.0.1:8081 | compose 内服务名互指 |

## 4. 技术方案

### 4.1 检索端点(bootstrap,~80 行)

`POST /api/rag/search` `{query, kb?, topK?}` → `{results: [{source, kb, chunkIndex, score, text}]}`。
实现 = 复用编排的检索侧(Rewrite→Hybrid→Rerank,**不生成**):S3 后直接调 pipeline 检索节点;S3 前可先直接调 HybridDocumentRetriever+Rerank。约束:topK ∈ [1,20] 默认 4;text 截断 500 字符(工具场景不需要全文)。异常走现有 GlobalExceptionHandler 结构化返回。该端点同时是 S5 评测与前端"引用预览"的复用资产(D11)。

### 4.2 RagSearchTool(mcp-server)

`@McpTool(name="ragSearch", description="检索 BetterRAG 企业知识库,返回最相关的文档片段")`,参数 `query`(必填)、`kb`(可选)。RestClient 调 4.1 端点,`app.mcp.rag-base-url` 可配(compose 内为服务名);连接/读超时 10s;非 2xx / 超时 → `Response{status:"error", message}`(不抛异常,MCP 工具错误也应可读)。TDD 用 MockRestServiceServer。

### 4.3 ChatMemory 持久化(D10)

引入 `spring-ai-starter-model-chat-memory-repository-jdbc`;PG 执行其 jar 内 `schema-chat-memory-postgresql` 脚本(经 sqlinit 或文档化手工);删除 InMemory Bean,`MessageWindowChatMemory` 窗口策略不变。效果:重启后会话续聊。

### 4.4 可观测健康检查

引入 `spring-boot-starter-actuator`,暴露 health/info。两个自定义 Indicator:`PgVectorHealthIndicator`(SELECT 1 + vector_store 表存在)、`EsHealthIndicator`(ping `es-url`,带 2s 超时,失败降级 `Status.DOWN` 但不阻塞主流程——ES 挂时系统应仍可纯向量问答,与 Hybrid 降级语义一致)。

### 4.5 docker-compose 一键全栈

服务:postgres(`pgvector/pgvector` 镜像)、elasticsearch(**固定版本** + `elasticsearch-plugin install analysis-ik` 与 ES 版本严格匹配,ENV 变量化)、betterrag-bootstrap、betterrag-mcp-server;env 注入 `SILICONFLOW_API_KEY`;健康检查依赖顺序(`depends_on: condition: service_healthy`)。Dockerfile:maven multi-stage(jdk17 build → jre 运行)。`scripts/smoke.sh`:起栈→上传 fixture→问答→search→mcp 调 ragSearch,全绿即部署验证通过。

### 4.6 FilterExpressionBuilder(D12)

`escapeForFilter` 删除;`FilterExpressionBuilder.eq("kb", kb)` 传对象表达式;为 user 维度预留 `and(eq("kb",..), eq("owner",..))` 组合。测试含恶意 kb 值(`a' || '1==1`)不再依赖转义正确性。

## 5. TDD 开发顺序

1. 【integration】search 端点:mock ChatModel 检索侧可用,断言结果结构/topK 边界/异常结构化
2. 【unit】RagSearchTool:MockRestServiceServer 三剧本(200 映射 / 超时 / 500 → status=error)
3. 【integration】ChatMemory JDBC:本地 PG roundtrip;**重启模拟**(新 repository 实例读旧数据);窗口裁剪行为
4. 【integration】HealthIndicator:本地 PG/ES 起停两态断言
5. 【unit】FilterExpression:eq/and 序列化正确;恶意值用例
6. 【手动】smoke.sh 全栈冒烟(compose 环境不进 CI,Windows Docker Desktop 前提)
7. 【unit→无】README/架构图更新(评审项)

## 6. 验收标准

- 外部 MCP 客户端(Qoder/Claude 等)通过 ragSearch 能查到已上传知识库内容并返回来源;
- `docker compose up` 一键起全栈,smoke.sh 全绿;
- 重启 bootstrap,原会话继续对话且历史完整;
- `/actuator/health` 正确反映 PG/ES 状态,ES 停机时问答仍可用(纯向量降级);
- kb 含特殊字符时检索正常,代码中无手拼过滤字符串。

## 7. 风险与回退

| 风险 | 缓解 |
|------|------|
| ik 插件与 ES 版本错配 | compose 双版本变量锁定;smoke.sh 校验分词可用 |
| bootstrap 未启动时工具调用 | 超时 + 结构化错误,不拖垮 mcp-server |
| JDBC schema 与现有 pgvector 表冲突 | 官方脚本独立表名;sqlinit 幂等 |
| 内存 memory 行为差异(裁剪时机) | 测试 3 显式覆盖窗口语义,差异即修 |

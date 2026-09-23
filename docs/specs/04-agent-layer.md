# Spec 04 — 意图路由、工具 Agent 与多 Agent 协作

> 关联决策:D9 | 关联 Story:S11 S12 S13 | 依赖:S3(编排稳定后再动主链路);S12 依赖 S11

## 1. 背景与目标

现状:所有问题都进同一条"RAG advisor + MCP 工具"链路——问天气也带知识库上下文,问知识也挂工具;无路由、无规划、无协作。目标:①意图三分(CHAT/RAG/TOOL)前置路由;②TOOL 类走显式 ReAct 循环(工具调用过程对用户可见);③supervisor 拓扑把 RagAgent/ToolAgent/ChatAgent 统一编排,SSE 协议扩展。test 目录 `intent/IntentClassifier`、`intent/IntentRouter` 已有学习版实现可参考迁移。

## 2. 范围 / 非目标

范围:IntentRouteService、ToolAgentService(ReAct 循环)、Supervisor 分派、SSE 新事件(route/tool_step)、前端适配。
非目标:通用 Agent 框架、并发多 Agent 编排、长期记忆/规划器(Planner)、Computer Use。

## 3. 现状触点

| 位置 | 现状 | 改动 |
|------|------|------|
| `RAGService#streamChat` | 直接进 RAG 链路 | 前置路由,按 intent 分派 |
| `RAGConfiguration#chatClient` | defaultToolCallbacks + 双 advisor | 保持(=RagAgent 用的 client);新增 ToolAgent 专用 ChatClient(带工具、无 RAG advisor)与 ChatAgent 专用(无工具、无 RAG advisor) |
| `NonReturnDirectToolCallback` | 工具包装 | ReAct 循环内复用,结果 digest 进 SSE |
| `RAGController` `/api/rag/chat/stream` | SSE 事件 meta/title/token/sources/suggestions/done/error | 新增 `route`、`tool_step` 事件;旧事件不动 |
| `static/index.html` | 消费现有事件 | 渲染意图徽标与工具步骤条 |

## 4. 技术方案

### 4.1 IntentRouteService

`ChatClient + BeanOutputConverter<IntentRoute>` 结构化输出:`{intent: CHAT|RAG|TOOL, confidence: 0~1, reason}`。prompt 模板 `prompts/intent-system.st`(含三类定义与 few-shot);`app.rag.intent-model` 可配廉价模型;temperature 0。降级铁律:**超时 2s / 解析失败 / confidence < 0.6 → 一律按 RAG 处理**——与项目全局降级哲学一致,路由是增强不是单点。

### 4.2 ToolAgentService(ReAct 循环,D9)

独立 ChatClient(system=`prompts/agent-system.st`,tools=MCP callbacks,无 RAG advisor、无检索开销)。手写循环:

```
for step in 1..maxSteps(默认5):
    resp = chatClient.call(历史)
    if resp 含 toolCalls:
        结果 = 经 NonReturnDirectToolCallback 执行
        SSE 推 tool_step{step, tool, argsDigest, resultDigest}
        结果作为 observation 回填历史,continue
    else:
        流式输出最终回答,终止
超 maxSteps → 注入"步骤用尽,请基于已有信息作答"强制收敛
工具异常 → 错误文本同样作为 observation 回填(错误也是信息,循环不中断)
```

### 4.3 Supervisor 拓扑

协议:`AgentTask{sessionId, question, intent}` / `AgentResult{type, answer, sources?}`;`interface Agent { boolean supports(Intent); AgentResult run(AgentTask, SSE sink); }`。`SupervisorService` 持有 `List<Agent>`,首个 `supports` 命中者执行——RagAgent(现编排)/ToolAgent(S4.2)/ChatAgent(轻量直答,带 memory)。**路由拓扑而非执行拓扑**:一次请求仍单 Agent 执行,不做并行编排(诚实边界,见 D9)。

### 4.4 记忆与会话连续性

三种 Agent 共用同一 `ChatMemory`(按 sessionId 隔离),跨轮意图可切换:上一轮 RAG、本轮 TOOL 无缝衔接,历史对 Agent 可见。

### 4.5 SSE 协议扩展

```
event: route   data: {"intent":"TOOL","confidence":0.93}
event: tool_step data: {"step":1,"tool":"queryWeatherForecast","argsDigest":"city=北京","resultDigest":"晴 28℃"}
```

前端:route → 顶部意图徽标;tool_step → 可折叠步骤条;其余不变。README 事件表同步更新。

## 5. TDD 开发顺序

1. 【unit】IntentRouteService:mock ChatModel 返回合法 JSON → 解析正确;返回烂文本 → 降级 RAG;confidence 0.5 → 降级 RAG;超时 → 降级 RAG
2. 【unit】意图 few-shot prompt 组装含 kb 上下文占位(快照 hash 覆盖 intent 模板)
3. 【unit】ReAct·两步收敛:fake ChatModel 剧本[toolCall→toolCall→text],断言循环 3 次、历史含 2 条 observation、最终文本输出
4. 【unit】ReAct·超步:maxSteps=2 剧本 3 次工具调用 → 第 2 步后强制收敛,输出含总结
5. 【unit】ReAct·工具异常:工具抛错 → observation 含错误文本且循环继续至收敛
6. 【unit】Supervisor 分派:三 intent 各命中对应 Agent;无 supports 时回退 RagAgent
7. 【integration】SSE 事件序列:TOOL 问题产生 route + tool_step* + token + done 的完整序列(MockMvc 收集)
8. 【integration】跨轮切换:同 session 先问知识再问天气,历史互通且两轮都正确分派

## 6. 验收标准

- "北京明天天气" → route:TOOL,前端可见工具步骤条,答案来自工具;再追问"那上海呢"仍走 TOOL 且利用历史;
- 知识库问题行为与 S3 之前完全一致(route:RAG);闲聊直答不走检索(响应时间显著下降,trace 可证);
- 意图服务宕机/超时,全部流量回落 RAG,功能无损;
- 三 Agent 共享 memory,会话连续。

## 7. 风险与回退

| 风险 | 缓解 |
|------|------|
| 意图误判伤害主链路 | 置信度阈值 + 全量回退 RAG;route 事件让误判可观测(trace 落库) |
| prompt 注入诱导乱调工具 | 工具白名单固定;args/result digest 进 trace 可审计 |
| ReAct 不终止 | maxSteps 硬上限 + 强制收敛注入 |
| 前端事件兼容 | 新事件只增不改;旧客户端忽略未知事件天然兼容 |

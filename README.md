# ragent-st

这是 Ragent 的渐进式教学重建项目，不是原项目的精简副本。

第 1～19 课已经完成。第 19 课加入能动态选择知识与订单工具的基础 Agent；第 20 课正在通过 MCP 接入独立工具服务，并为写工具增加 Skill、冻结参数、人工确认和重复执行保护。

课程资料位于项目内的 `tutorial/`，长期约束见 `tutorial/工作守则.md`。

## 当前验证命令

```bash
mvn test
```

当前普通测试应有二十一个全部通过：历史业务回归、结构化文档测试、第 12 课流程结构测试、第 16 课模型路由状态测试和第 17 课容量闸门测试。

第 6 课真实 Web 流程不会随普通测试自动运行。确认百炼环境已配置后，手动执行：

```bash
mvn -Dtest=QuestionApiLiveIT test
```

启动可以被 `curl` 或浏览器前端调用的应用：

```bash
mvn spring-boot:run
```

第 7 课真实流式与取消测试同样不会被普通测试自动运行。日常建议在 VS Code 中打开 `StreamingQuestionApiLiveIT`，点击测试方法旁的绿色按钮分别观察完整流和取消流。

第 8 课的同步上传测试已随第 10 课需求演化为 `AsyncKnowledgeUploadApiLiveIT`。测试文件保存在 `target/lesson10-uploads`；正常启动应用后，上传文件默认保存在已被 Git 忽略的 `data/uploads`。

第 9 课请先启用 Docker Desktop 的 WSL Integration，再打开 `KnowledgePersistenceIT` 点击绿色按钮。测试会自动启动临时 pgvector 容器，不调用百炼。正常运行应用时先执行：

```bash
docker compose up -d
```

数据库数据保存在命名卷中；不要使用会删除卷的 `docker compose down -v`。

第 10 课先运行不调用百炼的 `AsyncIngestionIT`，观察 pending、failed、retry 和防重复执行；再运行 `AsyncKnowledgeUploadApiLiveIT`，用真实 HTTP、PostgreSQL 和百炼验收后台成功通路。

第 12 课先运行 `IngestionPipelineTest` 理解两条顺序及断链/成环校验，再运行 `RemoteKnowledgeApiLiveIT` 验收远程获取、四节点、数据库和真实百炼，最后运行 `AsyncKnowledgeUploadApiLiveIT.shouldUploadIndexAndAnswerFromNewKnowledge` 回归旧上传三节点通路。

第 13 课打开 `ConversationQuestionApiLiveIT` 点击绿色按钮，观察两轮追问的消息顺序、模型改写、摘要水位和 Alice/Bob 用户隔离；测试会真实调用百炼查询改写、回答和摘要。

第 14 课打开 `IntentRoutingApiLiveIT` 点击绿色按钮，观察“年假 + 报销”被拆成两个子问题并分别定向检索，以及低置信度回落和同名意图澄清；测试会真实使用 pgvector 与百炼。

第 15 课打开 `HybridRetrievalApiLiveIT` 点击绿色按钮，观察错误码问题同时命中 `keyword` 和 `vector`，自然语言问题主要由 `vector` 命中；测试会真实调用 Embedding、精排 Chat 和答案 Chat。

第 16 课打开 `ModelRoutingTest` 点击绿色按钮，观察模型档位选择、失败候选进入 `OPEN`、冷却后的 `HALF_OPEN` 探测和恢复；再回归 `ConversationQuestionApiLiveIT`。

第 17 课先打开 `AdmissionGateTest`，观察模型和摄取任务的名额不会无限等待；真实上传流程仍可回归 `AsyncKnowledgeUploadApiLiveIT`。

第 18 课打开 `RagObservabilityApiLiveIT` 点击类名旁绿色按钮，观察同一文档多个 Chunk 聚合成一个来源、成功 Run 的节点时间线、反馈关联和失败 Run 定位；测试会自动启动 pgvector 并真实调用百炼。

第 19 课打开 `AgentApiLiveIT` 点击类名旁绿色按钮，一次观察“知识工具 → 订单工具 → 回答”、带旧 `sessionId` 恢复步骤和4次迭代上限。测试只脚本化模型的下一步选择，知识上传、数据库、Embedding、检索与 RAG 回答仍走真实实现。

第 20 课打开 `McpAgentApiLiveIT` 点击类名旁绿色按钮，一次观察“发现远端工具 → 只读查询 → 加载 Skill → 冻结写参数 → 人工批准 → 远端执行 → 最终回答”。测试会自动启动真实 MCP HTTP 服务和临时 pgvector，不调用百炼；重点检查确认前没有远端写入，以及重复批准返回409且不会再次写入。

第 20 课手工运行完整部署时，先用 `docker compose up -d` 启动数据库；再在第二个终端运行：

```bash
mvn -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=com.shitan.ai.mcp.McpOrderServerApplication
```

最后在第三个终端执行 `mvn spring-boot:run`。默认主应用连接 `http://127.0.0.1:8091/mcp`，也可以通过持久环境变量 `RAGENT_MCP_URL` 覆盖。启动后访问 `http://localhost:8080/api/system/health`，应看到数据库和 MCP 均可达、总状态为 `UP`。

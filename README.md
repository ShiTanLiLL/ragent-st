# ragent-st

这是 Ragent 的渐进式教学重建项目，不是原项目的精简副本。

第 1～13 课已经完成。第 14 课在同步会话问答前拆分复合问题，并为每个子问题选择知识库作用域；低置信度回落全库，同名候选先向用户澄清。

课程资料位于项目内的 `tutorial/`，长期约束见 `tutorial/工作守则.md`。

## 当前验证命令

```bash
mvn test
```

当前普通测试应有十六个全部通过：历史业务回归、结构化文档测试和第 12 课流程结构测试。

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

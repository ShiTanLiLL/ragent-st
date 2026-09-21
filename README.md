# ragent-st

这是 Ragent 的渐进式教学重建项目，不是原项目的精简副本。

第 1～9 课已经完成。第 10 课正在把同步上传改造成后台摄取：HTTP 只保存原文并登记任务，线程池随后执行解析、Embedding 和发布；任务、尝试次数、阶段耗时与错误都保存到 PostgreSQL。

课程资料位于项目内的 `tutorial/`，长期约束见 `tutorial/工作守则.md`。

## 当前验证命令

```bash
mvn test
```

当前应有十一个测试全部通过：九个已有问答、文件与向量检索测试，以及两个模型 HTTP/RAG 流程测试。

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

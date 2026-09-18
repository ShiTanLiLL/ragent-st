# ragent-st

这是 Ragent 的渐进式教学重建项目，不是原项目的精简副本。

第 1～8 课已经完成。第 9 课把知识库、文档、片段和 1024 维向量从内存 Map 迁移到 PostgreSQL + pgvector；本地原文件继续保留，Flyway 负责数据库结构，JDBC Repository 负责事务与向量 Top-1 查询。

课程资料位于相邻目录 `../tutorial/`，长期约束见 `../tutorial/工作守则.md`。

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

第 8 课请在 VS Code 中打开 `KnowledgeUploadApiLiveIT`，点击类名旁的绿色按钮一次运行成功上传问答与失败清理两个完整流程。测试文件保存在 `target/lesson8-uploads`；正常启动应用后，上传文件默认保存在已被 Git 忽略的 `data/uploads`。

第 9 课请先启用 Docker Desktop 的 WSL Integration，再打开 `KnowledgePersistenceIT` 点击绿色按钮。测试会自动启动临时 pgvector 容器，不调用百炼。正常运行应用时先执行：

```bash
docker compose up -d
```

数据库数据保存在命名卷中；不要使用会删除卷的 `docker compose down -v`。

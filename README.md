# ragent-st

这是 Ragent 的渐进式教学重建项目，不是原项目的精简副本。

第 1～7 课已经完成。第 8 课正在增加知识运营入口：创建知识库、上传 UTF-8 文本、本地保存原文、同步分块并建立内存向量索引，随后可以指定 `knowledgeBaseId` 查询新知识。本课代码已准备好，等待学生完成阅读与真实环境验收。

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

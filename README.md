# ragent-st

这是 Ragent 的渐进式教学重建项目，不是原项目的精简副本。

第 1～5 课已经完成。第 6 课正在把已有 RAG 能力开放为 HTTP API：Spring Boot 接收问题 JSON，空问题在调用百炼前返回 400，正常问题通过真实百炼返回回答和证据来源。本课代码已准备好，等待学生完成阅读与真实环境验收。

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

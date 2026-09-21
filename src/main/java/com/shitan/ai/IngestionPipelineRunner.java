package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按流程定义逐个调用节点，并统一记录节点开始、完成、失败与耗时。
 */
@Component
public class IngestionPipelineRunner {

    private final IngestionPipelineCatalog pipelineCatalog;
    private final IngestionTaskRepository taskRepository;
    private final Map<IngestionNodeType, IngestionNode> nodesByType;

    /**
     * 收集 Spring 中现有节点实现并建立类型映射；重复类型会在应用启动时立即失败。
     *
     * @param pipelineCatalog 当前两条已校验流程
     * @param taskRepository  节点状态日志的数据库入口
     * @param nodes           Spring 自动找到的全部 IngestionNode 实现
     */
    public IngestionPipelineRunner(
            IngestionPipelineCatalog pipelineCatalog,
            IngestionTaskRepository taskRepository,
            List<IngestionNode> nodes
    ) {
        this.pipelineCatalog = pipelineCatalog;
        this.taskRepository = taskRepository;
        this.nodesByType = collectNodes(nodes);
        validateImplementations();
    }

    /**
     * 从任务的 pipelineName 取得流程，依次执行节点，并让返回上下文成为下一节点输入。
     *
     * @param initialContext 包含任务和 running 文档的初始数据包
     * @return 最后节点处理完的上下文
     * @throws Exception 某个节点失败时，在记录失败日志后继续抛给任务服务收尾
     */
    public IngestionContext run(IngestionContext initialContext) throws Exception {
        IngestionTask task = initialContext.task();
        IngestionPipeline pipeline = pipelineCatalog.require(task.pipelineName());
        IngestionContext context = initialContext;

        for (int stepPosition = 0;
                stepPosition < pipeline.orderedNodes().size();
                stepPosition++) {
            IngestionPipelineNode configuredNode = pipeline.orderedNodes().get(stepPosition);
            long startedAt = System.nanoTime();
            taskRepository.startStep(
                    task.id(),
                    task.attemptCount(),
                    configuredNode.id(),
                    stepPosition
            );
            try {
                IngestionNode node = nodesByType.get(configuredNode.type());
                context = node.execute(context);
                taskRepository.completeStep(
                        task.id(),
                        task.attemptCount(),
                        configuredNode.id(),
                        elapsedMillis(startedAt)
                );
            } catch (Exception exception) {
                taskRepository.failStep(
                        task.id(),
                        task.attemptCount(),
                        configuredNode.id(),
                        elapsedMillis(startedAt),
                        readableMessage(exception)
                );
                throw exception;
            }
        }
        return context;
    }

    /**
     * 把 Spring 注入的列表改成按枚举查找的 Map，避免每执行一步都遍历实现列表。
     *
     * @param nodes 全部节点实现
     * @return 每种类型最多一个实现的映射
     */
    private Map<IngestionNodeType, IngestionNode> collectNodes(List<IngestionNode> nodes) {
        Map<IngestionNodeType, IngestionNode> result = new EnumMap<>(IngestionNodeType.class);
        for (IngestionNode node : nodes) {
            if (result.putIfAbsent(node.type(), node) != null) {
                throw new IllegalStateException("摄取节点实现重复：" + node.type());
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 确认当前流程引用的每一种节点都有实现，让缺组件问题在启动时暴露而不是运行到一半才失败。
     */
    private void validateImplementations() {
        for (String pipelineName : List.of(
                IngestionPipelineCatalog.UPLOAD_PIPELINE,
                IngestionPipelineCatalog.URL_PIPELINE
        )) {
            for (IngestionPipelineNode node : pipelineCatalog.require(pipelineName).orderedNodes()) {
                if (!nodesByType.containsKey(node.type())) {
                    throw new IllegalStateException("摄取节点没有实现：" + node.type());
                }
            }
        }
    }

    /**
     * 把纳秒计时换成 API 日志使用的毫秒，并避免极短操作出现负值。
     *
     * @param startedAt System.nanoTime 返回的开始时刻
     * @return 非负毫秒耗时
     */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * 从异常取可读原因，避免任务步骤保存空字符串。
     *
     * @param exception 节点抛出的错误
     * @return 可展示消息或异常类名
     */
    private String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}

package com.shitan.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 12 课流程结构测试：先看懂两条链的差异，再确认断链和成环会在执行前失败。
 */
class IngestionPipelineTest {

    /**
     * 用真实课程配置对比上传链与 URL 链，证明 URL 只多出开头的 fetch 节点。
     */
    @Test
    void shouldDescribeTwoLinearPipelinesInExecutionOrder() {
        IngestionPipelineCatalog catalog = new IngestionPipelineCatalog();

        List<String> uploadSteps = catalog.require(IngestionPipelineCatalog.UPLOAD_PIPELINE)
                .orderedNodes()
                .stream()
                .map(IngestionPipelineNode::id)
                .toList();
        List<String> remoteSteps = catalog.require(IngestionPipelineCatalog.URL_PIPELINE)
                .orderedNodes()
                .stream()
                .map(IngestionPipelineNode::id)
                .toList();

        assertEquals(List.of("parse", "embedding", "publish"), uploadSteps);
        assertEquals(List.of("fetch", "parse", "embedding", "publish"), remoteSteps);
    }

    /**
     * 人为写出一个指向不存在节点的配置，验证应用不会执行到一半才发现没有下游。
     */
    @Test
    void shouldRejectBrokenPipelineBeforeExecution() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new IngestionPipeline("broken", List.of(
                        new IngestionPipelineNode("fetch", IngestionNodeType.FETCH, "missing")
                ))
        );

        assertTrue(error.getMessage().contains("断链"));
    }

    /**
     * 人为让 parse 与 embedding 互相指回，验证校验器能阻止后台任务无限循环。
     */
    @Test
    void shouldRejectCyclicPipelineBeforeExecution() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new IngestionPipeline("cycle", List.of(
                        new IngestionPipelineNode("parse", IngestionNodeType.PARSE, "embedding"),
                        new IngestionPipelineNode("embedding", IngestionNodeType.EMBEDDING, "parse")
                ))
        );

        assertTrue(error.getMessage().contains("存在环"));
    }
}

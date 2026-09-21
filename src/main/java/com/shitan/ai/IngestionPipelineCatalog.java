package com.shitan.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 保存本课实际需要的两条线性流程：上传文件不获取远程内容，URL 来源先执行 fetch。
 */
@Component
public class IngestionPipelineCatalog {

    public static final String UPLOAD_PIPELINE = "uploaded-file";
    public static final String URL_PIPELINE = "remote-url";

    private final Map<String, IngestionPipeline> pipelines;

    /**
     * 在 Spring 启动阶段构造并校验两条当前流程；坏配置会阻止应用带病启动。
     */
    public IngestionPipelineCatalog() {
        IngestionPipeline upload = new IngestionPipeline(UPLOAD_PIPELINE, List.of(
                new IngestionPipelineNode("parse", IngestionNodeType.PARSE, "embedding"),
                new IngestionPipelineNode("embedding", IngestionNodeType.EMBEDDING, "publish"),
                new IngestionPipelineNode("publish", IngestionNodeType.PUBLISH, null)
        ));
        IngestionPipeline remote = new IngestionPipeline(URL_PIPELINE, List.of(
                new IngestionPipelineNode("fetch", IngestionNodeType.FETCH, "parse"),
                new IngestionPipelineNode("parse", IngestionNodeType.PARSE, "embedding"),
                new IngestionPipelineNode("embedding", IngestionNodeType.EMBEDDING, "publish"),
                new IngestionPipelineNode("publish", IngestionNodeType.PUBLISH, null)
        ));
        this.pipelines = Map.of(upload.name(), upload, remote.name(), remote);
    }

    /**
     * 按任务保存的名称取得已校验流程，让重试仍走原来的处理链。
     *
     * @param name 任务登记时选定的流程名称
     * @return 对应的流程定义
     */
    public IngestionPipeline require(String name) {
        IngestionPipeline pipeline = pipelines.get(name);
        if (pipeline == null) {
            throw new NoSuchElementException("摄取流程不存在：" + name);
        }
        return pipeline;
    }
}

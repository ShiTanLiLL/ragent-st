ALTER TABLE ingestion_task
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'upload'
        CHECK (source_type IN ('upload', 'url')),
    ADD COLUMN source_location TEXT,
    ADD COLUMN pipeline_name VARCHAR(64) NOT NULL DEFAULT 'uploaded-file';

ALTER TABLE ingestion_task_step
    ADD COLUMN step_position INTEGER;

UPDATE ingestion_task_step
SET step_position = CASE step_name
    WHEN 'parse' THEN 0
    WHEN 'embedding' THEN 1
    WHEN 'publish' THEN 2
    ELSE 99
END;

ALTER TABLE ingestion_task_step
    ALTER COLUMN step_position SET NOT NULL;

-- 历史上传任务已经由默认值补齐；新代码会明确写入这三个字段。
ALTER TABLE ingestion_task
    ALTER COLUMN source_type DROP DEFAULT,
    ALTER COLUMN pipeline_name DROP DEFAULT;

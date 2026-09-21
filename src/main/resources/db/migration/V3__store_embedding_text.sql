-- 展示正文保留 Markdown 表格等人类可读格式；embedding_text 保存真正发送给向量模型的结构化文本。
ALTER TABLE knowledge_chunk
    ADD COLUMN embedding_text TEXT,
    ADD COLUMN chunk_index INTEGER NOT NULL DEFAULT 0;

-- 兼容前十课已有数据：当时向量输入固定是“标题 + 换行 + 正文”。
UPDATE knowledge_chunk
SET embedding_text = title || E'\n' || content;

ALTER TABLE knowledge_chunk
    ALTER COLUMN embedding_text SET NOT NULL;

ALTER TABLE knowledge_chunk
    ALTER COLUMN chunk_index DROP DEFAULT;

CREATE INDEX idx_knowledge_chunk_document_order
    ON knowledge_chunk(document_id, chunk_index);

-- pgvector 为 PostgreSQL 增加 vector 类型、距离运算符和向量索引。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base (
    id VARCHAR(36) PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE knowledge_document (
    id VARCHAR(36) PRIMARY KEY,
    knowledge_base_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id),
    original_filename TEXT NOT NULL,
    stored_path TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    chunk_count INTEGER NOT NULL,
    error_message TEXT
);

CREATE TABLE knowledge_chunk (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    keywords TEXT NOT NULL,
    embedding VECTOR(1024) NOT NULL
);

-- 普通 B-tree 索引帮助按知识库找文档、按文档找片段。
CREATE INDEX idx_knowledge_document_base
    ON knowledge_document(knowledge_base_id);

CREATE INDEX idx_knowledge_chunk_document
    ON knowledge_chunk(document_id);

-- HNSW 索引按照余弦距离组织 1024 维向量，供 ORDER BY embedding <=> question 使用。
CREATE INDEX idx_knowledge_chunk_embedding
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);

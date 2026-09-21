CREATE TABLE ingestion_task (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL UNIQUE REFERENCES knowledge_document(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    current_step VARCHAR(32),
    attempt_count INTEGER NOT NULL DEFAULT 1,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ingestion_task_step (
    task_id VARCHAR(64) NOT NULL REFERENCES ingestion_task(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    step_name VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
    duration_ms BIGINT,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (task_id, attempt, step_name)
);

CREATE INDEX idx_ingestion_task_status ON ingestion_task(status);
CREATE INDEX idx_ingestion_task_step_task ON ingestion_task_step(task_id, attempt);

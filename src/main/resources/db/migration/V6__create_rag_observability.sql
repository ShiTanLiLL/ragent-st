CREATE TABLE rag_trace_run (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL
        REFERENCES conversation_session(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    question_summary TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
    assistant_message_id BIGINT REFERENCES conversation_message(id) ON DELETE SET NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_rag_trace_run_conversation_time
    ON rag_trace_run(conversation_id, user_id, started_at DESC);

CREATE TABLE rag_trace_node (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES rag_trace_run(id) ON DELETE CASCADE,
    node_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('completed', 'failed')),
    input_summary TEXT,
    output_summary TEXT,
    duration_ms BIGINT NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rag_trace_node_run_order
    ON rag_trace_node(run_id, id);

CREATE TABLE answer_feedback (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES rag_trace_run(id) ON DELETE CASCADE,
    assistant_message_id BIGINT NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    rating SMALLINT NOT NULL CHECK (rating IN (-1, 1)),
    reason VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

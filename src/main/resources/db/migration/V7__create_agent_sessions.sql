CREATE TABLE agent_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    latest_objective TEXT NOT NULL,
    knowledge_base_id VARCHAR(36) REFERENCES knowledge_base(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('running', 'completed', 'limit_reached', 'failed')),
    final_answer TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_session_user_time
    ON agent_session(user_id, updated_at DESC);

CREATE TABLE agent_step (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    iteration INTEGER NOT NULL,
    decision_type VARCHAR(16) NOT NULL CHECK (decision_type IN ('tool', 'answer')),
    decision_summary TEXT,
    tool_name VARCHAR(64),
    tool_input TEXT,
    observation TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (session_id, iteration)
);

CREATE INDEX idx_agent_step_session_order
    ON agent_step(session_id, iteration);

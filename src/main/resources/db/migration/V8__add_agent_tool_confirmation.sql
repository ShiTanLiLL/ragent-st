ALTER TABLE agent_session
    DROP CONSTRAINT agent_session_status_check;

ALTER TABLE agent_session
    ADD CONSTRAINT agent_session_status_check
        CHECK (status IN (
            'running', 'waiting_confirmation', 'completed', 'limit_reached', 'failed'
        ));

CREATE TABLE agent_confirmation (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_input TEXT NOT NULL,
    decision_summary TEXT,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('pending', 'executing', 'approved', 'denied', 'failed')),
    observation TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_confirmation_session_time
    ON agent_confirmation(session_id, created_at DESC);

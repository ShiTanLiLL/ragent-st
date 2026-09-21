CREATE TABLE conversation_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_conversation_session_user_time
    ON conversation_session(user_id, last_active_at DESC);

CREATE TABLE conversation_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL
        REFERENCES conversation_session(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_conversation_message_owner_order
    ON conversation_message(conversation_id, user_id, id);

CREATE TABLE conversation_summary (
    conversation_id VARCHAR(36) NOT NULL
        REFERENCES conversation_session(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    last_message_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id)
);

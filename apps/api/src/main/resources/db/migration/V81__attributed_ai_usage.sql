-- Dispatch attribution is durable before network I/O. No keys, content, or provider balances.
CREATE TABLE ai_dispatch (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 workspace_id uuid NOT NULL,
 session_id uuid NOT NULL,
 connector_id uuid,
 provider varchar(100) NOT NULL,
 anchor varchar(100) NOT NULL,
 after_anchor boolean NOT NULL DEFAULT false,
 accepted boolean NOT NULL DEFAULT true,
 closed boolean NOT NULL DEFAULT false,
 provider_revision bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_dispatch_session ON ai_dispatch(session_id, created_at, id);
-- Assign even incomplete steps before a provider restart. Later completion keeps that binding.
CREATE TABLE ai_step_attribution (
 session_id uuid NOT NULL,
 message_id varchar(100) NOT NULL,
 dispatch_id uuid NOT NULL REFERENCES ai_dispatch(id) ON DELETE CASCADE,
 PRIMARY KEY (session_id,message_id)
);
CREATE TABLE ai_usage (
 session_id uuid NOT NULL,
 message_id varchar(100) NOT NULL,
 dispatch_id uuid NOT NULL REFERENCES ai_dispatch(id) ON DELETE CASCADE,
 model varchar(300) NOT NULL,
 completed_at timestamptz NOT NULL,
 input_tokens bigint NOT NULL CHECK (input_tokens >= 0),
 output_tokens bigint NOT NULL CHECK (output_tokens >= 0),
 reasoning_tokens bigint NOT NULL CHECK (reasoning_tokens >= 0),
 cache_read_tokens bigint NOT NULL CHECK (cache_read_tokens >= 0),
 cache_write_tokens bigint NOT NULL CHECK (cache_write_tokens >= 0),
 PRIMARY KEY (session_id, message_id)
);

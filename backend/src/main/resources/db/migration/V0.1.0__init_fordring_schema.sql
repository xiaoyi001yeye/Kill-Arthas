CREATE TABLE credential_secret (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  auth_type VARCHAR(32) NOT NULL,
  encrypted_payload TEXT NOT NULL,
  algorithm VARCHAR(32) NOT NULL,
  iv VARCHAR(128) NOT NULL,
  key_version VARCHAR(64) NOT NULL,
  masked_summary VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE credential_master_key (
  id BIGSERIAL PRIMARY KEY,
  key_version VARCHAR(64) NOT NULL UNIQUE,
  encrypted_key TEXT NOT NULL,
  algorithm VARCHAR(32) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE access_target (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  environment VARCHAR(64),
  host VARCHAR(255) NOT NULL,
  auth_type VARCHAR(32) NOT NULL,
  username VARCHAR(128) NOT NULL,
  credential_id BIGINT,
  target_type VARCHAR(32) NOT NULL,
  container_name VARCHAR(255),
  process_id BIGINT,
  process_name VARCHAR(512),
  arthas_status VARCHAR(32) NOT NULL,
  telnet_port INTEGER,
  http_port INTEGER,
  latest_operation_time TIMESTAMPTZ,
  latest_failure_reason TEXT,
  created_by_name VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_access_target_status ON access_target (arthas_status);
CREATE INDEX idx_access_target_host ON access_target (host);

CREATE TABLE command_execution (
  id BIGSERIAL PRIMARY KEY,
  command TEXT NOT NULL,
  target_id BIGINT NOT NULL,
  target_snapshot TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  duration_ms BIGINT,
  source VARCHAR(32) NOT NULL,
  operator_name VARCHAR(128) NOT NULL,
  executed_at TIMESTAMPTZ NOT NULL,
  output_size_bytes BIGINT NOT NULL DEFAULT 0,
  output_truncated BOOLEAN NOT NULL DEFAULT FALSE,
  error_message TEXT,
  risk_level VARCHAR(32) NOT NULL DEFAULT 'ALLOW',
  risk_confirmed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_command_execution_target ON command_execution (target_id);
CREATE INDEX idx_command_execution_executed_at ON command_execution (executed_at DESC);
CREATE INDEX idx_command_execution_status ON command_execution (status);

CREATE TABLE command_output_chunk (
  id BIGSERIAL PRIMARY KEY,
  execution_id BIGINT NOT NULL,
  sequence INTEGER NOT NULL,
  content TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (execution_id, sequence)
);

CREATE INDEX idx_command_output_chunk_execution ON command_output_chunk (execution_id, sequence);

CREATE TABLE saved_command (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  command TEXT NOT NULL,
  description TEXT,
  visible_in_console BOOLEAN NOT NULL DEFAULT FALSE,
  operator_name VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (operator_name, name)
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(64),
  operator_name VARCHAR(128) NOT NULL,
  request_payload TEXT,
  result VARCHAR(32) NOT NULL,
  failure_reason TEXT,
  risk_level VARCHAR(32),
  risk_confirmed BOOLEAN,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action);

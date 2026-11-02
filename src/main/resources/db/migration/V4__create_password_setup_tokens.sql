CREATE TABLE password_setup_tokens (
                                       id         UUID        PRIMARY KEY,
                                       user_id    UUID        NOT NULL,
                                       token_hash VARCHAR(64) NOT NULL,
                                       expires_at TIMESTAMPTZ NOT NULL,
                                       used_at    TIMESTAMPTZ,
                                       created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_password_setup_tokens_hash ON password_setup_tokens (token_hash);

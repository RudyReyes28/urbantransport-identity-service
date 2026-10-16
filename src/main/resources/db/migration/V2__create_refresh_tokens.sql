CREATE TABLE refresh_tokens (
                                id         UUID         PRIMARY KEY,
                                user_id    UUID         NOT NULL REFERENCES users (id),
                                token_hash VARCHAR(64)  NOT NULL,
                                expires_at TIMESTAMP    NOT NULL,
                                revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
                                created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
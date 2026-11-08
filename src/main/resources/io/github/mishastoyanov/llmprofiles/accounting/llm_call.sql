CREATE TABLE IF NOT EXISTS llm_call (
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    profile            VARCHAR(200)             NOT NULL,
    vendor             VARCHAR(200)             NOT NULL,
    model              VARCHAR(200),
    input_tokens       BIGINT,
    output_tokens      BIGINT,
    total_tokens       BIGINT,
    input_per_million  DECIMAL(12, 4),
    output_per_million DECIMAL(12, 4),
    latency_ms         BIGINT                   NOT NULL,
    first_token_ms     BIGINT,
    error              VARCHAR(1000),
    tags               VARCHAR(4000)
)

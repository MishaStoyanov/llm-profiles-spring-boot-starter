-- Vendors, profiles and prices read by llm.source.jdbc. Written for PostgreSQL and H2.
-- A row replaces the YAML entry of the same name as a whole; columns left null are unset.

CREATE TABLE IF NOT EXISTS llm_vendor (
    name        VARCHAR(200)  NOT NULL PRIMARY KEY,
    type        VARCHAR(100)  NOT NULL,
    base_url    VARCHAR(1000),
    api_key     VARCHAR(1000),              -- a reference such as ${ANTHROPIC_API_KEY}, never the key itself
    auth_header VARCHAR(200),
    auth_value  VARCHAR(1000),              -- likewise a reference
    timeout     VARCHAR(50),                -- as in YAML: 60s, 2m, PT90S
    max_retries INTEGER
);

CREATE TABLE IF NOT EXISTS llm_profile (
    name                      VARCHAR(200)  NOT NULL PRIMARY KEY,
    vendor                    VARCHAR(200)  NOT NULL,
    model                     VARCHAR(200),
    temperature               DOUBLE PRECISION,
    max_tokens                INTEGER,
    reasoning                 VARCHAR(20),     -- off, low, medium, high or max
    path                      VARCHAR(1000),
    request                   VARCHAR(10000),
    response_text             VARCHAR(500),
    response_input_tokens     VARCHAR(500),
    response_output_tokens    VARCHAR(500),
    response_reasoning_tokens VARCHAR(500),
    tags                      VARCHAR(4000)    -- a JSON object of strings, as in {"feature": "summaries"}
);

CREATE TABLE IF NOT EXISTS llm_price (
    vendor             VARCHAR(200)   NOT NULL,
    model              VARCHAR(200)   NOT NULL,
    input_per_million  DECIMAL(12, 4) NOT NULL,
    output_per_million DECIMAL(12, 4) NOT NULL,
    PRIMARY KEY (vendor, model)
);
